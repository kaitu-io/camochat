//! Wire format 安全性属性测试 — 补充 e2e_session 的端到端覆盖。
//!
//! 关注点：
//! - wire 文本不含会被微信 / QQ 自动识别为链接 / @提及 / # 话题的子串
//! - 不同消息密文不重复（nonce 随机化生效）

use chencang_core::handshake::bundle::PreKeyBundle;
use chencang_core::handshake::pqxdh::{derive_initiator, derive_responder};
use chencang_core::identity::SecretIdentity;
use chencang_core::prekey::{SecretOneTimePreKey, SecretSignedPreKey};
use chencang_core::session::Session;

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

    let sid = [9; 5];
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
fn wire_text_has_no_url_or_mention_patterns() {
    // 关键属性：密文不能被微信 / QQ 自动识别为链接、@、#。
    let mut rng = rand::thread_rng();
    let (mut bob, _alice) = setup_session_pair();

    let plaintexts: &[&[u8]] = &[
        b"hello",
        b"a longer message with various bytes including 0xff",
        &[0xff; 200],
    ];

    for pt in plaintexts {
        let wire = bob.encrypt(pt, &mut rng).unwrap();
        assert!(
            !wire.contains("://"),
            "wire must not contain URL scheme: {wire}"
        );
        assert!(!wire.contains("http"), "wire must not contain 'http'");
        assert!(!wire.contains("www"), "wire must not contain 'www'");
        assert!(!wire.contains('@'), "wire must not contain '@'");
        assert!(!wire.contains('#'), "wire must not contain '#'");
        // z-base32 alphabet is lowercase a-z + 0-9; all chars must be alphanumeric ascii
        for c in wire.chars() {
            assert!(
                c.is_ascii_alphanumeric() && c.is_ascii_lowercase() || c.is_ascii_digit(),
                "non-z-base32 char {c:?} in wire: {wire}"
            );
        }
    }
}

#[test]
fn distinct_messages_produce_distinct_ciphertext() {
    // 两次加密同一明文应产生不同密文（nonce 随机化）。
    let mut rng = rand::thread_rng();
    let (mut bob, _alice) = setup_session_pair();
    let plaintext = b"same content";
    let wire1 = bob.encrypt(plaintext, &mut rng).unwrap();
    let wire2 = bob.encrypt(plaintext, &mut rng).unwrap();
    assert_ne!(
        wire1, wire2,
        "nonce + counter must differentiate identical-plaintext ciphertexts"
    );
}
