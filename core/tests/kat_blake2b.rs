//! BLAKE2b + HMAC + HKDF KAT.

use chencang_core::primitives::kdf;
use serde::Deserialize;

#[derive(Deserialize)]
struct V {
    input: String,
    key: String,
    output_len: usize,
    expected: String,
}
#[derive(Deserialize)]
struct F {
    vectors: Vec<V>,
}

#[test]
fn blake2b_kat() {
    let raw = std::fs::read_to_string("kat-vectors/blake2b.json").unwrap();
    let f: F = serde_json::from_str(&raw).unwrap();
    for v in f.vectors {
        let input = hex::decode(&v.input).unwrap();
        let key = hex::decode(&v.key).unwrap();
        let expected = hex::decode(&v.expected).unwrap();
        let result = kdf::blake2b(&input, &key, v.output_len);
        assert_eq!(result, expected);
    }
}

#[test]
fn hkdf_blake2b_basic() {
    let salt = [0u8; 32];
    let ikm = b"input keying material";
    let info = b"chencang-test";
    let out: [u8; 32] = kdf::hkdf_blake2b(&salt, ikm, info);
    let out2: [u8; 32] = kdf::hkdf_blake2b(&salt, ikm, info);
    assert_eq!(out, out2);
    let out3: [u8; 32] = kdf::hkdf_blake2b(&salt, ikm, b"different-info");
    assert_ne!(out, out3);
}

#[test]
fn hmac_blake2b_deterministic() {
    let out1 = kdf::hmac_blake2b_32(b"key", b"message");
    let out2 = kdf::hmac_blake2b_32(b"key", b"message");
    assert_eq!(out1, out2);
    let out3 = kdf::hmac_blake2b_32(b"different-key", b"message");
    assert_ne!(out1, out3);
}
