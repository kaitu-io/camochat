//! Wire format 二进制头部布局。
//!
//! See protocol spec §9.2.
//!
//! ```text
//! ┌──────┬──────┬─────────────────┬────────────────────────┐
//! │ off  │ len  │ field           │ description             │
//! ├──────┼──────┼─────────────────┼────────────────────────┤
//! │   0  │  2 B │ magic           │ 0xCC 0xC8               │
//! │   2  │  1 B │ version         │ 0x01 = V1               │
//! │   3  │  1 B │ suite_id        │ 0x02 = PQ-hybrid        │
//! │   4  │  1 B │ flags           │ bit 0: DH ratchet pub   │
//! │      │      │                 │ bit 1: KEM ratchet data │
//! │   5  │  5 B │ sid             │ 40-bit SessionId        │
//! │  10  │  4 B │ ratchet_gen     │ Big-endian u32          │
//! │  14  │  4 B │ counter         │ Big-endian u32          │
//! │  18  │ 24 B │ nonce           │ XChaCha20 nonce         │
//! │  42  │ 0/32 │ dh_pub          │ only if bit 0           │
//! │   ?  │ 0/2272 │ kem_data      │ only if bit 1           │
//! └──────┴──────┴─────────────────┴────────────────────────┘
//! ```

use crate::error::{Error, Result};
use crate::primitives::{ml_kem, x25519};

/// Magic 2 字节标识陈仓密文。
pub const MAGIC: [u8; 2] = [0xCC, 0xC8];

/// V1 协议版本。
pub const VERSION_V1: u8 = 0x01;

/// V1 经典算法套件（X25519 + Ed25519，无后量子棘轮）。
pub const SUITE_CLASSICAL_V1: u8 = 0x01;

/// V1 PQ-hybrid 算法套件。
pub const SUITE_PQ_HYBRID_V1: u8 = 0x02;

/// 固定头部大小（无可选字段）。
pub const FIXED_HEADER_LEN: usize = 42;

/// Flag: 本条消息含 DH ratchet 新 pub。
pub const FLAG_DH_RATCHET: u8 = 0b0000_0001;
/// Flag: 本条消息含 KEM ratchet pub + ct。
pub const FLAG_KEM_RATCHET: u8 = 0b0000_0010;

/// KEM ratchet 携带的数据。
#[derive(Clone)]
pub struct KemRatchetData {
    /// 新一代 ML-KEM-768 公钥。
    pub new_kem_pub: ml_kem::PublicKey,
    /// ML-KEM ciphertext 封装的共享 secret。
    pub kem_ct: [u8; ml_kem::CIPHERTEXT_LEN],
}

impl std::fmt::Debug for KemRatchetData {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KemRatchetData").finish_non_exhaustive()
    }
}

/// 消息头部。
#[derive(Clone, Debug)]
pub struct Header {
    /// 协议版本（V1 = 0x01）。
    pub version: u8,
    /// 算法套件 ID（V1 = 0x02）。
    pub suite_id: u8,
    /// 位标记。
    pub flags: u8,
    /// 40-bit `SessionId`。
    pub sid: [u8; 5],
    /// DH ratchet 代际编号。
    pub ratchet_gen: u32,
    /// 当前链消息计数器。
    pub counter: u32,
    /// XChaCha20-Poly1305 nonce (24 字节)。
    pub nonce: [u8; 24],
    /// DH ratchet 新公钥（仅当 flag bit 0 设置）。
    pub dh_pub: Option<x25519::PublicKey32>,
    /// KEM ratchet 材料（仅当 flag bit 1 设置）。
    pub kem_data: Option<KemRatchetData>,
}

impl Header {
    /// 序列化为字节。
    #[must_use]
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(
            FIXED_HEADER_LEN + 32 + ml_kem::PUBLIC_KEY_LEN + ml_kem::CIPHERTEXT_LEN,
        );
        out.extend_from_slice(&MAGIC);
        out.push(self.version);
        out.push(self.suite_id);
        out.push(self.flags);
        out.extend_from_slice(&self.sid);
        out.extend_from_slice(&self.ratchet_gen.to_be_bytes());
        out.extend_from_slice(&self.counter.to_be_bytes());
        out.extend_from_slice(&self.nonce);
        if let Some(dh) = &self.dh_pub {
            out.extend_from_slice(&dh.0);
        }
        if let Some(kem) = &self.kem_data {
            out.extend_from_slice(&kem.new_kem_pub.0);
            out.extend_from_slice(&kem.kem_ct);
        }
        out
    }

    /// 从字节流解析头部。返回 `(header, ciphertext_bytes_remaining)`。
    ///
    /// # Errors
    /// - `Error::InvalidLength` 当输入小于固定头长
    /// - `Error::Decoding` 当 magic / 保留位 / flag 不一致
    /// - `Error::UnsupportedVersion` / `UnsupportedSuite`
    pub fn from_bytes(input: &[u8]) -> Result<(Self, &[u8])> {
        if input.len() < FIXED_HEADER_LEN {
            return Err(Error::InvalidLength {
                expected: FIXED_HEADER_LEN,
                got: input.len(),
            });
        }
        if input[0..2] != MAGIC {
            return Err(Error::Decoding("missing magic bytes".into()));
        }
        let version = input[2];
        if version != VERSION_V1 {
            return Err(Error::UnsupportedVersion(version));
        }
        let suite_id = input[3];
        if suite_id != SUITE_CLASSICAL_V1 && suite_id != SUITE_PQ_HYBRID_V1 {
            return Err(Error::UnsupportedSuite(suite_id));
        }
        let flags = input[4];
        // bit 1 set requires bit 0 set
        if (flags & FLAG_KEM_RATCHET) != 0 && (flags & FLAG_DH_RATCHET) == 0 {
            return Err(Error::Decoding(
                "KEM ratchet flag set without DH ratchet flag".into(),
            ));
        }
        // reserved bits must be zero
        if flags & 0b1111_1100 != 0 {
            return Err(Error::Decoding("reserved flag bits non-zero".into()));
        }

        let mut sid = [0u8; 5];
        sid.copy_from_slice(&input[5..10]);
        let mut ratchet_gen_bytes = [0u8; 4];
        ratchet_gen_bytes.copy_from_slice(&input[10..14]);
        let ratchet_gen = u32::from_be_bytes(ratchet_gen_bytes);
        let mut counter_bytes = [0u8; 4];
        counter_bytes.copy_from_slice(&input[14..18]);
        let counter = u32::from_be_bytes(counter_bytes);
        let mut nonce = [0u8; 24];
        nonce.copy_from_slice(&input[18..42]);

        let mut offset = FIXED_HEADER_LEN;
        let dh_pub = if flags & FLAG_DH_RATCHET != 0 {
            if input.len() < offset + 32 {
                return Err(Error::Decoding("missing DH ratchet pubkey".into()));
            }
            let mut k = [0u8; 32];
            k.copy_from_slice(&input[offset..offset + 32]);
            offset += 32;
            Some(x25519::PublicKey32(k))
        } else {
            None
        };

        let kem_data = if flags & FLAG_KEM_RATCHET != 0 {
            let needed = ml_kem::PUBLIC_KEY_LEN + ml_kem::CIPHERTEXT_LEN;
            if input.len() < offset + needed {
                return Err(Error::Decoding("missing KEM ratchet data".into()));
            }
            let mut pk = [0u8; ml_kem::PUBLIC_KEY_LEN];
            pk.copy_from_slice(&input[offset..offset + ml_kem::PUBLIC_KEY_LEN]);
            offset += ml_kem::PUBLIC_KEY_LEN;
            let mut ct = [0u8; ml_kem::CIPHERTEXT_LEN];
            ct.copy_from_slice(&input[offset..offset + ml_kem::CIPHERTEXT_LEN]);
            offset += ml_kem::CIPHERTEXT_LEN;
            Some(KemRatchetData {
                new_kem_pub: ml_kem::PublicKey(pk),
                kem_ct: ct,
            })
        } else {
            None
        };

        Ok((
            Header {
                version,
                suite_id,
                flags,
                sid,
                ratchet_gen,
                counter,
                nonce,
                dh_pub,
                kem_data,
            },
            &input[offset..],
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal_header() -> Header {
        Header {
            version: VERSION_V1,
            suite_id: SUITE_PQ_HYBRID_V1,
            flags: 0,
            sid: [1, 2, 3, 4, 5],
            ratchet_gen: 42,
            counter: 99,
            nonce: [0xaa; 24],
            dh_pub: None,
            kem_data: None,
        }
    }

    #[test]
    fn round_trip_minimal() {
        let h = minimal_header();
        let bytes = h.to_bytes();
        assert_eq!(bytes.len(), FIXED_HEADER_LEN);
        let (parsed, rest) = Header::from_bytes(&bytes).unwrap();
        assert_eq!(parsed.ratchet_gen, 42);
        assert_eq!(parsed.counter, 99);
        assert_eq!(parsed.sid, [1, 2, 3, 4, 5]);
        assert_eq!(rest, []);
    }

    #[test]
    fn round_trip_with_dh_ratchet() {
        let mut h = minimal_header();
        h.flags = FLAG_DH_RATCHET;
        h.dh_pub = Some(x25519::PublicKey32([0x77; 32]));
        let bytes = h.to_bytes();
        assert_eq!(bytes.len(), FIXED_HEADER_LEN + 32);
        let (parsed, rest) = Header::from_bytes(&bytes).unwrap();
        assert_eq!(parsed.dh_pub, Some(x25519::PublicKey32([0x77; 32])));
        assert_eq!(rest, []);
    }

    #[test]
    fn reject_kem_without_dh_flag() {
        let mut h = minimal_header();
        h.flags = FLAG_KEM_RATCHET;
        let bytes = h.to_bytes();
        let r = Header::from_bytes(&bytes);
        assert!(r.is_err(), "should reject bit 1 without bit 0");
    }

    #[test]
    fn reject_wrong_magic() {
        let mut bytes = vec![0xFF, 0xFF];
        bytes.extend(vec![0u8; FIXED_HEADER_LEN - 2]);
        assert!(Header::from_bytes(&bytes).is_err());
    }

    #[test]
    fn reject_wrong_version() {
        let h = minimal_header();
        let mut bytes = h.to_bytes();
        bytes[2] = 0x99;
        assert!(matches!(
            Header::from_bytes(&bytes),
            Err(Error::UnsupportedVersion(_))
        ));
    }

    #[test]
    fn reject_wrong_suite() {
        let h = minimal_header();
        let mut bytes = h.to_bytes();
        bytes[3] = 0x99;
        assert!(matches!(
            Header::from_bytes(&bytes),
            Err(Error::UnsupportedSuite(_))
        ));
    }

    #[test]
    fn reject_reserved_bits_set() {
        let h = minimal_header();
        let mut bytes = h.to_bytes();
        bytes[4] = 0b1000_0000;
        assert!(Header::from_bytes(&bytes).is_err());
    }

    #[test]
    fn reject_truncated() {
        let h = minimal_header();
        let bytes = h.to_bytes();
        let truncated = &bytes[..FIXED_HEADER_LEN - 1];
        assert!(Header::from_bytes(truncated).is_err());
    }

    #[test]
    fn round_trip_with_both_ratchet_flags() {
        let mut h = minimal_header();
        h.flags = FLAG_DH_RATCHET | FLAG_KEM_RATCHET;
        h.dh_pub = Some(x25519::PublicKey32([0x33; 32]));
        h.kem_data = Some(KemRatchetData {
            new_kem_pub: ml_kem::PublicKey([0x44; ml_kem::PUBLIC_KEY_LEN]),
            kem_ct: [0x55; ml_kem::CIPHERTEXT_LEN],
        });
        let bytes = h.to_bytes();
        let expected_len = FIXED_HEADER_LEN + 32 + ml_kem::PUBLIC_KEY_LEN + ml_kem::CIPHERTEXT_LEN;
        assert_eq!(bytes.len(), expected_len);
        let (parsed, rest) = Header::from_bytes(&bytes).unwrap();
        assert!(parsed.dh_pub.is_some());
        assert!(parsed.kem_data.is_some());
        assert_eq!(rest, []);
    }
}
