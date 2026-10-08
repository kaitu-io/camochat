//! End-to-end: full PQXDH handshake → Session → message exchange.
//!
//! These tests exercise the full V1 protocol stack:
//! identity → prekey → PQXDH-hybrid handshake → SRK convergence →
//! Session construction (initiator + responder) → encrypt/decrypt round
//! trip → AEAD tamper detection.

use chencang_core::handshake::bundle::PreKeyBundle;
use chencang_core::handshake::pqxdh::{derive_initiator, derive_responder};
use chencang_core::identity::SecretIdentity;
use chencang_core::prekey::{SecretOneTimePreKey, SecretSignedPreKey};
use chencang_core::session::Session;

/// Build a freshly-handshook (Bob initiator, Alice responder) pair of
/// `Session` objects sharing the same SRK and sid.
fn setup_session_pair() -> (Session, Session) {
    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 1);

    let alice_bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [0u8; 16],
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

    assert_eq!(srk_alice, srk_bob);

    let sid = [9; 5];
    // Build the responder first (it borrows `init_out`), then move the
    // initiator's retained EK secrets into the initiator session.
    let alice_session = Session::responder_after_handshake(srk_alice, sid, &init_out);
    let bob_session = Session::initiator_after_handshake(
        srk_bob,
        sid,
        &alice_bundle.ik,
        init_out.ek_x25519_secret,
        init_out.ek_mlkem_secret,
    );
    (bob_session, alice_session)
}

#[test]
fn bob_to_alice_single_message() {
    let mut rng = rand::thread_rng();
    let (mut bob, mut alice) = setup_session_pair();

    let plaintext = b"hello alice from bob";
    let wire = bob.encrypt(plaintext, &mut rng).unwrap();
    let recovered = alice.decrypt(&wire).unwrap();
    assert_eq!(recovered.as_slice(), plaintext);
}

#[test]
fn multiple_messages_one_direction() {
    let mut rng = rand::thread_rng();
    let (mut bob, mut alice) = setup_session_pair();
    let messages: &[&[u8]] = &[
        b"first",
        b"second message",
        b"a longer third message with 50 bytes of payload data here.",
    ];
    for msg in messages {
        let wire = bob.encrypt(msg, &mut rng).unwrap();
        let recovered = alice.decrypt(&wire).unwrap();
        assert_eq!(recovered.as_slice(), *msg);
    }
}

#[test]
fn tampered_ciphertext_rejected() {
    let mut rng = rand::thread_rng();
    let (mut bob, mut alice) = setup_session_pair();
    let wire = bob.encrypt(b"secret", &mut rng).unwrap();
    // Flip one z-base32 char near the END (the 16-byte Poly1305 tag is the last
    // bytes of the wire, so a trailing char always lands inside the AEAD-covered
    // ciphertext+tag region — unlike the header, which now carries an unauthenticated
    // seeded `dh_pub` on the initiator's first frame).
    let chars: Vec<char> = wire.chars().collect();
    let pos = chars.len() - 2;
    let mut tampered: String = chars[..pos].iter().collect();
    tampered.push(if chars[pos] == 'y' { 'b' } else { 'y' });
    tampered.extend(chars[pos + 1..].iter());
    assert!(alice.decrypt(&tampered).is_err());
}

#[test]
fn alice_to_bob_then_back_round_trips_both_directions() {
    let (mut bob_session, mut alice_session) = setup_session_pair();
    let mut rng = rand::thread_rng();
    let ct1 = bob_session
        .encrypt_to_bytes(b"hi from bob", &mut rng)
        .unwrap();
    assert_eq!(
        alice_session.decrypt_from_bytes(&ct1).unwrap(),
        b"hi from bob"
    );
    // THE FIX: responder -> initiator
    let ct2 = alice_session
        .encrypt_to_bytes(b"hi back from alice", &mut rng)
        .unwrap();
    assert_eq!(
        bob_session.decrypt_from_bytes(&ct2).unwrap(),
        b"hi back from alice"
    );
    let ct3 = bob_session.encrypt_to_bytes(b"msg3", &mut rng).unwrap();
    assert_eq!(alice_session.decrypt_from_bytes(&ct3).unwrap(), b"msg3");
    let ct4 = alice_session.encrypt_to_bytes(b"msg4", &mut rng).unwrap();
    assert_eq!(bob_session.decrypt_from_bytes(&ct4).unwrap(), b"msg4");
}

#[test]
fn reverse_kem_ratchet_decaps_on_initiator() {
    let (mut bob_session, mut alice_session) = setup_session_pair();
    let mut rng = rand::thread_rng();
    // Force Alice's next send to ride a KEM ratchet.
    alice_session.state.kem_messages_since_ratchet =
        chencang_core::session::kem_ratchet::KEM_RATCHET_THRESHOLD_MSGS;
    let ct = alice_session
        .encrypt_to_bytes(b"kem-ratchet-frame", &mut rng)
        .unwrap();
    assert_eq!(
        bob_session.decrypt_from_bytes(&ct).unwrap(),
        b"kem-ratchet-frame"
    );
}

/// #202 regression: responder-side send chain advance does not strand fresh receiver.
///
/// Failure scenario (pre-fix):
///   1. A (responder) `encrypt_to_bytes` msg-1 → triggers step_send (send chain
///      was None), header carries `dh_pub`, `ratchet_gen=1`. Wire-1 LOST in transit.
///   2. A `encrypt_to_bytes` msg-2 → step_send SKIPPED (send chain Some now).
///      Pre-fix header carried NO `dh_pub`, `ratchet_gen=1`.
///   3. B (fresh initiator) receives wire-2 first. Its `recv_chain_key=None`.
///      Pre-fix decrypt path: `header.dh_pub.is_none()` → no step_recv → falls
///      through to `recv_chain_key.take()` → `Internal("decrypt: no recv chain key")`.
///
/// Post-fix:
///   * encrypt_to_bytes now ALWAYS attaches `dh_pub` when `dh_send_x25519` is Some.
///     msg-2's header carries `dh_pub` (same key as msg-1's, since A skipped step_send).
///   * decrypt_from_bytes catch-up branch: when `recv_chain_key.is_none()` and
///     `header.dh_pub.is_some()`, run `step_recv` to derive recv chain from the
///     header's `dh_pub` even if `ratchet_gen` did not advance. B can now decrypt
///     wire-2 standalone.
#[test]
fn responder_catchup_after_send_chain_advance() {
    let mut rng = rand::thread_rng();
    // Build a single handshake-shared pair; we'll reconstruct B from the same
    // SRK + handshake material to model the "wire-1 never reached B" case
    // without ever touching the original B's state with wire-1.
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 1);
    let alice_bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [0u8; 16],
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
    assert_eq!(srk_alice, srk_bob);

    let sid = [9; 5];

    // Build A (responder). We move init_out into A's construction first,
    // but we need init_out.ek_x25519_secret / ek_mlkem_secret + alice_bundle.ik
    // to also build B's session. Capture the things B needs BEFORE giving
    // init_out away — via byte round-trip since ml_kem::SecretKey is not Clone.
    let alice_session_ref = &alice_bundle.ik;
    let ek_x25519_secret_b = init_out.ek_x25519_secret.clone();
    let ek_mlkem_secret_bytes = init_out.ek_mlkem_secret.to_bytes();
    let mut a_responder = Session::responder_after_handshake(srk_alice, sid, &init_out);

    // A: encrypt msg-1. Triggers step_send (recv→send direction switch).
    // We DROP wire-1 unconditionally — model lost-in-transit.
    let _wire1_dropped = a_responder
        .encrypt_to_bytes(b"msg-1 (lost)", &mut rng)
        .expect("A encrypt msg-1");

    // A: encrypt msg-2. step_send SKIPPED (send chain Some from msg-1).
    let pt2 = b"msg-2 must decrypt standalone";
    let wire2 = a_responder
        .encrypt_to_bytes(pt2, &mut rng)
        .expect("A encrypt msg-2");

    // Build a FRESH B from the same handshake material. This is "B receives
    // wire-2 first" — B's recv_chain_key starts None.
    let ek_mlkem_secret_b =
        chencang_core::primitives::ml_kem::SecretKey::from_bytes(ek_mlkem_secret_bytes);
    let mut b_initiator = Session::initiator_after_handshake(
        srk_bob,
        sid,
        alice_session_ref,
        ek_x25519_secret_b,
        ek_mlkem_secret_b,
    );

    // B decrypts wire-2. Pre-fix this returned Internal("no recv chain key").
    // Post-fix: catch-up step_recv fires off wire-2's dh_pub and decrypt succeeds.
    let recovered = b_initiator
        .decrypt_from_bytes(&wire2)
        .expect("B must decrypt wire-2 after fix #202");
    assert_eq!(recovered.as_slice(), pt2);
}

/// Pin the catch-up branch in `decrypt_from_bytes` to fire **at most once**.
///
/// The catch-up gate is `recv_chain_key.is_none() && ratchet_gen >= state.ratchet_gen`.
/// After the first catch-up fires, `recv_chain_key` becomes Some — so any
/// subsequent same-gen frame must take the symmetric-ratchet path, not re-fire
/// catch-up.
///
/// Regression contract: if a future change weakens the `recv_chain_key.is_none()`
/// guard (e.g. accidentally to `||` or to `dh_pub != state.dh_recv_x25519_pub`),
/// this test fails because `step_recv` would reset `recv_counter` and re-derive
/// root_key, desyncing wire-3 / wire-4.
#[test]
fn catchup_step_recv_is_idempotent_across_same_gen_frames() {
    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 1);
    let alice_bundle = PreKeyBundle {
        ik: alice_ik.public(),
        spk: alice_spk.public.clone(),
        opk: Some(alice_opk.public.clone()),
        inviter_username: "alice".to_string(),
        invite_id: [0u8; 16],
        pairing_nonce: [0u8; 16],
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
    assert_eq!(srk_alice, srk_bob);

    let sid = [7; 5];
    let alice_session_ref = &alice_bundle.ik;
    let ek_x25519_secret_b = init_out.ek_x25519_secret.clone();
    let ek_mlkem_secret_bytes = init_out.ek_mlkem_secret.to_bytes();
    let mut a_responder = Session::responder_after_handshake(srk_alice, sid, &init_out);

    // A: drop wire-1 (the only frame that does step_send), then emit wire-2..4.
    // All three carry the same dh_pub since no further step_send fires.
    let _drop1 = a_responder
        .encrypt_to_bytes(b"msg-1 (lost)", &mut rng)
        .expect("A encrypt msg-1");
    let wire2 = a_responder
        .encrypt_to_bytes(b"msg-2", &mut rng)
        .expect("A encrypt msg-2");
    let wire3 = a_responder
        .encrypt_to_bytes(b"msg-3", &mut rng)
        .expect("A encrypt msg-3");
    let wire4 = a_responder
        .encrypt_to_bytes(b"msg-4", &mut rng)
        .expect("A encrypt msg-4");

    let ek_mlkem_secret_b =
        chencang_core::primitives::ml_kem::SecretKey::from_bytes(ek_mlkem_secret_bytes);
    let mut b_initiator = Session::initiator_after_handshake(
        srk_bob,
        sid,
        alice_session_ref,
        ek_x25519_secret_b,
        ek_mlkem_secret_b,
    );

    // wire-2: catch-up fires (recv_chain_key None → Some).
    assert_eq!(
        b_initiator
            .decrypt_from_bytes(&wire2)
            .expect("decrypt msg-2"),
        b"msg-2"
    );
    // wire-3 & wire-4: recv_chain_key already Some; catch-up MUST be skipped.
    // If it incorrectly re-fires, recv_counter resets to 0 and the symmetric
    // chain re-derives from a new root → these decrypts return AeadFailed.
    assert_eq!(
        b_initiator
            .decrypt_from_bytes(&wire3)
            .expect("decrypt msg-3"),
        b"msg-3"
    );
    assert_eq!(
        b_initiator
            .decrypt_from_bytes(&wire4)
            .expect("decrypt msg-4"),
        b"msg-4"
    );
}

/// #142 transactional decrypt: branch (a) — DH+KEM step_recv on a fresh
/// gen advance must NOT commit to receiver state if AEAD subsequently fails.
///
/// Method: spin up two independent (Bob, Alice) handshake pairs sharing only
/// the fixed sid. Alice-rogue (different SRK) emits a wire — Bob's decrypt
/// reaches branch (a) (gen advance), runs step_recv on rogue's dh_pub on
/// tentative state, derives a msg_key, AEAD fails (wrong root). Pre-fix
/// would commit rogue's dh_pub to Bob's state. Post-fix: Bob's state is
/// untouched, and the legitimate Alice-legit's next wire decrypts cleanly.
///
/// If this test regresses, the legit decrypt returns `AeadFailed` because
/// Bob's poisoned recv chain doesn't match Alice-legit's send chain.
#[test]
fn unauthenticated_dh_pub_does_not_poison_state_on_aead_fail() {
    let mut rng = rand::thread_rng();
    // Legit pair (shared srk_legit).
    let (mut bob, mut alice_legit) = setup_session_pair();
    // Rogue pair: independent handshake, different srk, but setup_session_pair
    // uses the same fixed sid [9; 5] — matching what Bob will accept via the
    // sid gate.
    let (_bob_rogue, mut alice_rogue) = setup_session_pair();

    // Alice-rogue emits a wire. From Bob's POV: sid matches, header carries
    // rogue's dh_pub, ratchet_gen=1 (rogue's first send_chain step). Branch
    // (a) fires on tentative state. AEAD fails because msg_key derived from
    // rogue's chain ≠ Bob's expected (Bob's chain derived from srk_legit).
    let wire_rogue = alice_rogue
        .encrypt_to_bytes(b"forged frame", &mut rng)
        .unwrap();
    let rogue_attempt = bob.decrypt_from_bytes(&wire_rogue);
    assert!(rogue_attempt.is_err(), "rogue wire must AEAD-fail");

    // After the failed rogue attempt: legit Alice's first wire MUST decrypt.
    // Pre-fix: Bob's state.dh_recv_x25519_pub was poisoned by rogue, so legit
    // Alice's same-gen wire fails the symmetric chain walk. Post-fix: Bob's
    // state is bit-identical to its pre-rogue snapshot, and this decrypt is
    // exactly equivalent to "Alice's first wire to a fresh post-handshake Bob".
    let wire_legit = alice_legit
        .encrypt_to_bytes(b"hi from real alice", &mut rng)
        .unwrap();
    let recovered = bob
        .decrypt_from_bytes(&wire_legit)
        .expect("legit wire must decrypt after rogue's failed attempt");
    assert_eq!(recovered.as_slice(), b"hi from real alice");
}

/// #142 transactional decrypt: the symmetric ratchet chain walk on a wire
/// whose AEAD subsequently fails must NOT commit `recv_chain_key` /
/// `recv_counter` / `skipped_msg_keys` mutations.
///
/// Method: take three legit wires (Alice → Bob, counter 0/1/2). Bob decrypts
/// wire-0 successfully (advances recv chain). Bob attempts a TAMPERED wire-1
/// (counter=1, ct corrupted) — AEAD fails. Then Bob attempts the legit
/// wire-1 — must succeed. Pre-fix the legit wire-1 fails because the chain
/// already advanced past it on the tampered attempt.
#[test]
fn aead_fail_on_tampered_wire_does_not_advance_recv_chain() {
    let mut rng = rand::thread_rng();
    let (mut bob, mut alice) = setup_session_pair();

    let wire0 = alice.encrypt_to_bytes(b"msg-0", &mut rng).unwrap();
    let wire1_legit = alice.encrypt_to_bytes(b"msg-1", &mut rng).unwrap();

    assert_eq!(bob.decrypt_from_bytes(&wire0).unwrap(), b"msg-0");

    // Tamper wire-1: flip a byte in the last 8 bytes (Poly1305 tag region —
    // guaranteed inside the AEAD-covered ciphertext+tag, not in the header).
    let mut wire1_bad = wire1_legit.clone();
    let last = wire1_bad.len() - 1;
    wire1_bad[last] ^= 0xff;

    let bad_attempt = bob.decrypt_from_bytes(&wire1_bad);
    assert!(bad_attempt.is_err(), "tampered wire-1 must AEAD-fail");

    // Untouched legit wire-1: under the fix, Bob's recv chain is bit-identical
    // to its post-wire-0 state, so this decrypts cleanly. Pre-fix the chain
    // already walked forward (and possibly stored a junk skipped key), so this
    // returns AeadFailed.
    let recovered = bob
        .decrypt_from_bytes(&wire1_legit)
        .expect("legit wire-1 must decrypt after tampered attempt");
    assert_eq!(recovered.as_slice(), b"msg-1");
}

#[test]
fn kem_ratchet_deferred_during_oneway_streak_keeps_decrypting() {
    // Bob (initiator) streams one-way past the KEM threshold without any reply
    // from Alice. Bob's send_chain_key is Some from the handshake onward, so no
    // frame in this streak does a DH step_send (gen never bumps).
    //
    // Pre-fix bug: at the frame where the KEM threshold trips, KEM step_send
    // fires anyway, re-keying Bob's send chain WITHOUT bumping ratchet_gen.
    // Alice's decrypt gates both step_recv calls behind `header.ratchet_gen >
    // local`, so with gen unchanged she skips them and decrypts against the
    // stale chain → AEAD failure that frame and forever after.
    //
    // Post-fix: with no DH step to ride, the KEM ratchet is deferred
    // (kem_pending stays true) and every frame remains a plain symmetric send,
    // so all of them decrypt.
    let (mut bob_session, mut alice_session) = setup_session_pair();
    let mut rng = rand::thread_rng();

    let total = (chencang_core::session::kem_ratchet::KEM_RATCHET_THRESHOLD_MSGS + 10) as usize;
    for i in 0..total {
        let msg = format!("oneway-{i}");
        let ct = bob_session
            .encrypt_to_bytes(msg.as_bytes(), &mut rng)
            .unwrap();
        let pt = alice_session
            .decrypt_from_bytes(&ct)
            .unwrap_or_else(|e| panic!("frame {i} must decrypt, got {e:?}"));
        assert_eq!(pt, msg.as_bytes(), "frame {i} plaintext mismatch");
    }
}

// ---------------------------------------------------------------------------
// Classical suite (suite_id=0x01): X25519 + Ed25519, KEM ratchet gated OFF.
// ---------------------------------------------------------------------------

/// Build a freshly-handshook (Bob initiator, Alice responder) pair of classical
/// `Session` objects sharing the same SRK and sid via the classical X3DH path.
fn setup_classical_session_pair() -> (Session, Session) {
    use chencang_core::handshake::classical_bundle::{
        ClassicalOneTimePreKey, ClassicalPreKeyBundle, ClassicalPublicIdentity,
        ClassicalSignedPreKey,
    };
    use chencang_core::handshake::x3dh::{derive_initiator_classical, derive_responder_classical};

    let mut rng = rand::thread_rng();
    let alice_ik = SecretIdentity::generate(&mut rng);
    let alice_spk = SecretSignedPreKey::generate(&mut rng, &alice_ik, 1);
    let alice_opk = SecretOneTimePreKey::generate(&mut rng, 7);
    let bob_ik = SecretIdentity::generate(&mut rng);

    let nonce = [0x22u8; 16];
    let bundle = ClassicalPreKeyBundle {
        version: 0x01,
        suite_id: 0x01,
        ik: ClassicalPublicIdentity::from_full(&alice_ik.public()),
        spk: ClassicalSignedPreKey::from_full(&alice_spk.public),
        opk: Some(ClassicalOneTimePreKey {
            id: 7,
            x25519: alice_opk.public.opk_x25519.0,
        }),
        pairing_nonce: nonce,
        inviter_username: "alice".to_string(),
    };

    let (srk_bob, init_out) = derive_initiator_classical(&mut rng, &bob_ik, &bundle);
    let srk_alice = derive_responder_classical(
        &alice_ik,
        &alice_spk,
        Some(&alice_opk),
        &init_out.bob_ik_pub,
        &init_out.ek_x25519_pub,
        Some(7),
        &nonce,
    )
    .unwrap()
    .srk;

    assert_eq!(srk_alice, srk_bob, "classical SRK must converge");

    let sid = [9; 5];
    let alice_session =
        Session::responder_after_handshake_classical(srk_alice, sid, init_out.ek_x25519_pub);
    let bob_session = Session::initiator_after_handshake_classical(
        srk_bob,
        sid,
        alice_ik.public().ik_dh_x25519,
        init_out.ek_x25519_secret,
    );
    (bob_session, alice_session)
}

#[test]
fn classical_session_roundtrips_and_never_sets_kem_flag() {
    use chencang_core::wire::FLAG_KEM_RATCHET;
    let mut rng = rand::thread_rng();
    let (mut bob, mut alice) = setup_classical_session_pair();
    for i in 0..60u32 {
        let wire = bob
            .encrypt_to_bytes(format!("m{i}").as_bytes(), &mut rng)
            .unwrap();
        let (hdr, _) = chencang_core::wire::Header::from_bytes(&wire).unwrap();
        assert_eq!(
            hdr.flags & FLAG_KEM_RATCHET,
            0,
            "classical session must never set KEM flag"
        );
        assert_eq!(
            hdr.suite_id, 0x01,
            "classical session wire must be suite 0x01"
        );
        let pt = alice.decrypt_from_bytes(&wire).unwrap();
        assert_eq!(pt, format!("m{i}").as_bytes());
        let back = alice.encrypt_to_bytes(b"ack", &mut rng).unwrap();
        assert_eq!(bob.decrypt_from_bytes(&back).unwrap(), b"ack");
    }
}
