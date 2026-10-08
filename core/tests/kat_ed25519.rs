//! Ed25519 KAT verification (RFC 8032 §7.1).

use chencang_core::primitives::ed25519;
use serde::Deserialize;

#[derive(Deserialize)]
struct V {
    secret: String,
    public: String,
    message: String,
    signature: String,
}

#[derive(Deserialize)]
struct File {
    vectors: Vec<V>,
}

#[test]
fn rfc8032_kat() {
    let raw = std::fs::read_to_string("kat-vectors/ed25519.json").unwrap();
    let f: File = serde_json::from_str(&raw).unwrap();
    for v in f.vectors {
        let sk_bytes: [u8; 32] = hex::decode(&v.secret).unwrap().try_into().unwrap();
        let pk_expected: [u8; 32] = hex::decode(&v.public).unwrap().try_into().unwrap();
        let msg = hex::decode(&v.message).unwrap();
        let sig_expected: [u8; 64] = hex::decode(&v.signature).unwrap().try_into().unwrap();

        let signing = ed25519::SigningKey::from_bytes(sk_bytes);
        let pk = signing.verifying_key();
        assert_eq!(pk.to_bytes(), pk_expected, "public key derivation");

        let sig = signing.sign(&msg);
        assert_eq!(sig, sig_expected, "signature");

        ed25519::verify(&pk, &msg, &sig).expect("verify");
    }
}

#[test]
fn tampered_signature_rejected() {
    let mut rng = rand::thread_rng();
    let signing = ed25519::SigningKey::generate(&mut rng);
    let msg = b"hello chencang";
    let mut sig = signing.sign(msg);
    sig[0] ^= 0x01;
    let result = ed25519::verify(&signing.verifying_key(), msg, &sig);
    assert!(result.is_err());
}
