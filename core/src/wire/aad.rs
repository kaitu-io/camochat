//! AAD (Additional Authenticated Data) construction.
//!
//! AAD = `magic` || `version` || `suite_id` || `flags` || `sid` || `ratchet_gen` || `counter`
//!
//! 即头部固定 18 字节内容（不含 nonce、不含可选 ratchet 字段）。
//! Nonce 已被 XChaCha20-Poly1305 自身鉴权；
//! 可选 ratchet 数据被 AEAD 加密路径间接保护（位置和长度由 flags 决定）。

use crate::wire::header::{Header, MAGIC};

/// 构造 AAD 字节（18 字节固定长度）。
#[must_use]
pub fn build_aad(h: &Header) -> Vec<u8> {
    let mut aad = Vec::with_capacity(18);
    aad.extend_from_slice(&MAGIC);
    aad.push(h.version);
    aad.push(h.suite_id);
    aad.push(h.flags);
    aad.extend_from_slice(&h.sid);
    aad.extend_from_slice(&h.ratchet_gen.to_be_bytes());
    aad.extend_from_slice(&h.counter.to_be_bytes());
    aad
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::header::{SUITE_PQ_HYBRID_V1, VERSION_V1};

    fn header_with(flags: u8, sid: [u8; 5], ratchet_gen: u32, counter: u32) -> Header {
        Header {
            version: VERSION_V1,
            suite_id: SUITE_PQ_HYBRID_V1,
            flags,
            sid,
            ratchet_gen,
            counter,
            nonce: [0u8; 24],
            dh_pub: None,
            kem_data: None,
        }
    }

    #[test]
    fn aad_length_is_18() {
        let aad = build_aad(&header_with(0, [0; 5], 0, 0));
        assert_eq!(aad.len(), 18);
    }

    #[test]
    fn aad_starts_with_magic() {
        let aad = build_aad(&header_with(0, [0; 5], 0, 0));
        assert_eq!(&aad[0..2], &MAGIC);
    }

    #[test]
    fn aad_changes_when_counter_changes() {
        let base = build_aad(&header_with(0, [0; 5], 0, 0));
        let other = build_aad(&header_with(0, [0; 5], 0, 1));
        assert_ne!(base, other);
    }

    #[test]
    fn aad_changes_when_sid_changes() {
        let base = build_aad(&header_with(0, [0; 5], 0, 0));
        let other = build_aad(&header_with(0, [1, 0, 0, 0, 0], 0, 0));
        assert_ne!(base, other);
    }

    #[test]
    fn aad_changes_when_flags_change() {
        let base = build_aad(&header_with(0, [0; 5], 0, 0));
        let other = build_aad(&header_with(1, [0; 5], 0, 0));
        assert_ne!(base, other);
    }

    #[test]
    fn aad_changes_when_ratchet_gen_changes() {
        let base = build_aad(&header_with(0, [0; 5], 0, 0));
        let other = build_aad(&header_with(0, [0; 5], 99, 0));
        assert_ne!(base, other);
    }

    #[test]
    fn aad_does_not_include_nonce() {
        let mut h1 = header_with(0, [0; 5], 0, 0);
        h1.nonce = [0u8; 24];
        let mut h2 = header_with(0, [0; 5], 0, 0);
        h2.nonce = [0xFFu8; 24];
        assert_eq!(
            build_aad(&h1),
            build_aad(&h2),
            "nonce is XChaCha20-Poly1305's responsibility, not in AAD"
        );
    }
}
