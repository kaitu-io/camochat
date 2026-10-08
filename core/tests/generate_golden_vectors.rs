//! Phase A3.8 Task 21 — deterministic golden vectors generator.
//!
//! Run with: `cargo test --release --test generate_golden_vectors -- --nocapture`.
//! Outputs `bindings/golden-vectors/vectors.json`, the
//! cross-platform-equivalence anchor (spec §16.2). Regenerating must produce
//! the byte-identical file every time the PRNG seed below is unchanged.
//!
//! Swift/Kotlin tests load `alice_session_state_hex`, reconstruct the
//! responder Session via `Session::from_serialized_state`, and decrypt the
//! wire strings — proving Rust + Swift + Kotlin produce the same plaintexts.

use chencang_core::handshake::bundle::PreKeyBundle;
use chencang_core::handshake::pqxdh::{derive_initiator, derive_responder};
use chencang_core::identity::keypair::SecretIdentity;
use chencang_core::prekey::one_time::SecretOneTimePreKey;
use chencang_core::prekey::signed::SecretSignedPreKey;
use chencang_core::session::Session;
use rand_chacha::ChaCha20Rng;
use rand_core::SeedableRng;
use serde_json::json;
use std::path::PathBuf;

/// Committed PRNG seed — DO NOT change without regenerating ALL platform
/// fixtures. The 8 bytes spell "ChenCang" backwards in hex-ish.
const SEED_HEX: &str = "c4ec4a4ccce15b42";
const SEED_BYTES: [u8; 8] = [0xc4, 0xec, 0x4a, 0x4c, 0xcc, 0xe1, 0x5b, 0x42];

// Regenerator, not a routine assertion: it WRITES the binding-facing
// `bindings/golden-vectors/vectors.json`. `#[ignore]` keeps it out of
// the default `cargo test` run so routine testing never mutates that artifact,
// and so the large ML-KEM key material it builds does not overflow the default
// debug test-thread stack. Regenerate the committed vectors explicitly with:
//   cargo test -p chencang-core --release --test generate_golden_vectors -- --ignored --nocapture
#[test]
#[ignore = "regenerator: writes golden vectors.json; run with --release --ignored"]
fn emit_golden_vectors() {
    // ChaCha20Rng seeded with the committed byte stream — same seed across
    // every platform run produces byte-identical key material.
    let mut seed_32 = [0u8; 32];
    seed_32[..8].copy_from_slice(&SEED_BYTES);
    let mut rng = ChaCha20Rng::from_seed(seed_32);

    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 7);
    let bob_ik = SecretIdentity::generate(&mut rng);

    let alice_pub = alice_ik.public();
    let bob_pub = bob_ik.public();

    let bundle = PreKeyBundle {
        ik: alice_pub.clone(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [0u8; 16],
    };

    let (srk_bob, init_out) = derive_initiator(&mut rng, &bob_ik, &bundle);
    let srk_alice = derive_responder(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &init_out.bob_ik_pub,
        &init_out.ek_x25519_pub,
        &init_out.ek_mlkem_pub,
        &init_out.kem_ct_to_spk,
        &init_out.kem_ct_to_ik,
        init_out.kem_ct_to_opk.as_deref(),
        &bundle.pairing_nonce,
    )
    .expect("responder must derive SRK")
    .srk;
    assert_eq!(srk_bob, srk_alice, "initiator and responder SRK must match");

    let sid = [9u8; 5];
    let mut alice_sess = Session::responder_after_handshake(srk_alice, sid, &init_out);
    let mut bob_sess = Session::initiator_after_handshake(
        srk_bob,
        sid,
        &alice_pub,
        init_out.ek_x25519_secret,
        init_out.ek_mlkem_secret,
    );

    let m1: &[u8] = b"first message";
    let m2: &[u8] = b"second message";

    let wire1 = bob_sess.encrypt(m1, &mut rng).expect("encrypt m1");
    let wire2 = bob_sess.encrypt(m2, &mut rng).expect("encrypt m2");

    // SessionState embeds `kem_ratchet_last_unix` (wall-clock seconds) which
    // would make the serialized snapshot non-deterministic. Pin it to 0 in
    // the snapshot so the regenerated vectors.json stays byte-identical on
    // every run. Decryption logic does not depend on this field.
    bob_sess.state.kem_ratchet_last_unix = 0;
    alice_sess.state.kem_ratchet_last_unix = 0;

    // Snapshot Alice's state BEFORE decrypt so loading from
    // `alice_session_state_hex` in Swift/Kotlin replays the same decrypts.
    let alice_state_hex = hex::encode(alice_sess.state.serialize());
    let bob_state_hex = hex::encode(bob_sess.state.serialize());

    // Sanity: Alice (in this run) can also decrypt — proves the vectors are
    // genuinely consistent before we emit them.
    {
        let mut alice_clone = Session {
            state: chencang_core::session::SessionState::deserialize(
                &hex::decode(&alice_state_hex).unwrap(),
            )
            .unwrap(),
        };
        assert_eq!(alice_clone.decrypt(&wire1).unwrap(), m1);
        assert_eq!(alice_clone.decrypt(&wire2).unwrap(), m2);
    }
    // Drain the actual session too so any future assertions in this file see
    // a consistent picture.
    assert_eq!(alice_sess.decrypt(&wire1).unwrap(), m1);
    assert_eq!(alice_sess.decrypt(&wire2).unwrap(), m2);

    // Rich-media vector: fixed inputs, no RNG. `encrypt_media_blob` now
    // generates its own `blob_secret` (F2 fix — core owns nonce-reuse
    // safety, callers can no longer pass a secret in), so to keep this
    // golden vector byte-identical to the committed one we go around it:
    // derive the same key/nonce from the fixed `media_secret` via
    // `derive_blob_material` and seal directly with `seal_cca`, exactly
    // what `encrypt_media_blob` does internally for a given secret.
    let media_secret = [0x5Au8; 32];
    let media_plain: &[u8] = b"chencang media golden v1";
    let media_material = chencang_core::payload::derive_blob_material(&media_secret);
    let media_cca = chencang_core::blob::seal_cca(
        media_plain,
        &media_material.blob_key,
        &media_material.blob_nonce,
        chencang_core::payload::MEDIA_KIND_IMAGE,
    )
    .expect("seal media");
    let media_frame =
        chencang_core::payload::encode_media_ref_frame(&[chencang_core::payload::MediaRef {
            kind: chencang_core::payload::MEDIA_KIND_IMAGE,
            dur_ms: 0,
            width: 1920,
            height: 1080,
            byte_len: u32::try_from(media_cca.len()).unwrap(),
            blob_secret: media_secret,
        }])
        .expect("encode media frame");

    // Signed-config vector: fixed TEST seed (never the production key).
    let (config_seed_hex, config_payload_b64, config_envelope) = {
        use base64::engine::general_purpose::URL_SAFE_NO_PAD;
        use base64::Engine;
        let seed = [9u8; 32];
        let payload = br#"{"schema":1,"seq":1}"#;
        let sk = chencang_core::primitives::ed25519::SigningKey::from_bytes(seed);
        let mut msg = b"chencang-config-v1\n".to_vec();
        msg.extend_from_slice(payload);
        let sig = sk.sign(&msg);
        let p = URL_SAFE_NO_PAD.encode(payload);
        let sg = URL_SAFE_NO_PAD.encode(sig);
        (
            hex::encode(seed),
            p.clone(),
            json!({ "p": p, "s": sg }).to_string(),
        )
    };

    let vectors = json!({
        "spec_version": "1.0.0",
        "core_version": env!("CARGO_PKG_VERSION"),
        "prng_seed_hex": SEED_HEX,
        "alice": {
            "ik_dh_x25519_hex":   hex::encode(alice_pub.ik_dh_x25519.0),
            "ik_sig_ed25519_hex": hex::encode(alice_pub.ik_sig_ed25519.0),
            "ik_kem_mlkem768_hex": hex::encode(alice_pub.ik_kem_mlkem768.as_bytes()),
            "ik_sig_mldsa65_hex":  hex::encode(alice_pub.ik_sig_mldsa65.to_bytes()),
        },
        "bob": {
            "ik_dh_x25519_hex":   hex::encode(bob_pub.ik_dh_x25519.0),
            "ik_sig_ed25519_hex": hex::encode(bob_pub.ik_sig_ed25519.0),
            "ik_kem_mlkem768_hex": hex::encode(bob_pub.ik_kem_mlkem768.as_bytes()),
            "ik_sig_mldsa65_hex":  hex::encode(bob_pub.ik_sig_mldsa65.to_bytes()),
        },
        "srk_hex": hex::encode(srk_bob),
        "session_id_hex": "0909090909",
        "alice_session_state_hex": alice_state_hex,
        "bob_session_state_hex":   bob_state_hex,
        "messages": [
            { "plaintext_hex": hex::encode(m1), "wire": wire1 },
            { "plaintext_hex": hex::encode(m2), "wire": wire2 },
        ],
        "media": {
            "blob_secret_hex": hex::encode(media_secret),
            "blob_id": chencang_core::blob::media_blob_id(&media_secret),
            "kind": chencang_core::payload::MEDIA_KIND_IMAGE,
            "plaintext_hex": hex::encode(media_plain),
            "cca_hex": hex::encode(&media_cca),
            "media_ref_frame_hex": hex::encode(&media_frame),
        },
        "signed_config": {
            "seed_hex": config_seed_hex,
            "public_key_hex": hex::encode(
                chencang_core::primitives::ed25519::SigningKey::from_bytes([9u8; 32])
                    .verifying_key()
                    .0
            ),
            "payload_b64url": config_payload_b64,
            "envelope": config_envelope,
        },
    });

    // Pretty-print with 2-space indentation so diffs are reviewable.
    let out = serde_json::to_string_pretty(&vectors).expect("serialize vectors");
    // Resolve the output path relative to CARGO_MANIFEST_DIR (core/)
    // so the test works from any cwd.
    let out_path: PathBuf = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("bindings")
        .join("golden-vectors")
        .join("vectors.json");
    if let Some(parent) = out_path.parent() {
        std::fs::create_dir_all(parent).expect("create golden-vectors dir");
    }
    std::fs::write(&out_path, &out).expect("write vectors.json");
    println!(
        "[golden] wrote {} ({} bytes)",
        out_path.display(),
        out.len()
    );
}
