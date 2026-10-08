//! Symmetric (chain) ratchet — derives per-message keys from a chain key.
//!
//! 算法（与 Signal Double Ratchet 同构，KDF 选用 HKDF-`BLAKE2b`-512）：
//!
//! ```text
//! msg_key        = HKDF(salt = chain_key, ikm = "", info = "chencang-v1-msg-key")
//! next_chain_key = HKDF(salt = chain_key, ikm = "", info = "chencang-v1-next-chain")
//! ```
//!
//! 由于 `msg_key` 与 `next_chain_key` 通过 HKDF 的不同 `info` 域分隔派生，二者
//! 互不可推。`next_chain_key` 取代当前 `chain_key`，旧 `chain_key` 应立即清零
//! (由调用方负责)，从而获得前向安全（forward secrecy）。
//!
//! 注意：单次单元测试无法证明真正的前向安全性（需要假设 KDF 不可逆），
//! 我们仅断言派生输出彼此互不相同。
//!
//! Domain-separation labels 与 V1 规范保持一致，且不与 `dh_ratchet` /
//! `kem_ratchet` 的 root-key 派生标签冲突。

use crate::primitives::kdf::hkdf_blake2b;

/// `info` for deriving the per-message key.
const INFO_MSG_KEY: &[u8] = b"chencang-v1-msg-key";

/// `info` for deriving the next chain key.
const INFO_NEXT_CHAIN: &[u8] = b"chencang-v1-next-chain";

/// Advance the symmetric (chain) ratchet by one step.
///
/// 输入当前 `chain_key`，返回 `(msg_key, next_chain_key)`。
///
/// - `msg_key` — 用于本条消息的 AEAD key 派生（再经 message-AAD 绑定）。
/// - `next_chain_key` — 取代当前 `chain_key`，调用方应将旧值清零。
///
/// 实现使用 HKDF-`BLAKE2b`-512，通过两个不同的 `info` 域分隔派生，确保两输出
/// 在 KDF 安全假设下彼此互不可推。
#[must_use]
pub fn advance(chain_key: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let msg_key = hkdf_blake2b(chain_key, b"", INFO_MSG_KEY);
    let next_chain_key = hkdf_blake2b(chain_key, b"", INFO_NEXT_CHAIN);
    (msg_key, next_chain_key)
}

#[cfg(test)]
mod tests {
    use super::advance;

    /// 单次 advance：`msg_key` 与 `next_chain_key` 必须不同（不同 `info` 域分隔）。
    #[test]
    fn advance_produces_distinct_outputs() {
        let ck = [0x11u8; 32];
        let (msg_key, next_ck) = advance(&ck);
        assert_ne!(msg_key, next_ck, "msg_key must differ from next_chain_key");
        assert_ne!(msg_key, ck, "msg_key must differ from input chain_key");
        assert_ne!(
            next_ck, ck,
            "next_chain_key must differ from input chain_key"
        );
    }

    /// 两次连续 advance 产生 4 个互不相同的 32-byte key。
    #[test]
    fn two_consecutive_advances_yield_distinct_keys() {
        let ck0 = [0x42u8; 32];
        let (mk0, ck1) = advance(&ck0);
        let (mk1, ck2) = advance(&ck1);

        // chain_keys 必须前向推进，互不相同
        assert_ne!(ck0, ck1, "ck1 must differ from ck0");
        assert_ne!(ck1, ck2, "ck2 must differ from ck1");
        assert_ne!(ck0, ck2, "ck2 must differ from ck0");

        // msg_keys 必须互不相同
        assert_ne!(mk0, mk1, "consecutive msg_keys must differ");

        // msg_key 不应等于任何 chain_key
        assert_ne!(mk0, ck1);
        assert_ne!(mk1, ck2);
    }

    /// 同输入必须确定性产出相同 `(msg_key, next_chain_key)`。
    #[test]
    fn advance_is_deterministic() {
        let ck = [0xa5u8; 32];
        let (mk_a, next_a) = advance(&ck);
        let (mk_b, next_b) = advance(&ck);
        assert_eq!(mk_a, mk_b, "msg_key must be deterministic");
        assert_eq!(next_a, next_b, "next_chain_key must be deterministic");
    }

    /// 不同输入产出不同输出（基本扩散性 sanity check）。
    #[test]
    fn distinct_inputs_yield_distinct_outputs() {
        let ck1 = [0x00u8; 32];
        let mut ck2 = [0x00u8; 32];
        ck2[0] = 0x01;
        let (mk1, next1) = advance(&ck1);
        let (mk2, next2) = advance(&ck2);
        assert_ne!(mk1, mk2);
        assert_ne!(next1, next2);
    }

    /// 前向安全（forward-secrecy）的弱形式断言：从 `chain_key_1` 不能简单
    /// 等回到 `chain_key_0`。真正的 FS 由 KDF 的单向性保证，单元测试只能
    /// 检测 `chain_key` 确实推进（非恒等映射）。
    #[test]
    fn chain_advance_is_not_identity() {
        // 多组初值都不应出现 ck1 == ck0 的退化情况。
        for byte in [0x00u8, 0x55, 0xaa, 0xff] {
            let ck0 = [byte; 32];
            let (_, ck1) = advance(&ck0);
            assert_ne!(
                ck0, ck1,
                "chain_key must advance for seed byte 0x{byte:02x}"
            );
        }
    }
}
