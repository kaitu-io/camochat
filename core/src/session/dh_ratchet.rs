//! DH ratchet step。每次方向切换时（即将发送消息但上一条是接收来的）触发。

use rand_core::{CryptoRng, RngCore};

use crate::error::{Error, Result};
use crate::primitives::{kdf, x25519};
use crate::session::state::SessionState;

const INFO_DH_RATCHET: &[u8] = b"chencang-v1-dh-ratchet";

/// 发送方执行 DH ratchet：生成新 `dh_send`，与已知 `dh_recv_pub` 做 DH，
/// 更新 `root_key` + `send_chain_key`。
///
/// # Panics
/// 当 `state.dh_recv_x25519_pub` 为 None 时 panic（协议层 bug，不该发生）。
pub fn step_send<R: CryptoRng + RngCore>(rng: &mut R, state: &mut SessionState) {
    let recv_pub = state
        .dh_recv_x25519_pub
        .as_ref()
        .expect("step_send called with no recv DH pub — protocol bug");
    let new_dh = x25519::SecretKey::random(rng);
    let shared = new_dh.diffie_hellman(recv_pub);

    let root_and_chain = kdf::hkdf_blake2b_64(&state.root_key, shared.as_bytes(), INFO_DH_RATCHET);
    state.root_key.copy_from_slice(&root_and_chain[0..32]);
    let mut chain = [0u8; 32];
    chain.copy_from_slice(&root_and_chain[32..64]);
    state.send_chain_key = Some(chain);
    state.dh_send_x25519 = Some(new_dh);
    state.ratchet_gen = state.ratchet_gen.wrapping_add(1);
    state.send_counter = 0;
}

/// 接收方执行 DH ratchet：用收到的新 `dh_pub` 与自己的 `dh_send` 做 DH，
/// 更新 root + `recv_chain`。
///
/// # Errors
/// 当 `state.dh_send_x25519` 为 None 时返回 `Error::Internal`。
pub fn step_recv(state: &mut SessionState, new_recv_pub: x25519::PublicKey32) -> Result<()> {
    let send_sk = state
        .dh_send_x25519
        .as_ref()
        .ok_or_else(|| Error::Internal("step_recv: no send DH key".into()))?;
    let shared = send_sk.diffie_hellman(&new_recv_pub);
    let root_and_chain = kdf::hkdf_blake2b_64(&state.root_key, shared.as_bytes(), INFO_DH_RATCHET);
    state.root_key.copy_from_slice(&root_and_chain[0..32]);
    let mut chain = [0u8; 32];
    chain.copy_from_slice(&root_and_chain[32..64]);
    state.recv_chain_key = Some(chain);
    state.dh_recv_x25519_pub = Some(new_recv_pub);
    state.recv_counter = 0;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn step_send_advances_ratchet_gen_and_populates_send_chain() {
        let mut rng = rand::thread_rng();
        let mut state = SessionState::from_srk([5; 32], [1, 2, 3, 4, 5]);
        // Need recv pub populated so step_send has something to DH against.
        let peer_sk = x25519::SecretKey::random(&mut rng);
        state.dh_recv_x25519_pub = Some(peer_sk.public());

        let root_before = state.root_key;
        let gen_before = state.ratchet_gen;
        step_send(&mut rng, &mut state);

        assert_ne!(state.root_key, root_before, "root_key must advance");
        assert!(state.send_chain_key.is_some());
        assert!(state.dh_send_x25519.is_some());
        assert_eq!(state.ratchet_gen, gen_before.wrapping_add(1));
        assert_eq!(state.send_counter, 0);
    }

    #[test]
    fn step_recv_populates_recv_chain() {
        let mut rng = rand::thread_rng();
        let mut state = SessionState::from_srk([5; 32], [1, 2, 3, 4, 5]);
        // Need send DH key for step_recv to do DH.
        state.dh_send_x25519 = Some(x25519::SecretKey::random(&mut rng));

        let new_recv_pub = x25519::SecretKey::random(&mut rng).public();
        step_recv(&mut state, new_recv_pub).unwrap();

        assert!(state.recv_chain_key.is_some());
        assert_eq!(
            state.dh_recv_x25519_pub.as_ref().map(|p| p.0),
            Some(new_recv_pub.0)
        );
        assert_eq!(state.recv_counter, 0);
    }

    #[test]
    fn step_recv_without_send_key_errors() {
        let mut rng = rand::thread_rng();
        let mut state = SessionState::from_srk([0; 32], [0; 5]);
        let new_recv_pub = x25519::SecretKey::random(&mut rng).public();
        let result = step_recv(&mut state, new_recv_pub);
        assert!(result.is_err());
    }
}
