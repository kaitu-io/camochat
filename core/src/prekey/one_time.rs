//! One-Time Prekey (OPK)：一次性预密钥。
//!
//! 每个 OPK 用于一次 PQXDH 握手后立即销毁。客户端维护一个池（默认 100 把），
//! 用完即上传新一批公钥到服务端。私钥永不离开本机。

use rand_core::{CryptoRng, RngCore};

use crate::primitives::{ml_kem, x25519};

/// 公共 OPK（上传给服务端）。
#[derive(Clone)]
pub struct OneTimePreKey {
    /// 池内唯一 ID（用于配对完成时告知对方"用了哪把 OPK"）。
    pub id: u32,
    /// 经典 X25519 一次性公钥。
    pub opk_x25519: x25519::PublicKey32,
    /// 后量子 ML-KEM-768 一次性公钥。
    pub opk_mlkem768: ml_kem::PublicKey,
}

/// 完整 OPK（含私钥；只存本地，用完销毁）。
pub struct SecretOneTimePreKey {
    /// 池内唯一 ID。
    pub id: u32,
    /// 经典 X25519 一次性私钥。
    pub opk_x25519: x25519::SecretKey,
    /// 后量子 ML-KEM-768 一次性私钥。
    pub opk_mlkem768: ml_kem::SecretKey,
    /// 同步保存的公钥（避免重复计算）。
    pub public: OneTimePreKey,
}

impl SecretOneTimePreKey {
    /// 生成一把新 OPK。
    #[must_use]
    pub fn generate<R: CryptoRng + RngCore>(rng: &mut R, id: u32) -> Self {
        let x_secret = x25519::SecretKey::random(rng);
        let x_public = x_secret.public();
        let (kem_public, kem_secret) = ml_kem::generate_keypair(rng);
        let public = OneTimePreKey {
            id,
            opk_x25519: x_public,
            opk_mlkem768: kem_public.clone(),
        };
        SecretOneTimePreKey {
            id,
            opk_x25519: x_secret,
            opk_mlkem768: kem_secret,
            public,
        }
    }

    /// 批量生成 N 把 OPK，id 从 `first_id` 起。
    #[must_use]
    pub fn generate_batch<R: CryptoRng + RngCore>(
        rng: &mut R,
        first_id: u32,
        count: u32,
    ) -> Vec<Self> {
        (0..count)
            .map(|i| Self::generate(rng, first_id + i))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_batch_unique_ids() {
        let mut rng = rand::thread_rng();
        let batch = SecretOneTimePreKey::generate_batch(&mut rng, 100, 10);
        assert_eq!(batch.len(), 10);
        for (i, opk) in batch.iter().enumerate() {
            let expected = 100 + u32::try_from(i).expect("test index fits in u32");
            assert_eq!(opk.id, expected);
        }
    }

    #[test]
    fn each_opk_has_distinct_public_keys() {
        let mut rng = rand::thread_rng();
        let batch = SecretOneTimePreKey::generate_batch(&mut rng, 0, 3);
        assert_ne!(batch[0].public.opk_x25519, batch[1].public.opk_x25519);
        assert_ne!(batch[1].public.opk_x25519, batch[2].public.opk_x25519);
    }

    #[test]
    fn public_struct_id_matches_secret_id() {
        let mut rng = rand::thread_rng();
        let opk = SecretOneTimePreKey::generate(&mut rng, 42);
        assert_eq!(opk.id, 42);
        assert_eq!(opk.public.id, 42);
    }
}
