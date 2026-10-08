//! z-base32 编码 / 解码。
//!
//! - 字符集（Phil Zimmermann）：`ybndrfg8ejkmcpqxot1uwisza345h769`
//! - 5 bits per character
//! - 解码时：忽略所有非字符集字符（空白、引号、不可见字符等），大写自动转小写

const ALPHABET: &[u8; 32] = b"ybndrfg8ejkmcpqxot1uwisza345h769";

/// 字节流 → z-base32 文本。
#[must_use]
pub fn encode(input: &[u8]) -> String {
    zbase32::encode_full_bytes(input)
}

/// z-base32 文本 → 字节流。
/// 自动忽略所有非字符集字符；自动转小写。
///
/// # Errors
/// 输入只含合法字符但语义无效时（应当极少）返回 `DecodeError::Invalid`。
pub fn decode(input: &str) -> Result<Vec<u8>, DecodeError> {
    let filtered: String = input
        .chars()
        .filter_map(|c| {
            let lc = c.to_ascii_lowercase();
            if ALPHABET.contains(&(lc as u8)) {
                Some(lc)
            } else {
                None
            }
        })
        .collect();
    zbase32::decode_full_bytes_str(&filtered).map_err(|e| DecodeError::Invalid(format!("{e:?}")))
}

/// 解码错误。
#[derive(Debug)]
pub enum DecodeError {
    /// 解码失败原因（来自 zbase32 crate 的内部错误）。
    Invalid(String),
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DecodeError::Invalid(s) => write!(f, "z-base32 decode error: {s}"),
        }
    }
}

impl std::error::Error for DecodeError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_basic() {
        let data = b"hello chencang";
        let encoded = encode(data);
        let decoded = decode(&encoded).unwrap();
        assert_eq!(decoded.as_slice(), data);
    }

    #[test]
    fn ignore_whitespace_during_decode() {
        let data = b"some bytes";
        let encoded = encode(data);
        let with_ws = format!("  {}\n {} ", &encoded[..5], &encoded[5..]);
        let decoded = decode(&with_ws).unwrap();
        assert_eq!(decoded.as_slice(), data);
    }

    #[test]
    fn uppercase_decoded() {
        let data = b"chencang test";
        let encoded = encode(data).to_uppercase();
        let decoded = decode(&encoded).unwrap();
        assert_eq!(decoded.as_slice(), data);
    }

    #[test]
    fn decode_filters_invalid_chars() {
        let data = b"data";
        let encoded = encode(data);
        let polluted = format!("\"{encoded}\"  $%^");
        let decoded = decode(&polluted).unwrap();
        assert_eq!(decoded.as_slice(), data);
    }

    #[test]
    fn encoded_uses_only_alphabet_chars() {
        let encoded = encode(b"some test data of various lengths");
        for c in encoded.chars() {
            assert!(
                ALPHABET.contains(&(c as u8)),
                "char {c:?} not in z-base32 alphabet"
            );
        }
    }
}
