//! ML-KEM-768 (FIPS 203) round-trip + tamper-detection tests.
//!
//! 注意：NIST 官方 KAT 向量（来自 ACVP）暂未在此引入；本测试文件聚焦于
//! 行为级别验证（round-trip、distinctness、implicit rejection、长度校验）。
//! 后续可在同一文件追加 KAT JSON 解析。

use chencang_core::primitives::ml_kem;

#[test]
fn ml_kem_encap_decap_round_trip() {
    let mut rng = rand::thread_rng();
    let (pk, sk) = ml_kem::generate_keypair(&mut rng);
    let (ct, ss_alice) = ml_kem::encapsulate(&pk, &mut rng);
    let ss_bob = ml_kem::decapsulate(&sk, &ct).unwrap();
    assert_eq!(ss_alice.as_bytes(), ss_bob.as_bytes());
}

#[test]
fn ml_kem_distinct_keypairs_produce_distinct_outputs() {
    let mut rng = rand::thread_rng();
    let (pk1, _) = ml_kem::generate_keypair(&mut rng);
    let (pk2, _) = ml_kem::generate_keypair(&mut rng);
    // Different keypair → different public keys (overwhelming probability)
    assert_ne!(pk1.0, pk2.0);
}

#[test]
fn ml_kem_tampered_ciphertext_implicit_rejection() {
    // FIPS 203 ML-KEM uses implicit rejection: decap with tampered ct returns
    // a deterministic but different shared secret instead of an error.
    // This is correct per spec — we test it doesn't panic and produces SOMETHING.
    let mut rng = rand::thread_rng();
    let (pk, sk) = ml_kem::generate_keypair(&mut rng);
    let (mut ct, ss_orig) = ml_kem::encapsulate(&pk, &mut rng);
    ct[0] ^= 0x01;
    let ss_tampered = ml_kem::decapsulate(&sk, &ct).unwrap();
    // With overwhelming probability, the tampered ss differs from original
    assert_ne!(ss_orig.as_bytes(), ss_tampered.as_bytes());
}

#[test]
fn ml_kem_decap_wrong_length_errors() {
    let mut rng = rand::thread_rng();
    let (_, sk) = ml_kem::generate_keypair(&mut rng);
    let bad_ct = vec![0u8; 10];
    let result = ml_kem::decapsulate(&sk, &bad_ct);
    assert!(result.is_err());
}

#[test]
fn ml_kem_lengths_match_constants() {
    let mut rng = rand::thread_rng();
    let (pk, sk) = ml_kem::generate_keypair(&mut rng);
    let (ct, ss) = ml_kem::encapsulate(&pk, &mut rng);

    assert_eq!(pk.as_bytes().len(), ml_kem::PUBLIC_KEY_LEN);
    assert_eq!(sk.to_bytes().len(), ml_kem::SECRET_KEY_LEN);
    assert_eq!(ct.len(), ml_kem::CIPHERTEXT_LEN);
    assert_eq!(ss.as_bytes().len(), ml_kem::SHARED_SECRET_LEN);
}
