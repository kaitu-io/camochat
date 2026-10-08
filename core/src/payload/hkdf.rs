//! HKDF-Expand-SHA256 derivation of `(blob_id, blob_key, blob_nonce)`
//! from a 32-byte CSPRNG `blob_secret`.
//!
//! See `docs/protocol/README.md` §8.2 ("密钥材料与 locator") for the
//! derivation and info labels.
//!
//! ## Why Expand-only (no Extract)
//! RFC 5869 §3.3 explicitly permits skipping the Extract phase when the
//! input keying material is already a uniform pseudo-random string of the
//! correct length. The `blob_secret` is exactly that: 32 bytes from a
//! cryptographic RNG, used once per blob. Extract would only add a
//! constant overhead without changing the security argument.
//!
//! ## Label convention
//! `cc/<subsystem>/<version>/<purpose>`. ASCII bytes, no NUL terminator.

use ::hkdf::Hkdf;
use sha2::Sha256;

/// HKDF info label for the 16-byte blob identifier (S3 path segment).
pub const LABEL_BLOB_ID: &[u8] = b"cc/blob/v1/id";
/// HKDF info label for the 32-byte `XChaCha20`-Poly1305 key.
pub const LABEL_BLOB_KEY: &[u8] = b"cc/blob/v1/key";
/// HKDF info label for the 24-byte `XChaCha20` nonce.
pub const LABEL_BLOB_NONCE: &[u8] = b"cc/blob/v1/nonce";

/// Output length of `blob_id` (matches `LABEL_BLOB_ID`).
pub const BLOB_ID_LEN: usize = 16;
/// Output length of `blob_key` (matches `LABEL_BLOB_KEY`).
pub const BLOB_KEY_LEN: usize = 32;
/// Output length of `blob_nonce` (matches `LABEL_BLOB_NONCE`).
pub const BLOB_NONCE_LEN: usize = 24;

/// The three deterministic outputs of [`derive_blob_material`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobMaterial {
    /// 16-byte S3 path identifier (hex-encoded by callers).
    pub blob_id: [u8; BLOB_ID_LEN],
    /// 32-byte `XChaCha20`-Poly1305 key for the `.cca` blob.
    pub blob_key: [u8; BLOB_KEY_LEN],
    /// 24-byte `XChaCha20` nonce for the `.cca` blob.
    pub blob_nonce: [u8; BLOB_NONCE_LEN],
}

/// HKDF-Expand-SHA256 with a fixed-size output buffer.
///
/// `secret` is treated as the PRK (RFC 5869 §3.3). `info` is the ASCII
/// label string without NUL terminator. Output length is determined by
/// the const generic `N`; the `hkdf` crate caps this at `255 * HashLen`
/// (255 × 32 = 8 160 bytes for SHA-256), far above any chencang use case.
///
/// # Panics
/// Panics only if `N > 255 * 32`. Compile-time checked by callers via
/// the const-generic length.
fn hkdf_expand_sha256<const N: usize>(secret: &[u8; 32], info: &[u8]) -> [u8; N] {
    const SHA256_OUT: usize = 32;
    assert!(
        N <= 255 * SHA256_OUT,
        "HKDF-Expand-SHA256 output length cannot exceed 255*32 = 8160 bytes"
    );
    let hk = Hkdf::<Sha256>::from_prk(secret).expect("PRK length == HashLen");
    let mut out = [0u8; N];
    hk.expand(info, &mut out)
        .expect("HKDF expand under 255*HashLen bound");
    out
}

/// Derive `(blob_id, blob_key, blob_nonce)` from a 32-byte `blob_secret`.
///
/// All three outputs are independent (different `info` labels) and
/// deterministic — calling this twice with the same secret yields the
/// same triple, which is the point: both sender and receiver derive the
/// same S3 path and AEAD key/nonce locally.
#[must_use]
pub fn derive_blob_material(blob_secret: &[u8; 32]) -> BlobMaterial {
    BlobMaterial {
        blob_id: hkdf_expand_sha256::<BLOB_ID_LEN>(blob_secret, LABEL_BLOB_ID),
        blob_key: hkdf_expand_sha256::<BLOB_KEY_LEN>(blob_secret, LABEL_BLOB_KEY),
        blob_nonce: hkdf_expand_sha256::<BLOB_NONCE_LEN>(blob_secret, LABEL_BLOB_NONCE),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn determinism_same_secret_same_output() {
        let secret = [0x42u8; 32];
        let a = derive_blob_material(&secret);
        let b = derive_blob_material(&secret);
        assert_eq!(a, b);
    }

    #[test]
    fn different_secrets_different_outputs() {
        let a = derive_blob_material(&[0u8; 32]);
        let b = derive_blob_material(&[1u8; 32]);
        assert_ne!(a.blob_id, b.blob_id);
        assert_ne!(a.blob_key, b.blob_key);
        assert_ne!(a.blob_nonce, b.blob_nonce);
    }

    #[test]
    fn distinct_labels_distinct_streams() {
        // The three outputs must be uncorrelated even prefix-wise.
        // (They are different lengths but their first 16 bytes can be
        // compared safely.)
        let secret = [0xAAu8; 32];
        let m = derive_blob_material(&secret);
        assert_ne!(&m.blob_key[..16], &m.blob_id[..16]);
        assert_ne!(&m.blob_nonce[..16], &m.blob_id[..16]);
        assert_ne!(&m.blob_key[..16], &m.blob_nonce[..16]);
    }

    /// RFC 5869 conformance regression: spot-check that an alternate-label
    /// derivation gives different bytes. (This guards against accidental
    /// label-truncation or NUL-termination bugs.)
    #[test]
    fn label_typo_changes_output() {
        let secret = [0x33u8; 32];
        let canonical: [u8; 16] = hkdf_expand_sha256(&secret, LABEL_BLOB_ID);
        let typo: [u8; 16] = hkdf_expand_sha256(&secret, b"cc/blob/v1/Id"); // capital I
        assert_ne!(canonical, typo);
    }

    /// Vector 1: `blob_secret` = all zeros.
    /// Pinned regression vector; derivation per `docs/protocol/README.md` §8.2.
    #[test]
    fn spec_vector_1_all_zero() {
        let m = derive_blob_material(&[0u8; 32]);
        assert_eq!(hex::encode(m.blob_id), "31766f35b0acb82ed8cb94d35eae977e");
        assert_eq!(
            hex::encode(m.blob_key),
            "4b00904f34f16918709cb862aa8225b66514ea91a8d9ca114e14a10ba320f4c9"
        );
        assert_eq!(
            hex::encode(m.blob_nonce),
            "22e9328a3fda5a567c35cb2e0bc19616ce2f7efab72af8e2"
        );
    }

    /// Vector 2: fixed non-zero `blob_secret = 7A3F91…4D3B` from the spec.
    #[test]
    fn spec_vector_2_fixed_secret() {
        let secret: [u8; 32] = [
            0x7A, 0x3F, 0x91, 0xC4, 0xE5, 0xB2, 0x8D, 0x06, 0x4A, 0xD1, 0xF3, 0x59, 0x2E, 0xB7,
            0x80, 0xA1, 0xC6, 0x42, 0x18, 0x9F, 0x05, 0x33, 0xED, 0x71, 0xBC, 0x68, 0x0E, 0x97,
            0x52, 0xAA, 0x4D, 0x3B,
        ];
        let m = derive_blob_material(&secret);
        assert_eq!(hex::encode(m.blob_id), "1997cef897ee95def61beafff481657d");
        assert_eq!(
            hex::encode(m.blob_key),
            "816301fb090d8a1c2f6f4f8638a5e68f6e7bd1ee5d893702a3447ddf5e5c132e"
        );
        assert_eq!(
            hex::encode(m.blob_nonce),
            "0470ba117ff6402d4ccc454b37a57d98d4b3bbbb9dee7db9"
        );
    }
}
