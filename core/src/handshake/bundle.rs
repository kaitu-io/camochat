//! `PreKey` Bundle：Alice 给 Bob 的一整套公钥 + 签名。
//!
//! Bundle 是 Alice 在配对邀请中（或通过服务端预密钥目录）发布给对端的
//! 公开数据包：身份公钥 (IK)、中期签名预密钥 (SPK) 以及可选的一次性
//! 预密钥 (OPK)，同时携带配对元数据（邀请者用户名、邀请 ID、配对 nonce）。
//!
//! Bob 收到 bundle 后必须先调用 [`PreKeyBundle::verify`] 验证 SPK 双签名，
//! 然后才能用于 PQXDH-hybrid 握手（参见 [`crate::handshake::pqxdh`]）。

use crate::error::Result;
use crate::identity::keypair::PublicIdentity;
use crate::prekey::one_time::OneTimePreKey;
use crate::prekey::signed::SignedPreKey;

/// Alice 发布给 Bob 的公开预密钥包。
///
/// 字段顺序对应线上传输 / 邀请 URL 中的编码顺序；详见 wire format 文档。
#[derive(Clone)]
pub struct PreKeyBundle {
    /// 长期身份公钥四件套 (Ed25519 + ML-DSA-65 + X25519 + ML-KEM-768)。
    pub ik: PublicIdentity,
    /// 中期签名预密钥（被 `ik` 双签）。
    pub spk: SignedPreKey,
    /// 可选的一次性预密钥；缺失时握手退化为只用 IK + SPK。
    pub opk: Option<OneTimePreKey>,
    /// 邀请者（Alice）的用户名，用于 AAD 绑定及 UI 展示。
    pub inviter_username: String,
    /// 16 字节邀请 ID，与配对 URL 中的标识符一致。
    pub invite_id: [u8; 16],
    /// 16 字节配对 nonce，用于绑定本次握手与某个具体邀请。
    pub pairing_nonce: [u8; 16],
}

impl PreKeyBundle {
    /// 验证整个 bundle 的签名。
    ///
    /// 当前实现委托给 [`SignedPreKey::verify`]：SPK 的 Ed25519 + ML-DSA-65 双签
    /// 必须都能被 `self.ik` 中的对应签名公钥验过。
    ///
    /// # Errors
    /// SPK 双签中任一签名失败 → [`crate::error::Error::SignatureFailed`]
    pub fn verify(&self) -> Result<()> {
        self.spk.verify(&self.ik)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::keypair::SecretIdentity;
    use crate::prekey::one_time::SecretOneTimePreKey;
    use crate::prekey::signed::SecretSignedPreKey;

    fn make_bundle(
        ik: PublicIdentity,
        spk: SignedPreKey,
        opk: Option<OneTimePreKey>,
    ) -> PreKeyBundle {
        PreKeyBundle {
            ik,
            spk,
            opk,
            inviter_username: "alice".to_string(),
            invite_id: [0x11; 16],
            pairing_nonce: [0x22; 16],
        }
    }

    #[test]
    fn bundle_with_valid_ik_and_spk_verifies() {
        let mut rng = rand::thread_rng();
        let ik_secret = SecretIdentity::generate(&mut rng);
        let ik_pub = ik_secret.public();
        let spk = SecretSignedPreKey::generate(&mut rng, &ik_secret, 1);
        let opk = SecretOneTimePreKey::generate(&mut rng, 7);

        let bundle = make_bundle(ik_pub, spk.public.clone(), Some(opk.public.clone()));
        bundle.verify().expect("valid bundle must verify");
    }

    #[test]
    fn bundle_without_opk_still_verifies() {
        let mut rng = rand::thread_rng();
        let ik_secret = SecretIdentity::generate(&mut rng);
        let ik_pub = ik_secret.public();
        let spk = SecretSignedPreKey::generate(&mut rng, &ik_secret, 2);

        let bundle = make_bundle(ik_pub, spk.public.clone(), None);
        bundle.verify().expect("OPK-less bundle must still verify");
    }

    #[test]
    fn bundle_with_wrong_ik_fails_verify() {
        let mut rng = rand::thread_rng();
        // SPK signed by ik_a, but bundle advertises ik_b's public identity.
        let ik_a = SecretIdentity::generate(&mut rng);
        let ik_b_pub = SecretIdentity::generate(&mut rng).public();
        let spk = SecretSignedPreKey::generate(&mut rng, &ik_a, 1);

        let bundle = make_bundle(ik_b_pub, spk.public.clone(), None);
        assert!(
            bundle.verify().is_err(),
            "bundle whose IK doesn't match SPK signer must fail verify"
        );
    }
}
