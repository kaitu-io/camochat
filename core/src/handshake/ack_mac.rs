//! `ack_mac` 计算与验证。
//!
//! `ack_mac = HMAC-BLAKE2b(SRK, "pair-ack" || sid || pairing_nonce)`
//!
//! 用于 Alice 验证 Bob 真的算出了相同的 SRK。中间环节出错（实现 bug、服务端
//! 篡改公钥）时 SRK 不一致，`ack_mac` 立即穿帮。

use subtle::ConstantTimeEq;

use crate::error::{Error, Result};
use crate::primitives::kdf;

/// `ack_mac` 字节长度。
pub const ACK_MAC_LEN: usize = 32;

/// 构造被 MAC 的字节：`"pair-ack" || sid || pairing_nonce` (8 + 5 + 16 = 29 字节)。
fn ack_message(sid: [u8; 5], pairing_nonce: [u8; 16]) -> Vec<u8> {
    let mut input = Vec::with_capacity(8 + 5 + 16);
    input.extend_from_slice(b"pair-ack");
    input.extend_from_slice(&sid);
    input.extend_from_slice(&pairing_nonce);
    input
}

/// 计算 `ack_mac`。
#[must_use]
pub fn compute_ack_mac(
    srk: &[u8; 32],
    sid: &[u8; 5],
    pairing_nonce: &[u8; 16],
) -> [u8; ACK_MAC_LEN] {
    let input = ack_message(*sid, *pairing_nonce);
    kdf::hmac_blake2b_32(srk, &input)
}

/// 验签 `ack_mac`，使用 constant-time 比较防 timing attack。
///
/// # Errors
/// 当 MAC 不匹配时返回 [`Error::SignatureFailed`]。
pub fn verify_ack_mac(
    expected: &[u8; ACK_MAC_LEN],
    srk: &[u8; 32],
    sid: &[u8; 5],
    pairing_nonce: &[u8; 16],
) -> Result<()> {
    let computed = compute_ack_mac(srk, sid, pairing_nonce);
    if computed.ct_eq(expected).into() {
        Ok(())
    } else {
        Err(Error::SignatureFailed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_passes() {
        let srk = [7u8; 32];
        let sid = [1, 2, 3, 4, 5];
        let nonce = [9u8; 16];
        let mac = compute_ack_mac(&srk, &sid, &nonce);
        verify_ack_mac(&mac, &srk, &sid, &nonce).unwrap();
    }

    #[test]
    fn wrong_srk_fails() {
        let mac = compute_ack_mac(&[1; 32], &[0; 5], &[0; 16]);
        assert!(verify_ack_mac(&mac, &[2; 32], &[0; 5], &[0; 16]).is_err());
    }

    #[test]
    fn wrong_sid_fails() {
        let mac = compute_ack_mac(&[1; 32], &[1; 5], &[0; 16]);
        assert!(verify_ack_mac(&mac, &[1; 32], &[2; 5], &[0; 16]).is_err());
    }

    #[test]
    fn wrong_nonce_fails() {
        let mac = compute_ack_mac(&[1; 32], &[0; 5], &[5; 16]);
        assert!(verify_ack_mac(&mac, &[1; 32], &[0; 5], &[6; 16]).is_err());
    }

    #[test]
    fn deterministic() {
        let mac1 = compute_ack_mac(&[3; 32], &[7; 5], &[11; 16]);
        let mac2 = compute_ack_mac(&[3; 32], &[7; 5], &[11; 16]);
        assert_eq!(mac1, mac2);
    }
}
