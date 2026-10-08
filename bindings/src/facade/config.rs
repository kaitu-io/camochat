//! Signed-config verification. The config envelope is signed offline with an
//! Ed25519 key (see `xtask sign-config`); clients verify with the public key
//! baked in below. The signed message is `CONFIG_DOMAIN || payload`.

use chencang_core::primitives::ed25519::{verify, VerifyingKey};

/// Domain-separation prefix prepended to the payload before signing.
pub const CONFIG_DOMAIN: &[u8] = b"chencang-config-v1\n";

/// Production config-signing public key. Filled from `xtask gen-config-key`.
pub const CONFIG_PUBLIC_KEY: [u8; 32] = [
    0x8c, 0x07, 0x1d, 0xa9, 0x2b, 0x2b, 0x8f, 0xb9, 0xf4, 0xc3, 0x73, 0x38, 0x4a, 0x5c, 0xc4, 0xbe,
    0x53, 0x10, 0x35, 0xea, 0x20, 0xdf, 0x3a, 0x64, 0xce, 0x7a, 0x08, 0xa9, 0xc0, 0xae, 0x1c, 0xec,
];

pub(crate) fn verify_with_key(pk: &[u8; 32], payload: &[u8], sig: &[u8]) -> bool {
    let Ok(sig_arr) = <[u8; 64]>::try_from(sig) else {
        return false;
    };
    let mut msg = Vec::with_capacity(CONFIG_DOMAIN.len() + payload.len());
    msg.extend_from_slice(CONFIG_DOMAIN);
    msg.extend_from_slice(payload);
    verify(&VerifyingKey(*pk), &msg, &sig_arr).is_ok()
}

#[must_use]
pub fn verify_signed_config(payload: Vec<u8>, sig: Vec<u8>) -> bool {
    verify_with_key(&CONFIG_PUBLIC_KEY, &payload, &sig)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chencang_core::primitives::ed25519::SigningKey;

    fn key() -> SigningKey {
        SigningKey::from_bytes([7u8; 32])
    }

    fn signed(payload: &[u8]) -> [u8; 64] {
        let mut msg = CONFIG_DOMAIN.to_vec();
        msg.extend_from_slice(payload);
        key().sign(&msg)
    }

    #[test]
    fn accepts_valid_signature() {
        let p = b"{\"schema\":1}";
        assert!(verify_with_key(&key().verifying_key().0, p, &signed(p)));
    }

    #[test]
    fn rejects_without_domain_prefix() {
        let p = b"{\"schema\":1}";
        let sig = key().sign(p);
        assert!(!verify_with_key(&key().verifying_key().0, p, &sig));
    }

    #[test]
    fn rejects_tampered_payload() {
        let sig = signed(b"{\"schema\":1}");
        assert!(!verify_with_key(
            &key().verifying_key().0,
            b"{\"schema\":2}",
            &sig
        ));
    }

    #[test]
    fn rejects_wrong_key() {
        let p = b"{\"schema\":1}";
        let other = SigningKey::from_bytes([8u8; 32]).verifying_key();
        assert!(!verify_with_key(&other.0, p, &signed(p)));
    }

    #[test]
    fn rejects_short_sig() {
        let p = b"{\"schema\":1}";
        let sig = signed(p);
        assert!(!verify_with_key(&key().verifying_key().0, p, &sig[..63]));
    }

    #[test]
    fn production_key_is_set() {
        assert_ne!(CONFIG_PUBLIC_KEY, [0u8; 32]);
    }

    #[test]
    fn rejects_identity_point_forgery() {
        // R = identity (0x01, 0, ...), s = 0: verifies under small-order keys in non-strict mode.
        let mut sig = [0u8; 64];
        sig[0] = 1;
        assert!(!verify_signed_config(
            b"{\"schema\":1}".to_vec(),
            sig.to_vec()
        ));
        assert!(!verify_signed_config(
            b"{\"schema\":1}".to_vec(),
            vec![0u8; 64]
        ));
    }
}
