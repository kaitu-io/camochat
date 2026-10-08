//! Phase A3.7 Task 20 — encrypt throughput benchmark.
//!
//! Verifies that the `Arc<Mutex<CoreSession>>` wrapper introduced by the
//! facade does not regress throughput. The encrypt path through chencang-core
//! is dominated by ChaCha20-Poly1305 + HKDF; if the Mutex contention or
//! cross-FFI lift/lower were significant we'd see >100µs per call. Spec
//! target: p50 well under 1 ms for a 100-byte plaintext.

use std::sync::Arc;

use chencang_bindings::{
    derive_initiator_handshake, PreKeyBundle, SecretIdentity, SecretOneTimePreKey,
    SecretSignedPreKey, Session,
};
use criterion::{black_box, criterion_group, criterion_main, Criterion, Throughput};

fn setup_bob_session() -> Arc<Session> {
    let bob_ik = Arc::new(SecretIdentity::new());
    let alice_ik = Arc::new(SecretIdentity::new());
    let alice_spk = Arc::new(
        SecretSignedPreKey::new(alice_ik.clone(), 1).expect("SecretSignedPreKey generation"),
    );
    let alice_opk = Arc::new(SecretOneTimePreKey::new(7));

    let bundle = PreKeyBundle {
        ik: alice_ik.public_identity(),
        spk: alice_spk.public_form(),
        opk: Some(alice_opk.public_form()),
        inviter_username: "alice".to_string(),
        invite_id: vec![0x11; 16],
        pairing_nonce: vec![0x22; 16],
    };

    let init_out = derive_initiator_handshake(bob_ik.clone(), bundle).expect("initiator handshake");

    let sid = vec![9, 9, 9, 9, 9];
    Arc::new(
        Session::initiator_after_handshake(
            init_out.session_root_key.clone(),
            sid,
            alice_ik.public_identity(),
            init_out.ek_x25519_secret.clone().unwrap(),
            init_out.ek_mlkem_secret.clone().unwrap(),
        )
        .expect("bob session"),
    )
}

fn bench_encrypt(c: &mut Criterion) {
    let bob_sess = setup_bob_session();

    let mut group = c.benchmark_group("encrypt");
    group.throughput(Throughput::Bytes(100));
    group.bench_function("encrypt_100_bytes", |b| {
        let plaintext = vec![0u8; 100];
        b.iter(|| {
            let wire = bob_sess
                .encrypt(black_box(plaintext.clone()))
                .expect("encrypt");
            black_box(wire);
        });
    });
    group.finish();
}

criterion_group!(benches, bench_encrypt);
criterion_main!(benches);
