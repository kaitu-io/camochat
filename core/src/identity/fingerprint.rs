//! 16 字节身份指纹 = `BLAKE2b`(`ik_sig_ed25519` || `ik_sig_mldsa65` || `ik_dh_x25519` || `ik_kem_mlkem768`)。
//!
//! 关键：必须包含**全部 4 件套公钥**，否则攻击者可替换 PQ 部分公钥而 fingerprint 不变。

use crate::identity::keypair::PublicIdentity;
use crate::primitives::kdf;

/// 16-byte 身份指纹。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Fingerprint(pub [u8; 16]);

impl Fingerprint {
    /// 从 4 件套公钥计算 fingerprint。
    #[must_use]
    pub fn of(public: &PublicIdentity) -> Self {
        let mut buf = Vec::with_capacity(32 + 1952 + 32 + 1184);
        buf.extend_from_slice(&public.ik_sig_ed25519.to_bytes());
        buf.extend_from_slice(&public.ik_sig_mldsa65.to_bytes());
        buf.extend_from_slice(&public.ik_dh_x25519.0);
        buf.extend_from_slice(public.ik_kem_mlkem768.as_bytes());
        let hash = kdf::blake2b(&buf, &[], 16);
        let mut out = [0u8; 16];
        out.copy_from_slice(&hash);
        Fingerprint(out)
    }

    /// 以分组 hex 显示（用户面 UI 用，如 `ab12 cd34 ef56 78ab cd12 ef34 5678 9abc`）。
    #[must_use]
    pub fn to_hex_groups(&self) -> String {
        let hex = hex::encode(self.0);
        format!(
            "{} {} {} {} {} {} {} {}",
            &hex[0..4],
            &hex[4..8],
            &hex[8..12],
            &hex[12..16],
            &hex[16..20],
            &hex[20..24],
            &hex[24..28],
            &hex[28..32]
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::keypair::SecretIdentity;

    #[test]
    fn fingerprint_deterministic() {
        let mut rng = rand::thread_rng();
        let sk = SecretIdentity::generate(&mut rng);
        let pk = sk.public();
        let fp1 = Fingerprint::of(&pk);
        let fp2 = Fingerprint::of(&pk);
        assert_eq!(fp1, fp2);
    }

    #[test]
    fn fingerprint_changes_when_ed25519_changes() {
        let mut rng = rand::thread_rng();
        let sk_a = SecretIdentity::generate(&mut rng);
        let mut pk_a = sk_a.public();
        let fp_orig = Fingerprint::of(&pk_a);
        let sk_b = SecretIdentity::generate(&mut rng);
        pk_a.ik_sig_ed25519 = sk_b.ik_sig_ed25519.verifying_key();
        let fp_changed = Fingerprint::of(&pk_a);
        assert_ne!(fp_orig, fp_changed);
    }

    #[test]
    fn fingerprint_changes_when_mlkem_changes() {
        // The critical anti-PQ-swap test.
        let mut rng = rand::thread_rng();
        let sk_a = SecretIdentity::generate(&mut rng);
        let mut pk_a = sk_a.public();
        let fp_orig = Fingerprint::of(&pk_a);
        // Tamper one byte in ml-kem pubkey
        pk_a.ik_kem_mlkem768.0[0] ^= 0x01;
        let fp_changed = Fingerprint::of(&pk_a);
        assert_ne!(
            fp_orig, fp_changed,
            "fingerprint MUST cover ml-kem pubkey, else attacker can swap PQ portion"
        );
    }

    #[test]
    fn fingerprint_changes_when_mldsa_changes() {
        let mut rng = rand::thread_rng();
        let sk_a = SecretIdentity::generate(&mut rng);
        let sk_b = SecretIdentity::generate(&mut rng);
        let pk_a = sk_a.public();
        let mut pk_a_tampered = pk_a.clone();
        pk_a_tampered.ik_sig_mldsa65 = sk_b.public().ik_sig_mldsa65;
        assert_ne!(
            Fingerprint::of(&pk_a),
            Fingerprint::of(&pk_a_tampered),
            "fingerprint MUST cover ml-dsa pubkey"
        );
    }

    #[test]
    fn hex_groups_format() {
        let fp = Fingerprint([
            0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            0x77, 0x88,
        ]);
        let s = fp.to_hex_groups();
        assert_eq!(s, "1234 5678 9abc def0 1122 3344 5566 7788");
    }
}
