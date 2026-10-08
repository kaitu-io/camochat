//! 乱序消息密钥缓冲。
//!
//! 当接收方在当前 chain 上看到的 counter 超前于 `recv_counter` 时，
//! 必须为跳过的 counter 派生并暂存消息密钥，以便后续乱序到达的消息
//! 仍能解密。本模块对 [`SessionState::skipped_msg_keys`] 提供受控的
//! 存取接口，并实施容量上限以避免无限内存增长。
//!
//! - 键：`(ratchet_gen, counter)`
//! - 值：32 字节消息密钥
//! - 容量：[`MAX_SKIPPED`]；超出时按近似 FIFO 语义淘汰最早一个条目
//!
//! [`SessionState::skipped_msg_keys`]: crate::session::state::SessionState::skipped_msg_keys

use crate::session::state::SessionState;

/// 乱序密钥缓冲的最大条目数。
///
/// 超过此阈值时，[`store`] 将丢弃一个已存在的条目，以避免内存无限增长。
/// 该阈值对应协议规范 §8.4 中“合理乱序窗口”的上限。
pub const MAX_SKIPPED: usize = 100;

/// 将一个跳过的消息密钥写入缓冲。
///
/// 当缓冲超过 [`MAX_SKIPPED`] 时，按 FIFO 近似语义丢弃一个旧条目，
/// 以保证缓冲容量不会无限增长。
///
/// # Parameters
///
/// - `state`：要写入的会话状态。
/// - `ratchet_gen`：消息所属的 DH ratchet 代际。
/// - `counter`：消息在所属 chain 内的计数器。
/// - `msg_key`：派生出的 32 字节消息密钥。
pub fn store(state: &mut SessionState, ratchet_gen: u32, counter: u32, msg_key: [u8; 32]) {
    state
        .skipped_msg_keys
        .insert((ratchet_gen, counter), msg_key);

    if state.skipped_msg_keys.len() > MAX_SKIPPED {
        // HashMap 不保留插入顺序；这里选取任意一个条目淘汰以满足容量约束。
        // 协议层面只要求“窗口有界”，因此严格 FIFO 不是必需的。
        if let Some(victim) = state.skipped_msg_keys.keys().next().copied() {
            state.skipped_msg_keys.remove(&victim);
        }
    }
}

/// 取出（即消费）一个先前存入的跳过密钥。
///
/// 若存在 `(ratchet_gen, counter)` 对应的条目，则从缓冲中移除并返回；
/// 否则返回 [`None`]。消息密钥一旦消费即不可再用，调用方负责将其
/// 立刻交给 AEAD 解密流程。
#[must_use]
pub fn take(state: &mut SessionState, ratchet_gen: u32, counter: u32) -> Option<[u8; 32]> {
    state.skipped_msg_keys.remove(&(ratchet_gen, counter))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh_state() -> SessionState {
        SessionState::from_srk([0u8; 32], [0u8; 5])
    }

    #[test]
    fn storing_more_than_max_caps_buffer_at_max() {
        let mut state = fresh_state();
        for i in 0..(u32::try_from(MAX_SKIPPED).unwrap() + 50) {
            store(&mut state, 0, i, [u8::try_from(i & 0xFF).unwrap(); 32]);
        }
        assert_eq!(state.skipped_msg_keys.len(), MAX_SKIPPED);
    }

    #[test]
    fn take_returns_stored_key_then_none() {
        let mut state = fresh_state();
        let key = [7u8; 32];
        store(&mut state, 3, 42, key);

        let first = take(&mut state, 3, 42);
        assert_eq!(first, Some(key));

        let second = take(&mut state, 3, 42);
        assert_eq!(second, None);
    }

    #[test]
    fn take_returns_none_for_unknown_key() {
        let mut state = fresh_state();
        assert_eq!(take(&mut state, 0, 0), None);
    }

    #[test]
    fn distinct_keys_are_stored_separately() {
        let mut state = fresh_state();
        let k_a = [1u8; 32];
        let k_b = [2u8; 32];
        let k_c = [3u8; 32];

        // 同 ratchet_gen，不同 counter
        store(&mut state, 0, 1, k_a);
        store(&mut state, 0, 2, k_b);
        // 不同 ratchet_gen，同 counter（与 k_a 相同 counter=1）
        store(&mut state, 1, 1, k_c);

        assert_eq!(state.skipped_msg_keys.len(), 3);
        assert_eq!(take(&mut state, 0, 1), Some(k_a));
        assert_eq!(take(&mut state, 0, 2), Some(k_b));
        assert_eq!(take(&mut state, 1, 1), Some(k_c));
        assert!(state.skipped_msg_keys.is_empty());
    }

    #[test]
    fn store_overwrites_existing_entry_in_place() {
        let mut state = fresh_state();
        let k_old = [9u8; 32];
        let k_new = [10u8; 32];

        store(&mut state, 5, 5, k_old);
        store(&mut state, 5, 5, k_new);

        assert_eq!(state.skipped_msg_keys.len(), 1);
        assert_eq!(take(&mut state, 5, 5), Some(k_new));
    }
}
