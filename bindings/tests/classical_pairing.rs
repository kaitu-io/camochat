//! Facade-level Rust round trip for the CLASSICAL (suite 0x01, X25519+Ed25519)
//! in-band pairing surface, mimicking what Swift / Kotlin will do once the
//! bindings are generated. Mirrors `facade_round_trip.rs` (the PQ path).

use std::sync::Arc;

use chencang_bindings::{
    compute_confirm_tag, confirm_who_initiator, derive_initiator_classical,
    derive_responder_classical, verify_confirm_tag, ClassicalOneTimePreKey, ClassicalPreKeyBundle,
    ClassicalPublicIdentity, ClassicalSignedPreKey, SecretIdentity, SecretOneTimePreKey,
    SecretSignedPreKey, Session,
};

/// Build a classical bundle from facade-generated identity + prekeys, run the
/// initiator → responder X3DH, assert both sides derive the same SRK, verify the
/// key-confirmation tag, then build classical Sessions and round-trip a message.
#[test]
fn classical_alice_bob_round_trip_with_opk() {
    let bob_ik = Arc::new(SecretIdentity::new());
    let alice_ik = Arc::new(SecretIdentity::new());
    let alice_spk = Arc::new(SecretSignedPreKey::new(alice_ik.clone(), 1).expect("spk generation"));
    let alice_opk = Arc::new(SecretOneTimePreKey::new(7));

    let alice_pub = alice_ik.public_identity();
    let spk_pub = alice_spk.public_form();
    let opk_pub = alice_opk.public_form();

    let pairing_nonce = vec![0x22u8; 16];
    let bundle = ClassicalPreKeyBundle {
        version: 0x01,
        suite_id: 0x01,
        ik: ClassicalPublicIdentity {
            ed25519: alice_pub.ik_sig_ed25519.clone(),
            x25519: alice_pub.ik_dh_x25519.clone(),
        },
        spk: ClassicalSignedPreKey {
            x25519: spk_pub.spk_x25519.clone(),
            // The classical bundle carries the classical-only Ed25519 signature
            // (covers `x25519 || epoch`), exposed via `ed25519_sig_classical`.
            sig_ed25519: spk_pub.ed25519_sig_classical.clone(),
            epoch: spk_pub.spk_version,
        },
        opk: Some(ClassicalOneTimePreKey {
            id: opk_pub.opk_index,
            x25519: opk_pub.opk_x25519.clone(),
        }),
        pairing_nonce: pairing_nonce.clone(),
        inviter_username: "alice".to_string(),
    };

    let init_out = derive_initiator_classical(bob_ik.clone(), bundle).expect("initiator classical");

    let resp_out = derive_responder_classical(
        alice_ik.clone(),
        alice_spk.clone(),
        Some(alice_opk.clone()),
        ClassicalPublicIdentity {
            ed25519: init_out.bob_ik.ik_sig_ed25519.clone(),
            x25519: init_out.bob_ik.ik_dh_x25519.clone(),
        },
        init_out.ek_x25519_pub.clone(),
        Some(7),
        pairing_nonce,
    )
    .expect("responder classical");

    assert_eq!(
        init_out.session_root_key, resp_out.session_root_key,
        "both sides must derive the same SRK"
    );
    assert_eq!(
        init_out.transcript, resp_out.transcript,
        "both sides must derive the same transcript"
    );

    // Key confirmation: initiator computes confirm_b over its (srk, transcript),
    // responder verifies it against its own (srk, transcript).
    let who_i = confirm_who_initiator();
    let confirm_b = compute_confirm_tag(
        init_out.session_root_key.clone(),
        init_out.transcript.clone(),
        who_i,
    )
    .expect("compute confirm tag");
    let verified = verify_confirm_tag(
        resp_out.session_root_key.clone(),
        resp_out.transcript.clone(),
        who_i,
        confirm_b,
    )
    .expect("verify confirm tag");
    assert!(
        verified,
        "responder must verify the initiator's confirmation tag"
    );

    // Build classical Sessions and round-trip a message. Bob (initiator) sends.
    let sid = vec![9u8, 9, 9, 9, 9];
    let bob_sess = Session::initiator_after_handshake_classical(
        init_out.session_root_key.clone(),
        sid.clone(),
        alice_pub.ik_dh_x25519.clone(),
        init_out.ek_x25519_secret.clone(),
    )
    .expect("bob classical session");
    let alice_sess = Session::responder_after_handshake_classical(
        resp_out.session_root_key.clone(),
        sid,
        init_out.ek_x25519_pub.clone(),
    )
    .expect("alice classical session");

    let ct = bob_sess
        .encrypt_to_bytes(b"hello classical".to_vec())
        .expect("encrypt");
    let plain = alice_sess.decrypt_from_bytes(ct).expect("decrypt");
    assert_eq!(plain, b"hello classical");
}

/// Same flow without an OPK (the optional one-time prekey leg).
#[test]
fn classical_alice_bob_round_trip_without_opk() {
    let bob_ik = Arc::new(SecretIdentity::new());
    let alice_ik = Arc::new(SecretIdentity::new());
    let alice_spk = Arc::new(SecretSignedPreKey::new(alice_ik.clone(), 1).expect("spk generation"));

    let alice_pub = alice_ik.public_identity();
    let spk_pub = alice_spk.public_form();

    let pairing_nonce = vec![0x55u8; 16];
    let bundle = ClassicalPreKeyBundle {
        version: 0x01,
        suite_id: 0x01,
        ik: ClassicalPublicIdentity {
            ed25519: alice_pub.ik_sig_ed25519.clone(),
            x25519: alice_pub.ik_dh_x25519.clone(),
        },
        spk: ClassicalSignedPreKey {
            x25519: spk_pub.spk_x25519.clone(),
            sig_ed25519: spk_pub.ed25519_sig_classical.clone(),
            epoch: spk_pub.spk_version,
        },
        opk: None,
        pairing_nonce: pairing_nonce.clone(),
        inviter_username: "alice".to_string(),
    };

    let init_out = derive_initiator_classical(bob_ik.clone(), bundle).expect("initiator classical");
    let resp_out = derive_responder_classical(
        alice_ik.clone(),
        alice_spk.clone(),
        None,
        ClassicalPublicIdentity {
            ed25519: init_out.bob_ik.ik_sig_ed25519.clone(),
            x25519: init_out.bob_ik.ik_dh_x25519.clone(),
        },
        init_out.ek_x25519_pub.clone(),
        None,
        pairing_nonce,
    )
    .expect("responder classical");

    assert_eq!(init_out.session_root_key, resp_out.session_root_key);
    assert_eq!(init_out.transcript, resp_out.transcript);

    let sid = vec![1u8, 2, 3, 4, 5];
    let bob_sess = Session::initiator_after_handshake_classical(
        init_out.session_root_key.clone(),
        sid.clone(),
        alice_pub.ik_dh_x25519.clone(),
        init_out.ek_x25519_secret.clone(),
    )
    .expect("bob classical session");
    let alice_sess = Session::responder_after_handshake_classical(
        resp_out.session_root_key.clone(),
        sid,
        init_out.ek_x25519_pub.clone(),
    )
    .expect("alice classical session");

    let ct = bob_sess
        .encrypt_to_bytes(b"opk-less classical".to_vec())
        .expect("encrypt");
    let plain = alice_sess.decrypt_from_bytes(ct).expect("decrypt");
    assert_eq!(plain, b"opk-less classical");
}
