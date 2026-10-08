//! Facade-level Rust round trip mimicking what Swift / Kotlin will do once
//! the bindings are generated.

use std::sync::Arc;

use chencang_bindings::{
    derive_initiator_handshake, derive_responder_handshake, PreKeyBundle, SecretIdentity,
    SecretOneTimePreKey, SecretSignedPreKey, Session,
};

#[test]
fn alice_bob_round_trip_with_opk() {
    let bob_ik = Arc::new(SecretIdentity::new());
    let alice_ik = Arc::new(SecretIdentity::new());
    let alice_spk = Arc::new(SecretSignedPreKey::new(alice_ik.clone(), 1).expect("spk generation"));
    let alice_opk = Arc::new(SecretOneTimePreKey::new(7));

    let pairing_nonce = vec![0x22; 16];
    let bundle = PreKeyBundle {
        ik: alice_ik.public_identity(),
        spk: alice_spk.public_form(),
        opk: Some(alice_opk.public_form()),
        inviter_username: "alice".to_string(),
        invite_id: vec![0x11; 16],
        pairing_nonce: pairing_nonce.clone(),
    };

    let init_out = derive_initiator_handshake(bob_ik.clone(), bundle).expect("initiator handshake");

    let srk_alice = derive_responder_handshake(
        alice_ik.clone(),
        alice_spk.clone(),
        Some(alice_opk.clone()),
        init_out.bob_identity_public.clone(),
        init_out.ek_x25519_pub.clone(),
        init_out.ek_mlkem_pub.clone(),
        init_out.kem_ct_to_spk.clone(),
        init_out.kem_ct_to_ik.clone(),
        init_out.kem_ct_to_opk.clone(),
        pairing_nonce,
    )
    .expect("responder handshake");

    assert_eq!(
        srk_alice, init_out.session_root_key,
        "responder must rederive the same SRK as the initiator"
    );

    let sid = vec![9, 9, 9, 9, 9];

    // Bob is the initiator (sender of the first ciphertext after handshake).
    let bob_sess = Session::initiator_after_handshake(
        init_out.session_root_key.clone(),
        sid.clone(),
        alice_ik.public_identity(),
        init_out.ek_x25519_secret.clone().unwrap(),
        init_out.ek_mlkem_secret.clone().unwrap(),
    )
    .expect("bob session");
    let alice_sess =
        Session::responder_after_handshake(srk_alice.clone(), sid.clone(), init_out.clone())
            .expect("alice session");

    let wire = bob_sess
        .encrypt(b"hello chencang".to_vec())
        .expect("encrypt");
    let plain = alice_sess.decrypt(wire).expect("decrypt");
    assert_eq!(plain, b"hello chencang");
}

#[test]
fn alice_bob_round_trip_without_opk() {
    let bob_ik = Arc::new(SecretIdentity::new());
    let alice_ik = Arc::new(SecretIdentity::new());
    let alice_spk = Arc::new(SecretSignedPreKey::new(alice_ik.clone(), 1).expect("spk generation"));

    let pairing_nonce = vec![0x22; 16];
    let bundle = PreKeyBundle {
        ik: alice_ik.public_identity(),
        spk: alice_spk.public_form(),
        opk: None,
        inviter_username: "alice".to_string(),
        invite_id: vec![0x11; 16],
        pairing_nonce: pairing_nonce.clone(),
    };

    let init_out = derive_initiator_handshake(bob_ik.clone(), bundle).expect("initiator handshake");
    let srk_alice = derive_responder_handshake(
        alice_ik.clone(),
        alice_spk.clone(),
        None,
        init_out.bob_identity_public.clone(),
        init_out.ek_x25519_pub.clone(),
        init_out.ek_mlkem_pub.clone(),
        init_out.kem_ct_to_spk.clone(),
        init_out.kem_ct_to_ik.clone(),
        init_out.kem_ct_to_opk.clone(),
        pairing_nonce,
    )
    .expect("responder handshake");

    assert_eq!(srk_alice, init_out.session_root_key);

    let sid = vec![1, 2, 3, 4, 5];

    let bob_sess = Session::initiator_after_handshake(
        init_out.session_root_key.clone(),
        sid.clone(),
        alice_ik.public_identity(),
        init_out.ek_x25519_secret.clone().unwrap(),
        init_out.ek_mlkem_secret.clone().unwrap(),
    )
    .expect("bob session");
    let alice_sess = Session::responder_after_handshake(srk_alice.clone(), sid, init_out)
        .expect("alice session");

    let wire = bob_sess
        .encrypt(b"opk-less message".to_vec())
        .expect("encrypt");
    let plain = alice_sess.decrypt(wire).expect("decrypt");
    assert_eq!(plain, b"opk-less message");
}

#[test]
fn secret_identity_serialize_round_trip() {
    let ik = SecretIdentity::new();
    let bytes = ik.serialize_for_local_storage();
    let restored = SecretIdentity::from_local_storage(bytes).expect("deserialize");
    assert_eq!(
        ik.public_identity().ik_dh_x25519,
        restored.public_identity().ik_dh_x25519,
    );
    assert_eq!(
        ik.public_identity().ik_sig_ed25519,
        restored.public_identity().ik_sig_ed25519,
    );
}
