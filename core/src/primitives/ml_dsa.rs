//! ML-DSA-65 (FIPS 204) wrapper.
//!
//! Wraps [`ml-dsa`](https://docs.rs/ml-dsa) at the pinned version
//! `0.1.0-rc.11`. We expose a minimal API surface tailored to the chencang
//! protocol: keygen from a `rand_core::CryptoRng`, deterministic signing
//! (FIPS 204 §3.4: deterministic variant is permitted), and length-checked
//! verification.
//!
//! # Size constants
//!
//! | item                 | bytes |
//! |----------------------|------:|
//! | public key (encoded) | 1952  |
//! | secret key (expanded encoding, see note) | 4032 |
//! | signature            | 3309  |
//!
//! Note: in `ml-dsa` 0.1.0-rc.11, the canonical signing-key serialization
//! is a 32-byte *seed* (the expanded 4032-byte form is deprecated). We keep
//! the `SECRET_KEY_LEN = 4032` constant to match the chencang V1 spec, but
//! internally we hold the upstream `SigningKey` value so that signing does
//! not re-derive the expanded key on every call.
//!
//! # Randomization
//!
//! The `sign` function takes an `rng` argument for API forward-compatibility
//! with hedged signing (FIPS 204 §3.4 randomized variant). The current
//! implementation uses the deterministic variant, so the rng is unused —
//! this matches the security guarantees of FIPS 204 and side-steps the
//! `rand_core` version mismatch between this crate (0.6) and `ml-dsa` (0.10).

use ml_dsa::signature::{Error as SigError, Signer, Verifier};
use ml_dsa::{
    KeyInit, MlDsa65, Signature as MlDsaSignature, SignatureEncoding,
    SigningKey as MlDsaSigningKey, VerifyingKey as MlDsaVerifyingKey,
};
use rand_core::{CryptoRng, RngCore};
use zeroize::Zeroize;

use crate::error::{Error, Result};

/// ML-DSA-65 公钥长度（FIPS 204 表 2）。
pub const PUBLIC_KEY_LEN: usize = 1952;

/// ML-DSA-65 私钥扩展编码长度（FIPS 204 §8.2 表 2 的 sk）。
///
/// 内部并不直接持有这 4032 字节；该常量保留以与 chencang V1 协议规范对齐。
/// 序列化路径请使用 32-byte 种子 ([`SecretKey::to_seed`])。
pub const SECRET_KEY_LEN: usize = 4032;

/// ML-DSA-65 签名长度（FIPS 204 表 2）。
pub const SIGNATURE_LEN: usize = 3309;

/// ML-DSA-65 种子长度（FIPS 204 ML-DSA.KeyGen 输入 ξ）。
pub const SEED_LEN: usize = 32;

/// ML-DSA-65 verifying key。
#[derive(Clone)]
pub struct PublicKey(MlDsaVerifyingKey<MlDsa65>);

/// ML-DSA-65 signing key。32-byte 种子在 `Drop` 时被 zeroize。
pub struct SecretKey {
    inner: MlDsaSigningKey<MlDsa65>,
    seed: [u8; SEED_LEN],
}

impl Drop for SecretKey {
    fn drop(&mut self) {
        self.seed.zeroize();
        // The upstream `ml_dsa::SigningKey` does not unconditionally implement
        // `ZeroizeOnDrop` (only behind the `zeroize` feature, which is off by
        // default). The seed is the sensitive bootstrap material; zeroizing it
        // is the principal defense-in-depth measure we can apply here.
    }
}

impl PublicKey {
    /// 序列化为 1952-byte 编码。
    #[must_use]
    pub fn to_bytes(&self) -> [u8; PUBLIC_KEY_LEN] {
        let enc = self.0.encode();
        let mut out = [0u8; PUBLIC_KEY_LEN];
        out.copy_from_slice(&enc);
        out
    }

    /// 从 1952-byte 编码反序列化。
    ///
    /// # Errors
    ///
    /// 长度不符返回 [`Error::InvalidLength`]。
    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != PUBLIC_KEY_LEN {
            return Err(Error::InvalidLength {
                expected: PUBLIC_KEY_LEN,
                got: bytes.len(),
            });
        }
        // hybrid_array::Array<u8, _> impls TryFrom<&[u8]>
        let enc: &ml_dsa::EncodedVerifyingKey<MlDsa65> =
            bytes.try_into().map_err(|_| Error::InvalidLength {
                expected: PUBLIC_KEY_LEN,
                got: bytes.len(),
            })?;
        Ok(PublicKey(MlDsaVerifyingKey::new(enc)))
    }
}

impl SecretKey {
    /// 导出 32-byte 种子。Caller 应当立即处理（写入磁盘前加密、用完 zeroize）。
    #[must_use]
    pub fn to_seed(&self) -> [u8; SEED_LEN] {
        self.seed
    }

    /// 从 32-byte 种子重建私钥（用于反序列化）。
    #[must_use]
    pub fn from_seed(seed: [u8; SEED_LEN]) -> Self {
        let inner = MlDsaSigningKey::<MlDsa65>::new(&seed.into());
        SecretKey { inner, seed }
    }

    /// 取对应公钥。
    #[must_use]
    pub fn public_key(&self) -> PublicKey {
        // The upstream `SigningKey` caches the verifying key when the `alloc`
        // feature is enabled (default), so this is cheap.
        let kp_vk: &MlDsaVerifyingKey<MlDsa65> = self.inner.as_ref();
        PublicKey(kp_vk.clone())
    }
}

/// 生成 ML-DSA-65 密钥对。
#[must_use]
pub fn generate_keypair<R: CryptoRng + RngCore>(rng: &mut R) -> (PublicKey, SecretKey) {
    let mut seed = [0u8; SEED_LEN];
    rng.fill_bytes(&mut seed);
    let sk = SecretKey::from_seed(seed);
    let pk = sk.public_key();
    (pk, sk)
}

/// 对消息签名。返回 3309-byte ML-DSA-65 签名。
///
/// 当前实现使用 FIPS 204 deterministic 变体，`rng` 参数保留供未来切换到
/// hedged variant 时使用。
#[must_use]
pub fn sign<R: CryptoRng + RngCore>(sk: &SecretKey, msg: &[u8], _rng: &mut R) -> Vec<u8> {
    let sig: MlDsaSignature<MlDsa65> = sk.inner.sign(msg);
    sig.to_bytes().to_vec()
}

/// 验签。
///
/// # Errors
///
/// 当签名长度不正确、解码失败或验签失败时返回 [`Error::SignatureFailed`]。
pub fn verify(pk: &PublicKey, msg: &[u8], sig: &[u8]) -> Result<()> {
    if sig.len() != SIGNATURE_LEN {
        return Err(Error::SignatureFailed);
    }
    let parsed: MlDsaSignature<MlDsa65> =
        MlDsaSignature::<MlDsa65>::try_from(sig).map_err(|_: SigError| Error::SignatureFailed)?;
    pk.0.verify(msg, &parsed)
        .map_err(|_: SigError| Error::SignatureFailed)
}
