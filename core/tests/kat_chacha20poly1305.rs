//! XChaCha20-Poly1305 KAT verification (draft-irtf-cfrg-xchacha-03 A.3.1).

use chencang_core::primitives::aead;
use serde::Deserialize;

#[derive(Deserialize)]
struct V {
    key: String,
    nonce: String,
    aad: String,
    plaintext: String,
    ciphertext: String,
    tag: String,
}
#[derive(Deserialize)]
struct F {
    vectors: Vec<V>,
}

#[test]
fn xchacha20_poly1305_kat() {
    let raw = std::fs::read_to_string("kat-vectors/chacha20poly1305.json").unwrap();
    let f: F = serde_json::from_str(&raw).unwrap();
    for v in f.vectors {
        let key: [u8; 32] = hex::decode(&v.key).unwrap().try_into().unwrap();
        let nonce: [u8; 24] = hex::decode(&v.nonce).unwrap().try_into().unwrap();
        let aad = hex::decode(&v.aad).unwrap();
        let pt = hex::decode(&v.plaintext).unwrap();
        let ct_expected = hex::decode(&v.ciphertext).unwrap();
        let tag_expected: [u8; 16] = hex::decode(&v.tag).unwrap().try_into().unwrap();

        let ct = aead::seal(&key, &nonce, &aad, &pt).unwrap();
        let tag_observed: [u8; 16] = ct[ct.len() - 16..].try_into().unwrap();
        let ct_body = &ct[..ct.len() - 16];
        assert_eq!(ct_body, ct_expected.as_slice(), "ciphertext body");
        assert_eq!(tag_observed, tag_expected, "tag");

        let pt_back = aead::open(&key, &nonce, &aad, &ct).unwrap();
        assert_eq!(pt_back, pt);
    }
}

#[test]
fn tampered_aad_rejected() {
    let key = [7u8; 32];
    let nonce = [3u8; 24];
    let ct = aead::seal(&key, &nonce, b"original aad", b"plaintext").unwrap();
    assert!(aead::open(&key, &nonce, b"tampered aad", &ct).is_err());
}

#[test]
fn tampered_ciphertext_rejected() {
    let key = [7u8; 32];
    let nonce = [3u8; 24];
    let mut ct = aead::seal(&key, &nonce, b"aad", b"plaintext").unwrap();
    ct[0] ^= 0x01;
    assert!(aead::open(&key, &nonce, b"aad", &ct).is_err());
}
