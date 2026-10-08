//! Wire-format compatibility golden vector for the `.cca` blob format.
//!
//! The `golden_matches_committed_bytes` test asserts that the byte layout
//! of an encrypted blob produced from fixed input matches a frozen byte
//! sequence — this guards against accidental changes to the format
//! (header field order, AAD construction, ciphertext encoding) that would
//! break interop with shipped clients.
//!
//! To regenerate (e.g. after intentional spec change):
//! ```bash
//! cargo test -p chencang-core --test blob_wire_compat -- --ignored print_golden --nocapture
//! ```
//! then paste the printed bytes into `GOLDEN_OPUS`.

use chencang_core::blob::{open_cca, seal_cca, CCA_HEADER_LEN, CCA_MAGIC, CCA_VERSION};
use chencang_core::payload::{derive_blob_material, MEDIA_KIND_VOICE};

const KEY: [u8; 32] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
    0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
];

const NONCE: [u8; 24] = [
    0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f,
    0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
];

const PLAINTEXT: &[u8] = b"chencang voice opus frame v1";

/// Golden bytes for `seal_cca(PLAINTEXT, &KEY, &NONCE, MEDIA_KIND_VOICE)`.
///
/// Regenerate with the ignored `print_golden` test below.
const GOLDEN_OPUS: &[u8] = &[
    0x43, 0x43, 0x41, 0x31, 0x01, 0x01, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29,
    0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x00, 0x00,
    0x00, 0x2c, 0x7e, 0x31, 0x28, 0xa5, 0x18, 0x56, 0x7a, 0xec, 0x18, 0xc8, 0x3f, 0xd4, 0x2b, 0x22,
    0x21, 0xbe, 0x4d, 0x20, 0xa2, 0x1c, 0xb9, 0xe1, 0x67, 0xd8, 0x84, 0x41, 0x2a, 0x18, 0x1a, 0x1c,
    0xd4, 0xb5, 0xc8, 0x24, 0xa9, 0xfa, 0x23, 0xfb, 0x0f, 0x55, 0x66, 0x93, 0x75, 0xe3,
];

#[test]
#[ignore = "regenerator — prints the golden bytes to paste into GOLDEN_OPUS"]
fn print_golden() {
    let blob = seal_cca(PLAINTEXT, &KEY, &NONCE, MEDIA_KIND_VOICE).unwrap();
    println!("// .cca GOLDEN_OPUS — {} bytes", blob.len());
    println!("const GOLDEN_OPUS: &[u8] = &[");
    for chunk in blob.chunks(16) {
        let line = chunk
            .iter()
            .map(|b| format!("0x{b:02x}"))
            .collect::<Vec<_>>()
            .join(", ");
        println!("    {line},");
    }
    println!("];");
}

#[test]
fn golden_matches_committed_bytes() {
    let blob = seal_cca(PLAINTEXT, &KEY, &NONCE, MEDIA_KIND_VOICE).unwrap();
    assert_eq!(
        blob, GOLDEN_OPUS,
        "wire format drift — regenerate GOLDEN_OPUS via `cargo test --ignored print_golden`"
    );
    // sanity: header constants still hold
    assert_eq!(&blob[..4], CCA_MAGIC);
    assert_eq!(blob[4], CCA_VERSION);
    assert_eq!(blob[5], MEDIA_KIND_VOICE);
    assert_eq!(blob.len(), CCA_HEADER_LEN + PLAINTEXT.len() + 16);
    // and round-trips
    assert_eq!(open_cca(&blob, &KEY).unwrap().1, PLAINTEXT);
}

// Two devices that both know the same `blob_secret` derive the same
// S3 path identifier and AEAD material.
#[test]
fn blob_material_matches_between_peers() {
    let secret = [0x11u8; 32];
    let a = derive_blob_material(&secret);
    let b = derive_blob_material(&secret);
    assert_eq!(a, b);

    // Tampering one byte of the secret produces completely different
    // (no shared bytes by birthday-bound expectation) derived material.
    let mut secret2 = secret;
    secret2[0] ^= 0x01;
    let c = derive_blob_material(&secret2);
    assert_ne!(a.blob_id, c.blob_id);
    assert_ne!(a.blob_key, c.blob_key);
    assert_ne!(a.blob_nonce, c.blob_nonce);
}
