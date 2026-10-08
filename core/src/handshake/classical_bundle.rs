//! 经典（suite 0x01）公开 prekey 类型 + Ed25519 验签。
//!
//! V1 仅用经典椭圆曲线（见 spec §4 顶部 2026-06-15 决策）。带内 `InbandBundle`
//! 经不可信信道传输的最小公钥集合：对端只需 X25519（做 X3DH）+ Ed25519（验 SPK
//! 签名 + 作 IK 身份锚）。后量子字段（ML-KEM/ML-DSA）不在 V1 wire 中出现——
//! 它们由 transcript 绑定，仅 PQ suite (0x02) 才需要。

use crate::error::{Error, Result};
use crate::identity::keypair::PublicIdentity;
use crate::prekey::signed::{classical_signed_bytes, SignedPreKey};
use crate::primitives::{ed25519, x25519};

/// 经典套件协议版本。
pub const CLASSICAL_VERSION_V1: u8 = 0x01;
/// 经典套件标识（X25519 + Ed25519）。
pub const CLASSICAL_SUITE_ID: u8 = 0x01;

/// 经典身份：仅 Ed25519 签名公钥 + X25519 DH 公钥（各 32B）。
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ClassicalPublicIdentity {
    /// Ed25519 签名公钥（身份锚 + 验 SPK 签名）。
    pub ed25519: [u8; 32],
    /// X25519 长期 DH 公钥（X3DH 用）。
    pub x25519: [u8; 32],
}

impl ClassicalPublicIdentity {
    /// 从完整 [`PublicIdentity`] 取经典两件套。
    #[must_use]
    pub fn from_full(p: &PublicIdentity) -> Self {
        Self {
            ed25519: p.ik_sig_ed25519.to_bytes(),
            x25519: p.ik_dh_x25519.0,
        }
    }
}

/// 经典签名预密钥：X25519 公钥 + Ed25519 签名（64B）+ epoch。
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ClassicalSignedPreKey {
    /// X25519 中期预密钥公钥。
    pub x25519: [u8; 32],
    /// 覆盖签名消息的 Ed25519 签名（由 IK 的 Ed25519 私钥签）。
    pub sig_ed25519: [u8; 64],
    /// 轮换 epoch 序号。
    pub epoch: u32,
}

impl ClassicalSignedPreKey {
    /// 从完整 [`SignedPreKey`] 取经典字段（丢弃 ML-KEM 公钥与 ML-DSA 签名）。
    ///
    /// `sig_ed25519` 取 [`SignedPreKey::sig_ed25519_classical`]——即只覆盖
    /// `x25519 || epoch` 的经典签名，因为带内包不携带 ML-KEM 公钥，无法重建
    /// 混合签名的 message。
    #[must_use]
    pub fn from_full(spk: &SignedPreKey) -> Self {
        Self {
            x25519: spk.spk_x25519.0,
            sig_ed25519: spk.sig_ed25519_classical,
            epoch: spk.epoch,
        }
    }
}

/// 经典一次性预密钥：仅 X25519 + id（不单独签名——篡改经 transcript 绑定保护）。
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ClassicalOneTimePreKey {
    /// OPK id。
    pub id: u32,
    /// X25519 一次性预密钥公钥。
    pub x25519: [u8; 32],
}

/// A 发给 B 的经典带内 prekey 包。
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ClassicalPreKeyBundle {
    /// 协议版本（必须 = [`CLASSICAL_VERSION_V1`]）。
    pub version: u8,
    /// 套件标识（必须 = [`CLASSICAL_SUITE_ID`]）。
    pub suite_id: u8,
    /// A 的经典身份公钥。
    pub ik: ClassicalPublicIdentity,
    /// A 的经典签名预密钥。
    pub spk: ClassicalSignedPreKey,
    /// 可选一次性预密钥。
    pub opk: Option<ClassicalOneTimePreKey>,
    /// 16 字节配对 nonce。
    pub pairing_nonce: [u8; 16],
    /// 邀请者用户名（AAD 绑定 + UI 展示）。
    pub inviter_username: String,
}

impl ClassicalPreKeyBundle {
    /// 硬校验 version/suite（防降级）+ 用 IK 的 Ed25519 验 SPK 签名。
    ///
    /// # Errors
    /// - [`Error::UnsupportedSuite`] 当 `suite_id` ≠ 0x01
    /// - [`Error::UnsupportedVersion`] 当 `version` ≠ 0x01
    /// - [`Error::SignatureFailed`] 当 SPK 的 Ed25519 签名验证失败
    pub fn verify(&self) -> Result<()> {
        if self.suite_id != CLASSICAL_SUITE_ID {
            return Err(Error::UnsupportedSuite(self.suite_id));
        }
        if self.version != CLASSICAL_VERSION_V1 {
            return Err(Error::UnsupportedVersion(self.version));
        }
        // SPK 的经典 Ed25519 签名覆盖 `x25519 || epoch`（与 `SecretSignedPreKey`
        // 里 `classical_signed_bytes` 一致）——带内包无 ML-KEM 公钥，故验经典段。
        let vk = ed25519::VerifyingKey(self.ik.ed25519);
        let msg = classical_signed_bytes(&x25519::PublicKey32(self.spk.x25519), self.spk.epoch);
        ed25519::verify(&vk, &msg, &self.spk.sig_ed25519)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::keypair::SecretIdentity;
    use crate::prekey::signed::SecretSignedPreKey;

    fn sample() -> (SecretIdentity, ClassicalPreKeyBundle) {
        let mut rng = rand::thread_rng();
        let ik = SecretIdentity::generate(&mut rng);
        let spk = SecretSignedPreKey::generate(&mut rng, &ik, 1);
        let bundle = ClassicalPreKeyBundle {
            version: 0x01,
            suite_id: 0x01,
            ik: ClassicalPublicIdentity::from_full(&ik.public()),
            spk: ClassicalSignedPreKey::from_full(&spk.public),
            opk: None,
            pairing_nonce: [0x22; 16],
            inviter_username: "alice".into(),
        };
        (ik, bundle)
    }

    #[test]
    fn valid_classical_bundle_verifies() {
        let (_ik, b) = sample();
        b.verify().expect("ed25519-signed SPK must verify");
    }

    #[test]
    fn tampered_spk_sig_fails_verify() {
        let (_ik, mut b) = sample();
        b.spk.sig_ed25519[0] ^= 1;
        assert!(b.verify().is_err());
    }

    #[test]
    fn wrong_suite_rejected() {
        let (_ik, mut b) = sample();
        b.suite_id = 0x02;
        assert!(matches!(
            b.verify(),
            Err(crate::error::Error::UnsupportedSuite(0x02))
        ));
    }
}
