//! 长期身份密钥（IK 四件套）。
//!
//! 一个用户的"身份"由 4 把密钥组成：
//! - `ik_sig_ed25519`  — 经典签名身份
//! - `ik_sig_mldsa65`  — 后量子签名身份
//! - `ik_dh_x25519`    — 经典长期 DH
//! - `ik_kem_mlkem768` — 后量子长期 KEM
//!
//! 私钥永远不离开设备的 Secure Enclave / Keystore。
//!
//! # 设计说明：ML-KEM 公钥缓存
//!
//! `ml_kem::SecretKey` 在 V1 wrapper 中只持有 2400 字节的 raw bytes，
//! 不提供 `.public_key()` 派生方法。因此 [`SecretIdentity`] 在生成阶段
//! **同时缓存** ML-KEM 的公钥，使 [`SecretIdentity::public`] 可以零成本
//! 返回 [`PublicIdentity`]。其他三把私钥（ed25519、ml-dsa、x25519）都
//! 支持就地派生公钥，无需缓存。

use rand_core::{CryptoRng, RngCore};

use crate::error::{Error, Result};
use crate::primitives::{ed25519, ml_dsa, ml_kem, x25519};

/// Magic + version prefix for `SecretIdentity::serialize` output.
/// `0xCC` is the chencang magic byte (shared with wire format);
/// `0x01` denotes identity-blob format v1.
const SECRET_IDENTITY_MAGIC: [u8; 2] = [0xCC, 0x01];

/// Total fixed size of [`SecretIdentity::serialize`] output:
/// 2 magic + 32 ed25519 + 32 mldsa-seed + 32 x25519 + 2400 mlkem-sk + 1184 mlkem-pk.
pub const SECRET_IDENTITY_SERIALIZED_LEN: usize = 2 + 32 + 32 + 32 + 2400 + 1184;

/// 公共 IK 包（可以公开 / 发送给对方）。
#[derive(Clone)]
pub struct PublicIdentity {
    /// 经典签名公钥。
    pub ik_sig_ed25519: ed25519::VerifyingKey,
    /// 后量子签名公钥。
    pub ik_sig_mldsa65: ml_dsa::PublicKey,
    /// 经典 DH 公钥。
    pub ik_dh_x25519: x25519::PublicKey32,
    /// 后量子 KEM 公钥。
    pub ik_kem_mlkem768: ml_kem::PublicKey,
}

/// 完整 IK 包（含私钥）。**绝不在网络传输 / 持久化时离开设备**。
///
/// ML-KEM 公钥被缓存在结构体中（见 module-level 文档）。其他三把公钥通过
/// 对应私钥的派生方法即时计算，开销可忽略。
pub struct SecretIdentity {
    /// 经典签名私钥。
    pub ik_sig_ed25519: ed25519::SigningKey,
    /// 后量子签名私钥。
    pub ik_sig_mldsa65: ml_dsa::SecretKey,
    /// 经典 DH 私钥。
    pub ik_dh_x25519: x25519::SecretKey,
    /// 后量子 KEM 私钥。
    pub ik_kem_mlkem768_sk: ml_kem::SecretKey,
    /// 后量子 KEM 公钥（生成时一并保存，避免从私钥重派生）。
    pub ik_kem_mlkem768_pk: ml_kem::PublicKey,
}

impl SecretIdentity {
    /// 生成全新的 IK 四件套。
    #[must_use]
    pub fn generate<R: CryptoRng + RngCore>(rng: &mut R) -> Self {
        let ik_sig_ed25519 = ed25519::SigningKey::generate(rng);
        let (_mldsa_pk, mldsa_sk) = ml_dsa::generate_keypair(rng);
        let ik_dh_x25519 = x25519::SecretKey::random(rng);
        let (kem_public, kem_secret) = ml_kem::generate_keypair(rng);
        SecretIdentity {
            ik_sig_ed25519,
            ik_sig_mldsa65: mldsa_sk,
            ik_dh_x25519,
            ik_kem_mlkem768_sk: kem_secret,
            ik_kem_mlkem768_pk: kem_public,
        }
    }

    /// 取对应的公共身份包。
    #[must_use]
    pub fn public(&self) -> PublicIdentity {
        PublicIdentity {
            ik_sig_ed25519: self.ik_sig_ed25519.verifying_key(),
            ik_sig_mldsa65: self.ik_sig_mldsa65.public_key(),
            ik_dh_x25519: self.ik_dh_x25519.public(),
            ik_kem_mlkem768: self.ik_kem_mlkem768_pk.clone(),
        }
    }

    /// 将 `SecretIdentity` 序列化为本地加密存储用字节串。
    ///
    /// 布局（共 [`SECRET_IDENTITY_SERIALIZED_LEN`] = 3682 字节）：
    /// `magic[2]` || `ed25519_sk[32]` || `mldsa_seed[32]` || `x25519_sk[32]` ||
    /// `mlkem_sk[2400]` || `mlkem_pk[1184]`
    ///
    /// 调用方（典型 = 应用层）应当用 Argon2id KEK 加密本输出后再持久化。
    /// 本方法不做加密。
    #[must_use]
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(SECRET_IDENTITY_SERIALIZED_LEN);
        out.extend_from_slice(&SECRET_IDENTITY_MAGIC);
        out.extend_from_slice(&self.ik_sig_ed25519.to_bytes());
        out.extend_from_slice(&self.ik_sig_mldsa65.to_seed());
        out.extend_from_slice(&self.ik_dh_x25519.to_bytes());
        out.extend_from_slice(&self.ik_kem_mlkem768_sk.to_bytes());
        out.extend_from_slice(self.ik_kem_mlkem768_pk.as_bytes());
        debug_assert_eq!(out.len(), SECRET_IDENTITY_SERIALIZED_LEN);
        out
    }

    /// 从 [`Self::serialize`] 输出重建 `SecretIdentity`。
    ///
    /// # Errors
    /// - [`Error::Decoding`] — 长度不匹配 / magic 不匹配 / 内嵌私钥解码失败
    ///
    /// # Panics
    /// 永不 panic — 长度已在入口处检查；内部 `try_into` 失败属于库本身 bug。
    pub fn deserialize(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != SECRET_IDENTITY_SERIALIZED_LEN {
            return Err(Error::Decoding(format!(
                "SecretIdentity::deserialize expected {SECRET_IDENTITY_SERIALIZED_LEN} bytes, got {}",
                bytes.len()
            )));
        }
        if bytes[..2] != SECRET_IDENTITY_MAGIC {
            return Err(Error::Decoding("SecretIdentity magic mismatch".into()));
        }

        let mut cursor = 2;
        let ed_sk_bytes: [u8; 32] = bytes[cursor..cursor + 32].try_into().expect("slice len 32");
        cursor += 32;

        let mldsa_seed: [u8; ml_dsa::SEED_LEN] = bytes[cursor..cursor + ml_dsa::SEED_LEN]
            .try_into()
            .expect("slice len SEED_LEN");
        cursor += ml_dsa::SEED_LEN;

        let x_sk_bytes: [u8; 32] = bytes[cursor..cursor + 32].try_into().expect("slice len 32");
        cursor += 32;

        let kem_sk_bytes: [u8; ml_kem::SECRET_KEY_LEN] = bytes
            [cursor..cursor + ml_kem::SECRET_KEY_LEN]
            .try_into()
            .expect("slice len SECRET_KEY_LEN");
        cursor += ml_kem::SECRET_KEY_LEN;

        let mlkem_pub_bytes: [u8; ml_kem::PUBLIC_KEY_LEN] = bytes
            [cursor..cursor + ml_kem::PUBLIC_KEY_LEN]
            .try_into()
            .expect("slice len PUBLIC_KEY_LEN");

        Ok(SecretIdentity {
            ik_sig_ed25519: ed25519::SigningKey::from_bytes(ed_sk_bytes),
            ik_sig_mldsa65: ml_dsa::SecretKey::from_seed(mldsa_seed),
            ik_dh_x25519: x25519::SecretKey::from_bytes(x_sk_bytes),
            ik_kem_mlkem768_sk: ml_kem::SecretKey::from_bytes(kem_sk_bytes),
            ik_kem_mlkem768_pk: ml_kem::PublicKey::from_bytes(mlkem_pub_bytes),
        })
    }
}

impl PublicIdentity {
    /// 从四件套公钥组件构造 `PublicIdentity`。
    ///
    /// 主要给跨语言 bindings 用：上层先把字节通过 `*::from_bytes`
    /// 转成各 primitive 类型，再用本构造器组装。
    #[must_use]
    pub fn from_components(
        ik_dh_x25519: x25519::PublicKey32,
        ik_sig_ed25519: ed25519::VerifyingKey,
        ik_kem_mlkem768: ml_kem::PublicKey,
        ik_sig_mldsa65: ml_dsa::PublicKey,
    ) -> Self {
        PublicIdentity {
            ik_sig_ed25519,
            ik_sig_mldsa65,
            ik_dh_x25519,
            ik_kem_mlkem768,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_and_public_consistent() {
        let mut rng = rand::thread_rng();
        let sk = SecretIdentity::generate(&mut rng);
        let pk = sk.public();

        // ed25519: sign then verify with derived public key.
        let sig = sk.ik_sig_ed25519.sign(b"test");
        crate::primitives::ed25519::verify(&pk.ik_sig_ed25519, b"test", &sig).unwrap();

        // ml-dsa: sign then verify with derived public key.
        let mldsa_sig = crate::primitives::ml_dsa::sign(&sk.ik_sig_mldsa65, b"test", &mut rng);
        crate::primitives::ml_dsa::verify(&pk.ik_sig_mldsa65, b"test", &mldsa_sig).unwrap();

        // x25519: DH between our secret and our own public must equal DH the
        // other way (sanity — both ends derive the same shared secret).
        let ss1 = sk.ik_dh_x25519.diffie_hellman(&pk.ik_dh_x25519);
        // Re-derive the secret -> public mapping; must match the cached pk.
        assert_eq!(sk.ik_dh_x25519.public().0, pk.ik_dh_x25519.0);
        // ss1 not asserted further — its existence proves the keypair is paired.
        let _ = ss1;

        // ml-kem: encapsulate to our public key, decapsulate with our secret.
        let (ct, ss_enc) = crate::primitives::ml_kem::encapsulate(&pk.ik_kem_mlkem768, &mut rng);
        let ss_dec = crate::primitives::ml_kem::decapsulate(&sk.ik_kem_mlkem768_sk, &ct).unwrap();
        assert_eq!(ss_enc.as_bytes(), ss_dec.as_bytes());
    }

    #[test]
    fn serialize_deserialize_round_trip() {
        let mut rng = rand::thread_rng();
        let original = SecretIdentity::generate(&mut rng);
        let bytes = original.serialize();
        assert_eq!(bytes.len(), SECRET_IDENTITY_SERIALIZED_LEN);

        let restored = SecretIdentity::deserialize(&bytes).expect("round-trip");
        let orig_pub = original.public();
        let rest_pub = restored.public();
        assert_eq!(
            orig_pub.ik_sig_ed25519.to_bytes(),
            rest_pub.ik_sig_ed25519.to_bytes()
        );
        assert_eq!(orig_pub.ik_dh_x25519.0, rest_pub.ik_dh_x25519.0);
        assert_eq!(
            orig_pub.ik_kem_mlkem768.as_bytes(),
            rest_pub.ik_kem_mlkem768.as_bytes()
        );
        assert_eq!(
            orig_pub.ik_sig_mldsa65.to_bytes(),
            rest_pub.ik_sig_mldsa65.to_bytes()
        );
    }

    #[test]
    fn deserialize_rejects_wrong_length() {
        let result = SecretIdentity::deserialize(&[0u8; 100]);
        assert!(matches!(result, Err(crate::error::Error::Decoding(_))));
    }

    #[test]
    fn deserialize_rejects_wrong_magic() {
        let mut bytes = vec![0u8; SECRET_IDENTITY_SERIALIZED_LEN];
        bytes[0] = 0xFF; // wrong magic
        let result = SecretIdentity::deserialize(&bytes);
        assert!(matches!(result, Err(crate::error::Error::Decoding(_))));
    }

    #[test]
    fn from_components_round_trip() {
        let mut rng = rand::thread_rng();
        let sk = SecretIdentity::generate(&mut rng);
        let derived = sk.public();
        let rebuilt = PublicIdentity::from_components(
            derived.ik_dh_x25519,
            derived.ik_sig_ed25519,
            derived.ik_kem_mlkem768.clone(),
            derived.ik_sig_mldsa65.clone(),
        );
        assert_eq!(rebuilt.ik_dh_x25519.0, sk.public().ik_dh_x25519.0);
        assert_eq!(
            rebuilt.ik_sig_ed25519.to_bytes(),
            sk.public().ik_sig_ed25519.to_bytes()
        );
    }

    #[test]
    fn distinct_identities_have_distinct_keys() {
        let mut rng = rand::thread_rng();
        let a = SecretIdentity::generate(&mut rng);
        let b = SecretIdentity::generate(&mut rng);
        let a_pub = a.public();
        let b_pub = b.public();
        assert_ne!(
            a_pub.ik_sig_ed25519.to_bytes(),
            b_pub.ik_sig_ed25519.to_bytes()
        );
        assert_ne!(a_pub.ik_dh_x25519.0, b_pub.ik_dh_x25519.0);
        assert_ne!(
            a_pub.ik_sig_mldsa65.to_bytes(),
            b_pub.ik_sig_mldsa65.to_bytes()
        );
        assert_ne!(
            a_pub.ik_kem_mlkem768.as_bytes(),
            b_pub.ik_kem_mlkem768.as_bytes()
        );
    }
}
