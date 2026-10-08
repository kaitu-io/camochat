//! X25519 KAT (Known Answer Tests) + Diffie-Hellman round-trip.

use chencang_core::primitives::x25519;
use serde::Deserialize;

#[derive(Deserialize)]
struct Vector {
    scalar: String,
    u: String,
    expected: String,
}

#[derive(Deserialize)]
struct File {
    vectors: Vec<Vector>,
}

#[test]
fn rfc7748_kat() {
    let raw = std::fs::read_to_string("kat-vectors/x25519.json").unwrap();
    let file: File = serde_json::from_str(&raw).unwrap();
    for v in file.vectors {
        let scalar: [u8; 32] = hex::decode(&v.scalar).unwrap().try_into().unwrap();
        let u: [u8; 32] = hex::decode(&v.u).unwrap().try_into().unwrap();
        let expected: [u8; 32] = hex::decode(&v.expected).unwrap().try_into().unwrap();
        let result = x25519::scalar_mult(&scalar, &u);
        assert_eq!(result, expected, "RFC 7748 KAT mismatch");
    }
}

#[test]
fn diffie_hellman_round_trip() {
    let mut rng = rand::thread_rng();
    let alice = x25519::SecretKey::random(&mut rng);
    let bob = x25519::SecretKey::random(&mut rng);
    let shared_a = alice.diffie_hellman(&bob.public());
    let shared_b = bob.diffie_hellman(&alice.public());
    assert_eq!(shared_a.as_bytes(), shared_b.as_bytes());
}
