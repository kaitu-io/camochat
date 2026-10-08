//! Signed Prekey (SPK) with双签名 (Ed25519 || ML-DSA-65)。
//!
//! SPK 公钥包 = X25519 pub + ML-KEM pub。被签名内容 = 两个公钥 + epoch (u32 BE)。
//! 双签名意味着攻击者必须同时破 Ed25519 和 ML-DSA-65 才能伪造 SPK。

use rand_core::{CryptoRng, RngCore};

use crate::error::Result;
use crate::identity::keypair::{PublicIdentity, SecretIdentity};
use crate::primitives::{ed25519, ml_dsa, ml_kem, x25519};

/// SPK 公钥包（X25519 + ML-KEM），有效期内可被多个对端使用。
#[derive(Clone)]
pub struct SignedPreKey {
    /// 经典 X25519 中期预密钥公钥。
    pub spk_x25519: x25519::PublicKey32,
    /// 后量子 ML-KEM-768 中期预密钥公钥。
    pub spk_mlkem768: ml_kem::PublicKey,
    /// Ed25519 签名（覆盖两个公钥 + epoch）。
    pub sig_ed25519: [u8; 64],
    /// ML-DSA-65 签名（覆盖两个公钥 + epoch）。
    pub sig_mldsa65: Vec<u8>,
    /// 轮换 epoch 序号（用于在多个有效 SPK 之间区分）。
    pub epoch: u32,
    /// 经典套件（suite 0x01）专用 Ed25519 签名，仅覆盖**经典字段**
    /// (`spk_x25519` || epoch BE)。带内配对包只携带 X25519 公钥，无 ML-KEM
    /// 公钥，故无法重建上面 `sig_ed25519` 的混合 message；此字段让经典 SPK
    /// 能脱离 ML-KEM 公钥独立验签（见 `handshake::classical_bundle`）。
    /// **派生量**：不进 [`SecretSignedPreKey::serialize`] 字节流（生成 / 反序列化
    /// 时即时计算），故不影响混合 wire 格式与 golden 向量。
    pub sig_ed25519_classical: [u8; 64],
}

/// 完整 SPK（含私钥）。**绝不离开设备**。
pub struct SecretSignedPreKey {
    /// 经典 X25519 中期预密钥私钥。
    pub spk_x25519: x25519::SecretKey,
    /// 后量子 ML-KEM-768 中期预密钥私钥。
    pub spk_mlkem768: ml_kem::SecretKey,
    /// 对应的公开 SPK 结构。
    pub public: SignedPreKey,
}

/// 构造被签名的字节：`spk_x25519_pub` || `spk_mlkem_pub` || epoch (BE u32)。
fn signed_bytes(spk_x: &x25519::PublicKey32, spk_k: &ml_kem::PublicKey, epoch: u32) -> Vec<u8> {
    let mut buf = Vec::with_capacity(32 + ml_kem::PUBLIC_KEY_LEN + 4);
    buf.extend_from_slice(&spk_x.0);
    buf.extend_from_slice(spk_k.as_bytes());
    buf.extend_from_slice(&epoch.to_be_bytes());
    buf
}

/// 构造经典（suite 0x01）被签名字节：`spk_x25519_pub` || epoch (BE u32)。
/// 不含 ML-KEM 公钥——经典带内包不携带它。
#[must_use]
pub fn classical_signed_bytes(spk_x: &x25519::PublicKey32, epoch: u32) -> Vec<u8> {
    let mut buf = Vec::with_capacity(32 + 4);
    buf.extend_from_slice(&spk_x.0);
    buf.extend_from_slice(&epoch.to_be_bytes());
    buf
}

impl SignedPreKey {
    /// 验签：用对端的 `IK_sig` 公钥（双签都验过才通过）。
    ///
    /// # Errors
    /// 任一签名验签失败 → [`crate::error::Error::SignatureFailed`]
    pub fn verify(&self, ik: &PublicIdentity) -> Result<()> {
        let signed = signed_bytes(&self.spk_x25519, &self.spk_mlkem768, self.epoch);
        ed25519::verify(&ik.ik_sig_ed25519, &signed, &self.sig_ed25519)?;
        ml_dsa::verify(&ik.ik_sig_mldsa65, &signed, &self.sig_mldsa65)?;
        Ok(())
    }
}

/// Magic bytes prepended to every serialized `SecretSignedPreKey` blob.
const SPK_SECRET_MAGIC: [u8; 2] = [0xCC, 0x03];

impl SecretSignedPreKey {
    /// Serialize the full secret SPK (private keys + public keys + signatures)
    /// for local Keystore-backed persistence. **Never transmit.**
    ///
    /// Format:
    /// `magic[2]` || `x25519_secret[32]` || `mlkem_secret[2400]`
    /// || `x25519_pub[32]` || `mlkem_pub[1184]` || `ed25519_sig[64]`
    /// || `mldsa_sig_len[u32 BE]` || `mldsa_sig[mldsa_sig_len]`
    /// || `epoch[u32 BE]` || `ed25519_classical_sig[64]`
    #[must_use]
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&SPK_SECRET_MAGIC);
        out.extend_from_slice(&self.spk_x25519.to_bytes());
        out.extend_from_slice(&self.spk_mlkem768.to_bytes());
        out.extend_from_slice(&self.public.spk_x25519.0);
        out.extend_from_slice(self.public.spk_mlkem768.as_bytes());
        out.extend_from_slice(&self.public.sig_ed25519);
        #[allow(clippy::cast_possible_truncation)]
        out.extend_from_slice(&(self.public.sig_mldsa65.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.public.sig_mldsa65);
        out.extend_from_slice(&self.public.epoch.to_be_bytes());
        out.extend_from_slice(&self.public.sig_ed25519_classical);
        out
    }

    /// Inverse of [`serialize`].
    ///
    /// # Errors
    /// Returns [`crate::error::Error::Decoding`] on bad magic, truncation, or
    /// invalid length fields.
    ///
    /// # Panics
    /// Never panics in practice: `unwrap()` calls on `try_into()` are guarded
    /// by the preceding `take()` which guarantees the slice length matches the
    /// fixed-size array exactly.
    pub fn deserialize(buf: &[u8]) -> Result<Self> {
        use crate::error::Error;

        fn take<'a>(cur: &mut &'a [u8], n: usize) -> Result<&'a [u8]> {
            if cur.len() < n {
                return Err(Error::Decoding(format!(
                    "spk deserialize: need {n} bytes, have {}",
                    cur.len()
                )));
            }
            let (head, tail) = cur.split_at(n);
            *cur = tail;
            Ok(head)
        }

        let mut cur = buf;

        if take(&mut cur, 2)? != SPK_SECRET_MAGIC {
            return Err(Error::Decoding("spk deserialize: bad magic".into()));
        }

        let x_sec: [u8; 32] = take(&mut cur, 32)?.try_into().unwrap();
        let k_sec: [u8; ml_kem::SECRET_KEY_LEN] =
            take(&mut cur, ml_kem::SECRET_KEY_LEN)?.try_into().unwrap();
        let x_pub: [u8; 32] = take(&mut cur, 32)?.try_into().unwrap();
        let k_pub: [u8; ml_kem::PUBLIC_KEY_LEN] =
            take(&mut cur, ml_kem::PUBLIC_KEY_LEN)?.try_into().unwrap();
        let ed_sig: [u8; 64] = take(&mut cur, 64)?.try_into().unwrap();

        let sig_len = u32::from_be_bytes(take(&mut cur, 4)?.try_into().unwrap()) as usize;
        let mldsa_sig = take(&mut cur, sig_len)?.to_vec();

        let epoch = u32::from_be_bytes(take(&mut cur, 4)?.try_into().unwrap());
        let ed_sig_classical: [u8; 64] = take(&mut cur, 64)?.try_into().unwrap();

        Ok(Self {
            spk_x25519: x25519::SecretKey::from_bytes(x_sec),
            spk_mlkem768: ml_kem::SecretKey::from_bytes(k_sec),
            public: SignedPreKey {
                spk_x25519: x25519::PublicKey32(x_pub),
                spk_mlkem768: ml_kem::PublicKey::from_bytes(k_pub),
                sig_ed25519: ed_sig,
                sig_mldsa65: mldsa_sig,
                epoch,
                sig_ed25519_classical: ed_sig_classical,
            },
        })
    }

    /// 生成新 SPK，用持有者的 `IK_sig` 私钥双签。
    #[must_use]
    pub fn generate<R: CryptoRng + RngCore>(
        rng: &mut R,
        ik_secret: &SecretIdentity,
        epoch: u32,
    ) -> Self {
        let x_secret = x25519::SecretKey::random(rng);
        let x_public = x_secret.public();
        let (kem_public, kem_secret) = ml_kem::generate_keypair(rng);

        let to_sign = signed_bytes(&x_public, &kem_public, epoch);
        let ed_sig = ik_secret.ik_sig_ed25519.sign(&to_sign);
        let dsa_sig = ml_dsa::sign(&ik_secret.ik_sig_mldsa65, &to_sign, rng);

        let classical_to_sign = classical_signed_bytes(&x_public, epoch);
        let ed_sig_classical = ik_secret.ik_sig_ed25519.sign(&classical_to_sign);

        SecretSignedPreKey {
            spk_x25519: x_secret,
            spk_mlkem768: kem_secret,
            public: SignedPreKey {
                spk_x25519: x_public,
                spk_mlkem768: kem_public,
                sig_ed25519: ed_sig,
                sig_mldsa65: dsa_sig,
                sig_ed25519_classical: ed_sig_classical,
                epoch,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spk_serialize_roundtrip_preserves_keys() {
        let mut rng = rand::thread_rng();
        let ik = SecretIdentity::generate(&mut rng);
        let spk = SecretSignedPreKey::generate(&mut rng, &ik, 7);
        let bytes = spk.serialize();
        let back = SecretSignedPreKey::deserialize(&bytes).expect("deserialize");
        assert_eq!(back.spk_x25519.to_bytes(), spk.spk_x25519.to_bytes());
        assert_eq!(back.spk_mlkem768.to_bytes(), spk.spk_mlkem768.to_bytes());
        assert_eq!(back.public.spk_x25519.0, spk.public.spk_x25519.0);
        assert_eq!(
            back.public.spk_mlkem768.as_bytes(),
            spk.public.spk_mlkem768.as_bytes()
        );
        assert_eq!(back.public.sig_ed25519, spk.public.sig_ed25519);
        assert_eq!(back.public.sig_mldsa65, spk.public.sig_mldsa65);
        assert_eq!(back.public.epoch, spk.public.epoch);
        back.public
            .verify(&ik.public())
            .expect("verify after roundtrip");
    }

    #[test]
    fn spk_deserialize_rejects_bad_magic() {
        let mut bad = vec![0x00, 0x00];
        bad.extend_from_slice(&[0u8; 64]);
        assert!(SecretSignedPreKey::deserialize(&bad).is_err());
    }

    #[test]
    fn spk_generate_then_verify() {
        let mut rng = rand::thread_rng();
        let ik = SecretIdentity::generate(&mut rng);
        let ik_pub = ik.public();
        let spk = SecretSignedPreKey::generate(&mut rng, &ik, 1);
        spk.public.verify(&ik_pub).unwrap();
    }

    #[test]
    fn spk_verification_fails_with_wrong_ik() {
        let mut rng = rand::thread_rng();
        let ik_a = SecretIdentity::generate(&mut rng);
        let ik_b_pub = SecretIdentity::generate(&mut rng).public();
        let spk = SecretSignedPreKey::generate(&mut rng, &ik_a, 1);
        assert!(spk.public.verify(&ik_b_pub).is_err());
    }

    #[test]
    fn spk_verification_fails_with_tampered_x25519_pubkey() {
        let mut rng = rand::thread_rng();
        let ik = SecretIdentity::generate(&mut rng);
        let ik_pub = ik.public();
        let mut spk = SecretSignedPreKey::generate(&mut rng, &ik, 1);
        spk.public.spk_x25519.0[0] ^= 0x01;
        assert!(spk.public.verify(&ik_pub).is_err());
    }

    #[test]
    fn spk_verification_fails_with_tampered_mlkem_pubkey() {
        let mut rng = rand::thread_rng();
        let ik = SecretIdentity::generate(&mut rng);
        let ik_pub = ik.public();
        let mut spk = SecretSignedPreKey::generate(&mut rng, &ik, 1);
        spk.public.spk_mlkem768.0[0] ^= 0x01;
        assert!(spk.public.verify(&ik_pub).is_err());
    }

    #[test]
    fn spk_verification_fails_with_tampered_epoch() {
        let mut rng = rand::thread_rng();
        let ik = SecretIdentity::generate(&mut rng);
        let ik_pub = ik.public();
        let mut spk = SecretSignedPreKey::generate(&mut rng, &ik, 1);
        spk.public.epoch = 999;
        assert!(spk.public.verify(&ik_pub).is_err());
    }
}
