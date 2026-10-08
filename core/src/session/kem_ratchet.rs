//! KEM ratchet：搭乘下一次 DH ratchet 触发。
//!
//! 不主动发空消息 — 等到用户发真实消息时，DH ratchet step 一起携带 KEM 材料。

use rand_core::{CryptoRng, RngCore};

use crate::error::Result;
use crate::primitives::{kdf, ml_kem};
use crate::session::state::{now_unix, SessionState};

/// 50 条消息阈值。
pub const KEM_RATCHET_THRESHOLD_MSGS: u32 = 50;
/// 7 天阈值（秒）。
pub const KEM_RATCHET_THRESHOLD_SECS: u64 = 7 * 24 * 3600;

const INFO_KEM_RATCHET: &[u8] = b"chencang-v1-kem-ratchet";

/// 是否应该设置 `kem_pending = true`（调度判断，不修改 state）。
#[must_use]
pub fn should_set_pending(state: &SessionState, now_unix: u64) -> bool {
    state.kem_messages_since_ratchet >= KEM_RATCHET_THRESHOLD_MSGS
        || now_unix.saturating_sub(state.kem_ratchet_last_unix) >= KEM_RATCHET_THRESHOLD_SECS
}

/// 在 DH ratchet 时同步执行 KEM ratchet：生成新 KEM keypair + encap 对方 KEM pub.
/// 返回 (`new_kem_pub`, `kem_ct`) 用于 wire 携带。
///
/// # Panics
/// 当 `state.kem_recv_mlkem_pub` 为 None 时 panic（协议层 bug）。
#[must_use]
pub fn step_send<R: CryptoRng + RngCore>(
    rng: &mut R,
    state: &mut SessionState,
) -> (ml_kem::PublicKey, Vec<u8>) {
    let recv_pub = state
        .kem_recv_mlkem_pub
        .as_ref()
        .expect("KEM ratchet step_send without recv KEM pub — protocol bug");
    let (new_pk, new_secret) = ml_kem::generate_keypair(rng);
    let (ct, ss) = ml_kem::encapsulate(recv_pub, rng);

    let root_and_chain = kdf::hkdf_blake2b_64(&state.root_key, ss.as_bytes(), INFO_KEM_RATCHET);
    state.root_key.copy_from_slice(&root_and_chain[0..32]);
    let mut chain = [0u8; 32];
    chain.copy_from_slice(&root_and_chain[32..64]);
    state.send_chain_key = Some(chain);

    state.kem_send_mlkem = Some(new_secret);
    state.kem_messages_since_ratchet = 0;
    state.kem_ratchet_last_unix = now_unix();
    state.kem_pending = false;

    (new_pk, ct)
}

/// 接收方处理 KEM ratchet 数据：用自己的 KEM 私钥 decap，更新 root + recv chain.
///
/// # Errors
/// 当 `state.kem_send_mlkem` 缺失，或 decap 失败时返回错误。
pub fn step_recv(
    state: &mut SessionState,
    new_recv_kem_pub: ml_kem::PublicKey,
    kem_ct: &[u8],
) -> Result<()> {
    let send_sk = state
        .kem_send_mlkem
        .as_ref()
        .ok_or_else(|| crate::error::Error::Internal("step_recv: no send KEM key".into()))?;
    let ss = ml_kem::decapsulate(send_sk, kem_ct)?;
    let root_and_chain = kdf::hkdf_blake2b_64(&state.root_key, ss.as_bytes(), INFO_KEM_RATCHET);
    state.root_key.copy_from_slice(&root_and_chain[0..32]);
    let mut chain = [0u8; 32];
    chain.copy_from_slice(&root_and_chain[32..64]);
    state.recv_chain_key = Some(chain);
    state.kem_recv_mlkem_pub = Some(new_recv_kem_pub);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh_state() -> SessionState {
        SessionState::from_srk([0; 32], [0; 5])
    }

    #[test]
    fn should_set_pending_at_50_messages() {
        let mut state = fresh_state();
        state.kem_messages_since_ratchet = 49;
        assert!(!should_set_pending(&state, state.kem_ratchet_last_unix));
        state.kem_messages_since_ratchet = 50;
        assert!(should_set_pending(&state, state.kem_ratchet_last_unix));
    }

    #[test]
    fn should_set_pending_at_7_days() {
        let mut state = fresh_state();
        state.kem_ratchet_last_unix = 1000;
        state.kem_messages_since_ratchet = 0;
        let secs = KEM_RATCHET_THRESHOLD_SECS;
        assert!(!should_set_pending(&state, 1000 + secs - 1));
        assert!(should_set_pending(&state, 1000 + secs));
    }

    #[test]
    fn step_send_advances_root_and_resets_kem_state() {
        let mut rng = rand::thread_rng();
        let mut state = fresh_state();
        // Need recv KEM pub for step_send.
        let (peer_pk, _peer_sk) = ml_kem::generate_keypair(&mut rng);
        state.kem_recv_mlkem_pub = Some(peer_pk);
        state.kem_messages_since_ratchet = 99;
        state.kem_pending = true;

        let root_before = state.root_key;
        let (_new_pub, _ct) = step_send(&mut rng, &mut state);

        assert_ne!(state.root_key, root_before);
        assert!(state.send_chain_key.is_some());
        assert_eq!(state.kem_messages_since_ratchet, 0);
        assert!(!state.kem_pending);
        assert!(state.kem_send_mlkem.is_some());
    }

    #[test]
    fn step_send_and_step_recv_converge_roots() {
        // Alice's step_send produces (pub, ct). Bob uses his prior KEM send sk
        // to decap → both reach same root advancement.
        let mut rng = rand::thread_rng();

        // Bob has a KEM keypair he sent earlier.
        let (bob_kem_pub, bob_kem_sk) = ml_kem::generate_keypair(&mut rng);

        // Alice's state — knows Bob's kem pub as recv target.
        let mut alice = fresh_state();
        alice.kem_recv_mlkem_pub = Some(bob_kem_pub.clone());
        let initial_root = alice.root_key;

        // Alice does step_send → returns new_pk + ct.
        let (new_pk, ct) = step_send(&mut rng, &mut alice);
        let alice_root = alice.root_key;
        assert_ne!(alice_root, initial_root);

        // Bob's state — knows Alice has sent something with old root, holds his
        // send sk.
        let mut bob = fresh_state();
        bob.root_key = initial_root;
        bob.kem_send_mlkem = Some(bob_kem_sk);

        // Bob does step_recv with (new_pk, ct).
        step_recv(&mut bob, new_pk, &ct).unwrap();

        // Roots must converge.
        assert_eq!(
            alice.root_key, bob.root_key,
            "Alice and Bob roots must converge after KEM ratchet"
        );
    }
}
