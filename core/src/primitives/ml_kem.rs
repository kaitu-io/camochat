//! ML-KEM-768 (FIPS 203) wrapper.
//!
//! Wraps `RustCrypto` `ml-kem` 0.2.x. ML-KEM is a post-quantum Key
//! Encapsulation Mechanism (KEM) standardized by NIST in FIPS 203.
//!
//! 我们只暴露 768 参数集（NIST Category 3 ≈ AES-192），即陈仓 V1 协议
//! 在 PQXDH-hybrid 阶段所使用的参数。
//!
//! # 隐式拒绝（implicit rejection）
//!
//! 根据 FIPS 203，当 ciphertext 被篡改时，`decapsulate` **不返回错误**，
//! 而是返回一个由 sk + ct 派生的伪随机共享密钥。调用方负责后续的 AEAD
//! 校验来检测篡改。本 wrapper 保留这一语义：长度不正确时返回 `Error`，
//! 但只要长度正确就一定返回 `Ok(...)` —— 即使 ct 被改过。

use ml_kem::array::Array;
use ml_kem::kem::{Decapsulate, Encapsulate};
use ml_kem::{EncodedSizeUser, KemCore, MlKem768};
use rand_core::{CryptoRng, RngCore};
use zeroize::Zeroize;

use crate::error::{Error, Result};

/// ML-KEM-768 公钥（encapsulation key）字节长度。
pub const PUBLIC_KEY_LEN: usize = 1184;
/// ML-KEM-768 私钥（decapsulation key）字节长度。
pub const SECRET_KEY_LEN: usize = 2400;
/// ML-KEM-768 密文长度。
pub const CIPHERTEXT_LEN: usize = 1088;
/// 共享密钥长度（FIPS 203 固定 32 字节）。
pub const SHARED_SECRET_LEN: usize = 32;

/// ML-KEM-768 公钥（encapsulation key）。
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct PublicKey(pub [u8; PUBLIC_KEY_LEN]);

/// ML-KEM-768 私钥（decapsulation key）。Zeroized on drop.
///
/// `Clone` 允许在 transactional decrypt (#142) 路径上把 `SessionState` 复制成
/// tentative 工作副本。两份副本各自 zeroize-on-drop，因此 clone 不会延长密钥
/// 字节在内存中的生命周期超出原状态。
#[derive(Clone)]
pub struct SecretKey([u8; SECRET_KEY_LEN]);

/// ML-KEM 32-byte 共享密钥。Zeroized on drop.
pub struct SharedSecret([u8; SHARED_SECRET_LEN]);

impl PublicKey {
    /// 返回公钥字节。
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.0
    }

    /// 从字节构造公钥（仅用于反序列化）。
    #[must_use]
    pub fn from_bytes(bytes: [u8; PUBLIC_KEY_LEN]) -> Self {
        Self(bytes)
    }
}

impl SecretKey {
    /// 导出私钥字节（仅用于持久化）。Caller 应立即 zeroize。
    #[must_use]
    pub fn to_bytes(&self) -> [u8; SECRET_KEY_LEN] {
        self.0
    }

    /// 从字节构造私钥（仅用于反序列化已存储的密钥）。
    #[must_use]
    pub fn from_bytes(bytes: [u8; SECRET_KEY_LEN]) -> Self {
        Self(bytes)
    }
}

impl SharedSecret {
    /// 返回 32 字节共享密钥引用。
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; SHARED_SECRET_LEN] {
        &self.0
    }
}

impl Drop for SecretKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

impl Drop for SharedSecret {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

/// 生成新的 ML-KEM-768 密钥对，返回 `(pk, sk)`。
#[must_use]
pub fn generate_keypair<R: CryptoRng + RngCore>(rng: &mut R) -> (PublicKey, SecretKey) {
    // ml-kem 的 `generate` 返回顺序是 `(decap, encap)`，我们这里反过来对齐项目
    // 其他 wrapper 的 `(pk, sk)` 习惯。
    let (dk, ek) = <MlKem768 as KemCore>::generate(rng);

    let ek_bytes = ek.as_bytes();
    let dk_bytes = dk.as_bytes();

    let mut pk = [0u8; PUBLIC_KEY_LEN];
    pk.copy_from_slice(ek_bytes.as_slice());

    let mut sk = [0u8; SECRET_KEY_LEN];
    sk.copy_from_slice(dk_bytes.as_slice());

    (PublicKey(pk), SecretKey(sk))
}

/// 封装：用对端公钥派生一个新的共享密钥，返回 `(ciphertext, shared_secret)`。
///
/// # Panics
///
/// 上游 `ml-kem` 0.2.x 的 `encapsulate` 形式上返回 `Result`，但实现层面
/// 是 infallible (`Error = ()`，所有路径都返回 `Ok`)。我们对该 `Result`
/// 调用 `expect` 是安全的 —— 任何 panic 都意味着上游 crate 行为发生
/// 重大变化，应被立即发现。
#[must_use]
pub fn encapsulate<R: CryptoRng + RngCore>(pk: &PublicKey, rng: &mut R) -> (Vec<u8>, SharedSecret) {
    // 把公钥字节装回 `EncapsulationKey`。
    let ek_array: Array<
        u8,
        <<MlKem768 as KemCore>::EncapsulationKey as EncodedSizeUser>::EncodedSize,
    > = Array::try_from(pk.0.as_slice()).expect("PublicKey length is checked at compile time");
    let ek = <MlKem768 as KemCore>::EncapsulationKey::from_bytes(&ek_array);

    let (ct, ss) = ek
        .encapsulate(rng)
        .expect("ml-kem 0.2.x encapsulate is infallible");

    let mut ss_bytes = [0u8; SHARED_SECRET_LEN];
    ss_bytes.copy_from_slice(ss.as_slice());

    (ct.to_vec(), SharedSecret(ss_bytes))
}

/// 解封装：用自己的私钥解 ciphertext，返回共享密钥。
///
/// # Implicit rejection
///
/// 根据 FIPS 203，当 ct 长度正确但被篡改时，本函数仍返回 `Ok(...)`，
/// 但其内的 `shared_secret` 与发送方不同（伪随机派生）。这是规范要求的
/// 行为，并非 bug。
///
/// # Errors
///
/// - 当 `ct.len() != CIPHERTEXT_LEN` 时返回 [`Error::InvalidLength`].
/// - 当上游 `ml-kem` 报告 decapsulation 错误时（实际不应发生，上游为
///   infallible）返回 [`Error::Internal`].
///
/// # Panics
///
/// 内部 `Array::try_from(sk.0.as_slice())` 在私钥固定长度 `SECRET_KEY_LEN`
/// 下永远成功；若 `panic` 则意味着 `ml-kem` crate 的 `DecapsulationKey`
/// 编码长度发生变更，应当被立即发现。
pub fn decapsulate(sk: &SecretKey, ct: &[u8]) -> Result<SharedSecret> {
    if ct.len() != CIPHERTEXT_LEN {
        return Err(Error::InvalidLength {
            expected: CIPHERTEXT_LEN,
            got: ct.len(),
        });
    }

    // 私钥字节 → DecapsulationKey
    let dk_array: Array<
        u8,
        <<MlKem768 as KemCore>::DecapsulationKey as EncodedSizeUser>::EncodedSize,
    > = Array::try_from(sk.0.as_slice()).expect("SecretKey length is checked at compile time");
    let dk = <MlKem768 as KemCore>::DecapsulationKey::from_bytes(&dk_array);

    // 密文字节 → Ciphertext array
    let ct_array: Array<u8, <MlKem768 as KemCore>::CiphertextSize> =
        Array::try_from(ct).map_err(|_| Error::InvalidLength {
            expected: CIPHERTEXT_LEN,
            got: ct.len(),
        })?;

    let ss = dk
        .decapsulate(&ct_array)
        .map_err(|()| Error::Internal("ml-kem decapsulate failed".into()))?;

    let mut ss_bytes = [0u8; SHARED_SECRET_LEN];
    ss_bytes.copy_from_slice(ss.as_slice());
    Ok(SharedSecret(ss_bytes))
}
