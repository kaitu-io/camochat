//! ML-DSA-65 cross-language KAT emitter.
//!
//! This is *not* a normal test: it materialises three deterministic test
//! vectors and writes them to `kat-vectors/ml_dsa_65.json` so that
//! chencang-server can verify them in Node.
//!
//! Run with:
//!   cargo test --release --test emit_ml_dsa_kat -- --ignored emit_ml_dsa_kat_for_node
//!
//! It is `#[ignore]`-gated so the standard `cargo test` flow stays cheap. The
//! produced file is committed to the repo; regenerate only when the underlying
//! ml-dsa crate version changes.
//!
//! Determinism: ml-dsa 0.1.0-rc.11 implements the FIPS 204 deterministic
//! variant for `sign`, and `SecretKey::from_seed` is purely a function of the
//! seed bytes. Three vectors with seeds {0x07, 0x08, 0x09} repeated give
//! reproducible outputs.

use chencang_core::primitives::ml_dsa;
use rand::rngs::OsRng;
use serde_json::json;
use std::fs;
use std::path::PathBuf;

#[test]
#[ignore = "emits kat-vectors/ml_dsa_65.json — run explicitly with --ignored"]
fn emit_ml_dsa_kat_for_node() {
    let seeds: [[u8; 32]; 3] = [[0x07u8; 32], [0x08u8; 32], [0x09u8; 32]];
    let messages: [&[u8]; 3] = [
        b"",
        b"chencang-v1-ml-dsa-65-kat-1",
        b"\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f",
    ];

    let mut vectors = Vec::new();
    let mut rng = OsRng;
    for (i, seed) in seeds.iter().enumerate() {
        let sk = ml_dsa::SecretKey::from_seed(*seed);
        let pk = sk.public_key();
        let msg = messages[i];
        let sig = ml_dsa::sign(&sk, msg, &mut rng);
        // Sanity: round-trip in Rust.
        ml_dsa::verify(&pk, msg, &sig).expect("self-verify");
        vectors.push(json!({
            "seed":      hex::encode(seed),
            "public":    hex::encode(pk.to_bytes()),
            "message":   hex::encode(msg),
            "signature": hex::encode(&sig),
        }));
    }

    let payload = json!({
        "source": "core/tests/emit_ml_dsa_kat.rs — ml-dsa 0.1.0-rc.11 deterministic FIPS 204",
        "param_set": "ML-DSA-65",
        "public_key_len": ml_dsa::PUBLIC_KEY_LEN,
        "signature_len": ml_dsa::SIGNATURE_LEN,
        "vectors": vectors,
    });

    let out_path: PathBuf = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("kat-vectors")
        .join("ml_dsa_65.json");
    fs::create_dir_all(out_path.parent().expect("kat-vectors parent")).expect("mkdir");
    let formatted = serde_json::to_string_pretty(&payload).expect("serialize");
    fs::write(&out_path, format!("{formatted}\n")).expect("write json");
    println!("wrote {}", out_path.display());
}
