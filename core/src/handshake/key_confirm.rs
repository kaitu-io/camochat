//! Key-confirmation MAC for the PQXDH-hybrid handshake (spec §4.8).
//!
//! After both parties derive the same Session Root Key (SRK) and the same
//! 32-byte handshake transcript, each derives a confirmation key
//! `kc = HKDF-BLAKE2b(salt=SRK, ikm="", info="chencang-v1-kc")` and exchanges
//! a keyed-BLAKE2b MAC over `transcript || who`. Verifying the peer's tag
//! proves the peer derived the identical SRK+transcript. The `who` byte
//! domain-separates the two directions so a tag cannot be reflected.

use subtle::ConstantTimeEq;

use crate::primitives::kdf;

/// `who` byte for the INITIATOR's (Bob's) confirmation tag (`confirm_b`).
pub const CONFIRM_WHO_INITIATOR: u8 = 0x42;
/// `who` byte for the RESPONDER's (Alice's) confirmation tag (`confirm_a`).
pub const CONFIRM_WHO_RESPONDER: u8 = 0x41;

const KC_INFO_LABEL: &[u8] = b"chencang-v1-kc";

/// Derive the 32-byte key-confirmation key from the SRK.
#[must_use]
pub fn derive_kc(srk: &[u8; 32]) -> [u8; 32] {
    // Per spec §4.8: SRK goes in the HKDF *salt* position with empty IKM
    // (intentionally different from derive_srk's zero-salt/secret-IKM layout).
    // Output is domain-separated from the SRK by the distinct "chencang-v1-kc" info label.
    kdf::hkdf_blake2b(srk, b"", KC_INFO_LABEL)
}

/// Compute the confirmation tag for one direction:
/// `keyed-BLAKE2b(kc, transcript || who)`.
#[must_use]
pub fn confirm_tag(kc: &[u8; 32], transcript: &[u8; 32], who: u8) -> [u8; 32] {
    let mut msg = Vec::with_capacity(33);
    msg.extend_from_slice(transcript);
    msg.push(who);
    kdf::hmac_blake2b_32(kc, &msg)
}

/// Constant-time verify a confirmation tag. Returns true iff `tag` matches the
/// expected tag for `(kc, transcript, who)`.
#[must_use]
pub fn verify_confirm_tag(kc: &[u8; 32], transcript: &[u8; 32], who: u8, tag: &[u8; 32]) -> bool {
    let expected = confirm_tag(kc, transcript, who);
    expected.ct_eq(tag).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derive_kc_is_deterministic() {
        let srk = [9u8; 32];
        assert_eq!(derive_kc(&srk), derive_kc(&srk));
    }

    #[test]
    fn derive_kc_differs_for_different_srk() {
        let mut srk2 = [9u8; 32];
        srk2[0] ^= 1;
        assert_ne!(derive_kc(&[9u8; 32]), derive_kc(&srk2));
    }

    #[test]
    fn confirm_tag_is_deterministic() {
        let kc = [1u8; 32];
        let t = [2u8; 32];
        assert_eq!(
            confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR),
            confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR)
        );
    }

    #[test]
    fn confirm_tag_is_who_sensitive() {
        let kc = [1u8; 32];
        let t = [2u8; 32];
        assert_ne!(
            confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR),
            confirm_tag(&kc, &t, CONFIRM_WHO_RESPONDER)
        );
    }

    #[test]
    fn confirm_tag_differs_for_different_transcript() {
        let kc = [1u8; 32];
        let mut t2 = [2u8; 32];
        t2[5] ^= 1;
        assert_ne!(
            confirm_tag(&kc, &[2u8; 32], CONFIRM_WHO_INITIATOR),
            confirm_tag(&kc, &t2, CONFIRM_WHO_INITIATOR)
        );
    }

    #[test]
    fn confirm_tag_differs_for_different_kc() {
        let mut kc2 = [1u8; 32];
        kc2[0] ^= 1;
        let t = [2u8; 32];
        assert_ne!(
            confirm_tag(&[1u8; 32], &t, CONFIRM_WHO_INITIATOR),
            confirm_tag(&kc2, &t, CONFIRM_WHO_INITIATOR)
        );
    }

    #[test]
    fn verify_accepts_correct_tag() {
        let kc = [1u8; 32];
        let t = [2u8; 32];
        let tag = confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR);
        assert!(verify_confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR, &tag));
    }

    #[test]
    fn verify_rejects_flipped_byte() {
        let kc = [1u8; 32];
        let t = [2u8; 32];
        let mut tag = confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR);
        tag[10] ^= 1;
        assert!(!verify_confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR, &tag));
    }

    #[test]
    fn verify_rejects_wrong_who() {
        let kc = [1u8; 32];
        let t = [2u8; 32];
        let tag = confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR);
        assert!(!verify_confirm_tag(&kc, &t, CONFIRM_WHO_RESPONDER, &tag));
    }

    #[test]
    fn verify_rejects_wrong_transcript() {
        let kc = [1u8; 32];
        let tag = confirm_tag(&kc, &[2u8; 32], CONFIRM_WHO_INITIATOR);
        let mut t2 = [2u8; 32];
        t2[0] ^= 1;
        assert!(!verify_confirm_tag(&kc, &t2, CONFIRM_WHO_INITIATOR, &tag));
    }

    #[test]
    fn verify_rejects_wrong_kc() {
        let kc = [1u8; 32];
        let t = [2u8; 32];
        let tag = confirm_tag(&kc, &t, CONFIRM_WHO_INITIATOR);
        let mut kc2 = kc;
        kc2[0] ^= 1;
        assert!(!verify_confirm_tag(&kc2, &t, CONFIRM_WHO_INITIATOR, &tag));
    }
}
