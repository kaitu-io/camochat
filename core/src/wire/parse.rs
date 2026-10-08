//! Wire format 顶层 encode/decode：text z-base32 ↔ (Header, ciphertext+tag)。
//!
//! 这一层在 [`Header::to_bytes`] / [`Header::from_bytes`] 之上提供文本编解码，
//! 是平台 SDK 唯一会直接接触的 wire-format 入口。

use crate::encoding::zbase32;
use crate::error::{Error, Result};
use crate::wire::header::Header;

/// 把 `(Header, ciphertext+tag)` 编码成 z-base32 文本。
///
/// 输入 `ciphertext` 必须是 AEAD 输出的密文 + 16 字节 Poly1305 tag 拼接结果——
/// 本函数不做任何长度或结构检查，全部交给上层会话调用者负责。
///
/// 返回的字符串只包含 z-base32 字符集（`ybndrfg8ejkmcpqxot1uwisza345h769`），
/// 因此天然适合在聊天/输入法等任意文本通道里传输。
#[must_use]
pub fn encode(header: &Header, ciphertext: &[u8]) -> String {
    let mut binary = header.to_bytes();
    binary.extend_from_slice(ciphertext);
    zbase32::encode(&binary)
}

/// 把 z-base32 文本解析回 `(Header, ciphertext+tag)`。
///
/// 解码对空白、引号、换行等非字符集字节有容忍度（见 [`zbase32::decode`]），
/// 因此可以直接喂从聊天软件粘贴出来的、可能被加引号或软回车污染的字符串。
///
/// # Errors
/// - [`Error::Decoding`] 当 z-base32 解码失败
/// - [`Error::InvalidLength`] / [`Error::Decoding`] / [`Error::UnsupportedVersion`]
///   / [`Error::UnsupportedSuite`] 来自底层 [`Header::from_bytes`] 的传递
pub fn decode(text: &str) -> Result<(Header, Vec<u8>)> {
    let binary = zbase32::decode(text).map_err(|e| Error::Decoding(e.to_string()))?;
    let (header, ct_slice) = Header::from_bytes(&binary)?;
    Ok((header, ct_slice.to_vec()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::primitives::{ml_kem, x25519};
    use crate::wire::header::{
        KemRatchetData, FLAG_DH_RATCHET, FLAG_KEM_RATCHET, SUITE_PQ_HYBRID_V1, VERSION_V1,
    };

    /// z-base32 字符集，必须与 [`crate::encoding::zbase32`] 中的一致。
    const ZBASE32_ALPHABET: &[u8; 32] = b"ybndrfg8ejkmcpqxot1uwisza345h769";

    fn minimal_header() -> Header {
        Header {
            version: VERSION_V1,
            suite_id: SUITE_PQ_HYBRID_V1,
            flags: 0,
            sid: [1, 2, 3, 4, 5],
            ratchet_gen: 7,
            counter: 42,
            nonce: [0xab; 24],
            dh_pub: None,
            kem_data: None,
        }
    }

    #[test]
    fn round_trip_text_form() {
        let h = minimal_header();
        let ct = vec![0xff; 80];
        let text = encode(&h, &ct);

        // 编码输出只允许 z-base32 字母表中的字符
        assert!(
            text.bytes().all(|b| ZBASE32_ALPHABET.contains(&b)),
            "encoded text contains non-z-base32 bytes: {text}",
        );

        // 解码回来应当与原 (header, ct) 一致
        let (h2, ct2) = decode(&text).unwrap();
        assert_eq!(h2.version, h.version);
        assert_eq!(h2.suite_id, h.suite_id);
        assert_eq!(h2.flags, h.flags);
        assert_eq!(h2.sid, h.sid);
        assert_eq!(h2.ratchet_gen, h.ratchet_gen);
        assert_eq!(h2.counter, h.counter);
        assert_eq!(h2.nonce, h.nonce);
        assert_eq!(ct2, ct);
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
        let ct = vec![0x11; 128];
        let text = encode(&h, &ct);
        let (h2, ct2) = decode(&text).unwrap();
        assert_eq!(h2.flags, h.flags);
        assert_eq!(h2.dh_pub.as_ref().map(|k| k.0), Some([0x33; 32]));
        assert!(h2.kem_data.is_some());
        assert_eq!(ct2, ct);
    }

    #[test]
    fn decode_handles_whitespace() {
        let h = minimal_header();
        let ct = vec![0u8; 32];
        let text = encode(&h, &ct);

        // 拆开往中间塞引号、空格、换行——chat 软件复制粘贴的常见污染形态
        let polluted = format!("\"{}\"\n\n  {}", &text[..10], &text[10..]);
        let (h2, ct2) = decode(&polluted).unwrap();
        assert_eq!(h2.sid, h.sid);
        assert_eq!(h2.counter, h.counter);
        assert_eq!(ct2, ct);
    }

    #[test]
    fn encoded_text_alphabet_is_zbase32_only() {
        let h = minimal_header();
        let ct = vec![0xde; 64];
        let text = encode(&h, &ct);
        for (i, b) in text.bytes().enumerate() {
            assert!(
                ZBASE32_ALPHABET.contains(&b),
                "byte {b:#x} at offset {i} not in z-base32 alphabet",
            );
        }
    }

    #[test]
    fn decode_garbage_returns_decoding_error() {
        // 长度太短，连 fixed header 都装不下 → Header::from_bytes 报错传上来
        let text = encode(&minimal_header(), &[]);
        let truncated = &text[..text.len() / 4];
        let err = decode(truncated).unwrap_err();
        // 任意 Error 变体都接受——只要不 panic 且确实是 Err
        let _msg = format!("{err}");
    }
}
