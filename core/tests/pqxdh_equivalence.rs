//! PQXDH-hybrid 等价测试：Alice + Bob 必算出同样的 SRK。

use chencang_core::handshake::bundle::PreKeyBundle;
use chencang_core::handshake::key_confirm::{
    confirm_tag, derive_kc, verify_confirm_tag, CONFIRM_WHO_INITIATOR, CONFIRM_WHO_RESPONDER,
};
use chencang_core::handshake::pqxdh::{derive_initiator, derive_responder};
use chencang_core::identity::SecretIdentity;
use chencang_core::prekey::{SecretOneTimePreKey, SecretSignedPreKey};

#[test]
fn alice_bob_derive_same_srk_with_opk() {
    let mut rng = rand::thread_rng();

    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 42);

    let alice_bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [1u8; 16],
    };

    let bob_ik = SecretIdentity::generate(&mut rng);
    let (srk_bob, init_out) = derive_initiator(&mut rng, &bob_ik, &alice_bundle);

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
        &alice_bundle.pairing_nonce,
    )
    .unwrap()
    .srk;

    assert_eq!(srk_alice, srk_bob, "Alice and Bob must derive same SRK");
}

#[test]
fn alice_bob_derive_same_srk_without_opk() {
    let mut rng = rand::thread_rng();

    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);

    let alice_bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: None,
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [1u8; 16],
    };

    let bob_ik = SecretIdentity::generate(&mut rng);
    let (srk_bob, init_out) = derive_initiator(&mut rng, &bob_ik, &alice_bundle);

    let srk_alice = derive_responder(
        &alice_ik,
        &alice_spk,
        None,
        &init_out.bob_ik_pub,
        &init_out.ek_x25519_pub,
        &init_out.ek_mlkem_pub,
        &init_out.kem_ct_to_spk,
        &init_out.kem_ct_to_ik,
        init_out.kem_ct_to_opk.as_deref(),
        &alice_bundle.pairing_nonce,
    )
    .unwrap()
    .srk;

    assert_eq!(
        srk_alice, srk_bob,
        "Alice and Bob must derive same SRK (no-OPK path)"
    );
}

#[test]
fn distinct_handshakes_yield_distinct_srks() {
    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk_a = SecretOneTimePreKey::generate(&mut rng, 1);
    let alice_opk_b = SecretOneTimePreKey::generate(&mut rng, 2);

    let bundle_a = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk_a.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [1u8; 16],
    };
    let bundle_b = PreKeyBundle {
        opk: Some(alice_opk_b.public.clone()),
        ..bundle_a.clone()
    };

    let bob_ik = SecretIdentity::generate(&mut rng);
    let (srk_a, _) = derive_initiator(&mut rng, &bob_ik, &bundle_a);
    let (srk_b, _) = derive_initiator(&mut rng, &bob_ik, &bundle_b);
    assert_ne!(srk_a, srk_b, "Different OPK → different SRK");
}

#[test]
fn pairing_nonce_binds_into_srk() {
    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 42);
    let nonce_real = [7u8; 16];
    let bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: nonce_real,
    };
    let bob_ik = SecretIdentity::generate(&mut rng);
    let (srk_bob, out) = derive_initiator(&mut rng, &bob_ik, &bundle);

    // Responder with the SAME nonce → must AGREE with initiator.
    let srk_ok = derive_responder(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &out.bob_ik_pub,
        &out.ek_x25519_pub,
        &out.ek_mlkem_pub,
        &out.kem_ct_to_spk,
        &out.kem_ct_to_ik,
        out.kem_ct_to_opk.as_deref(),
        &nonce_real,
    )
    .unwrap()
    .srk;
    assert_eq!(srk_ok, srk_bob, "matching nonce → SRK agreement");

    // Responder with a TAMPERED nonce (only the nonce differs) → must DIVERGE.
    let nonce_bad = [9u8; 16];
    let srk_bad = derive_responder(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &out.bob_ik_pub,
        &out.ek_x25519_pub,
        &out.ek_mlkem_pub,
        &out.kem_ct_to_spk,
        &out.kem_ct_to_ik,
        out.kem_ct_to_opk.as_deref(),
        &nonce_bad,
    )
    .unwrap()
    .srk;
    assert_ne!(
        srk_bad, srk_bob,
        "flipping ONLY the pairing_nonce must break SRK agreement"
    );
}

#[test]
fn kem_ciphertext_binds_into_srk() {
    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 42);
    let nonce = [7u8; 16];
    let bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: nonce,
    };
    let bob_ik = SecretIdentity::generate(&mut rng);
    let (srk_bob, out) = derive_initiator(&mut rng, &bob_ik, &bundle);

    // Sanity: untampered → agreement.
    let srk_ok = derive_responder(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &out.bob_ik_pub,
        &out.ek_x25519_pub,
        &out.ek_mlkem_pub,
        &out.kem_ct_to_spk,
        &out.kem_ct_to_ik,
        out.kem_ct_to_opk.as_deref(),
        &nonce,
    )
    .unwrap()
    .srk;
    assert_eq!(srk_ok, srk_bob);

    // Flip one byte of the SPK KEM ciphertext the responder sees. The ciphertext
    // bytes reach the transcript independent of the decap shared secret (ML-KEM
    // implicit rejection), so this must either be rejected at decap OR diverge.
    let mut tampered = out.kem_ct_to_spk.clone();
    tampered[0] ^= 0x01;
    match derive_responder(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &out.bob_ik_pub,
        &out.ek_x25519_pub,
        &out.ek_mlkem_pub,
        &tampered,
        &out.kem_ct_to_ik,
        out.kem_ct_to_opk.as_deref(),
        &nonce,
    ) {
        Ok(out_bad) => assert_ne!(out_bad.srk, srk_bob, "tampered KEM ct must not agree"),
        Err(_) => { /* rejection also proves the ct is not silently ignored */ }
    }
}

#[test]
fn key_confirmation_round_trip() {
    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 42);
    let nonce = [7u8; 16];
    let bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: nonce,
    };
    let bob_ik = SecretIdentity::generate(&mut rng);
    let (srk_bob, init_out) = derive_initiator(&mut rng, &bob_ik, &bundle);
    let resp = derive_responder(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &init_out.bob_ik_pub,
        &init_out.ek_x25519_pub,
        &init_out.ek_mlkem_pub,
        &init_out.kem_ct_to_spk,
        &init_out.kem_ct_to_ik,
        init_out.kem_ct_to_opk.as_deref(),
        &nonce,
    )
    .unwrap();

    // Both sides agree on SRK and transcript.
    assert_eq!(srk_bob, resp.srk);
    assert_eq!(init_out.transcript, resp.transcript);

    // Initiator (Bob) computes confirm_b; responder (Alice) verifies it.
    let kc_bob = derive_kc(&srk_bob);
    let confirm_b = confirm_tag(&kc_bob, &init_out.transcript, CONFIRM_WHO_INITIATOR);
    let kc_alice = derive_kc(&resp.srk);
    assert!(
        verify_confirm_tag(
            &kc_alice,
            &resp.transcript,
            CONFIRM_WHO_INITIATOR,
            &confirm_b
        ),
        "responder must verify the initiator's confirm_b"
    );

    // Responder (Alice) computes confirm_a; initiator (Bob) verifies it.
    let confirm_a = confirm_tag(&kc_alice, &resp.transcript, CONFIRM_WHO_RESPONDER);
    assert!(
        verify_confirm_tag(
            &kc_bob,
            &init_out.transcript,
            CONFIRM_WHO_RESPONDER,
            &confirm_a
        ),
        "initiator must verify the responder's confirm_a"
    );

    // A tag with the wrong `who` (reflection) must NOT verify.
    assert!(
        !verify_confirm_tag(
            &kc_alice,
            &resp.transcript,
            CONFIRM_WHO_RESPONDER,
            &confirm_b
        ),
        "confirm_b must not verify as a responder tag (reflection defense)"
    );
    assert!(
        !verify_confirm_tag(
            &kc_bob,
            &init_out.transcript,
            CONFIRM_WHO_INITIATOR,
            &confirm_a
        ),
        "confirm_a must not verify as an initiator tag (reverse reflection defense)"
    );
}
