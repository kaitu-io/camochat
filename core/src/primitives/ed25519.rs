//! Ed25519 签名 wrapper.
//!
//! Wraps `ed25519-dalek` v2.x. Upstream `SigningKey` derives `ZeroizeOnDrop`
//! natively, providing defense-in-depth for secret key bytes.

use ed25519_dalek::{Signer, SigningKey as DalekSigning, Verifier, VerifyingKey as DalekVerify};
use rand_core::{CryptoRng, RngCore};

use crate::error::{Error, Result};

/// 32-byte Ed25519 私钥。零化保护。
pub struct SigningKey(DalekSigning);

/// 32-byte Ed25519 公钥。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct VerifyingKey(pub [u8; 32]);

impl SigningKey {
    /// 生成新随机 Ed25519 私钥。
    #[must_use]
    pub fn generate<R: CryptoRng + RngCore>(rng: &mut R) -> Self {
        SigningKey(DalekSigning::generate(rng))
    }

    /// 从字节加载私钥（仅用于反序列化）。
    #[must_use]
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        SigningKey(DalekSigning::from_bytes(&bytes))
    }

    /// 取对应公钥。
    #[must_use]
    pub fn verifying_key(&self) -> VerifyingKey {
        VerifyingKey(self.0.verifying_key().to_bytes())
    }

    /// 签消息，返回 64-byte 签名。
    #[must_use]
    pub fn sign(&self, msg: &[u8]) -> [u8; 64] {
        self.0.sign(msg).to_bytes()
    }

    /// 导出私钥字节。Caller 应立即 zeroize。
    #[must_use]
    pub fn to_bytes(&self) -> [u8; 32] {
        self.0.to_bytes()
    }
}

impl VerifyingKey {
    /// 转为字节。
    #[must_use]
    pub fn to_bytes(&self) -> [u8; 32] {
        self.0
    }
}

/// 验签。失败返回 [`Error::SignatureFailed`].
///
/// # Errors
///
/// 当公钥点不在曲线上、签名格式错误或签名验证失败时，返回
/// [`Error::SignatureFailed`].
pub fn verify(vk: &VerifyingKey, msg: &[u8], sig: &[u8; 64]) -> Result<()> {
    let dvk = DalekVerify::from_bytes(&vk.0).map_err(|_| Error::SignatureFailed)?;
    let dsig = ed25519_dalek::Signature::from_bytes(sig);
    dvk.verify(msg, &dsig).map_err(|_| Error::SignatureFailed)
}
