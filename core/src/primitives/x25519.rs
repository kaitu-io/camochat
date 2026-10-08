//! X25519 ECDH wrapper. Wraps `x25519-dalek` with zeroize on secret keys.

use rand_core::{CryptoRng, RngCore};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

/// 32-byte X25519 secret scalar. Zeroized on drop.
#[derive(Clone)]
pub struct SecretKey(StaticSecret);

/// 32-byte X25519 public point.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct PublicKey32(pub [u8; 32]);

/// 32-byte shared secret from ECDH. Zeroized on drop.
pub struct SharedSecret([u8; 32]);

impl SecretKey {
    /// 生成新的随机 X25519 私钥。
    pub fn random<R: CryptoRng + RngCore>(rng: &mut R) -> Self {
        let mut bytes = [0u8; 32];
        rng.fill_bytes(&mut bytes);
        let s = StaticSecret::from(bytes);
        bytes.zeroize();
        SecretKey(s)
    }

    /// 从字节数组导入私钥（仅用于反序列化已存储的密钥）。
    #[must_use]
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        SecretKey(StaticSecret::from(bytes))
    }

    /// 取对应的公钥。
    #[must_use]
    pub fn public(&self) -> PublicKey32 {
        let pk = PublicKey::from(&self.0);
        PublicKey32(*pk.as_bytes())
    }

    /// 与对端公钥做 DH，得到 32 字节共享密钥。
    #[must_use]
    pub fn diffie_hellman(&self, peer: &PublicKey32) -> SharedSecret {
        let peer_pk = PublicKey::from(peer.0);
        let shared = self.0.diffie_hellman(&peer_pk);
        SharedSecret(*shared.as_bytes())
    }

    /// 导出私钥字节（仅用于持久化）。Caller 负责立即 zeroize。
    #[must_use]
    pub fn to_bytes(&self) -> [u8; 32] {
        self.0.to_bytes()
    }
}

impl SharedSecret {
    /// 返回 32 字节共享秘密引用。
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl Drop for SharedSecret {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

/// 直接的 scalar × point 运算 — 仅供 KAT 测试使用。
#[must_use]
pub fn scalar_mult(scalar: &[u8; 32], u: &[u8; 32]) -> [u8; 32] {
    let s = StaticSecret::from(*scalar);
    let p = PublicKey::from(*u);
    *s.diffie_hellman(&p).as_bytes()
}
