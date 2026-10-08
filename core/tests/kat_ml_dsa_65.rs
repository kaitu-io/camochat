//! ML-DSA-65 (FIPS 204) sign/verify round-trip + tamper-detection tests.

use chencang_core::primitives::ml_dsa;

#[test]
fn ml_dsa_sign_verify_round_trip() {
    let mut rng = rand::thread_rng();
    let (pk, sk) = ml_dsa::generate_keypair(&mut rng);
    let msg = b"chencang V1 test message";
    let sig = ml_dsa::sign(&sk, msg, &mut rng);
    ml_dsa::verify(&pk, msg, &sig).unwrap();
}

#[test]
fn ml_dsa_tampered_signature_rejected() {
    let mut rng = rand::thread_rng();
    let (pk, sk) = ml_dsa::generate_keypair(&mut rng);
    let msg = b"test message";
    let mut sig = ml_dsa::sign(&sk, msg, &mut rng);
    // Flip a bit in the middle of the signature
    sig[100] ^= 0x01;
    assert!(ml_dsa::verify(&pk, msg, &sig).is_err());
}

#[test]
fn ml_dsa_tampered_message_rejected() {
    let mut rng = rand::thread_rng();
    let (pk, sk) = ml_dsa::generate_keypair(&mut rng);
    let msg = b"original message";
    let sig = ml_dsa::sign(&sk, msg, &mut rng);
    assert!(ml_dsa::verify(&pk, b"tampered message", &sig).is_err());
}

#[test]
fn ml_dsa_wrong_signature_length_errors() {
    let mut rng = rand::thread_rng();
    let (pk, _sk) = ml_dsa::generate_keypair(&mut rng);
    let bad_sig = vec![0u8; 10];
    let result = ml_dsa::verify(&pk, b"msg", &bad_sig);
    assert!(result.is_err());
}
