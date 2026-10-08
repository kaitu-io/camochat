//! CJK-only 14-bit binary-to-text (替 Base32768)。主表 U+4E00..=U+8DFF
//! (16384=2^14)，次表 U+8E00..=U+8E3F (64=2^6) 仅末位补齐。全 CJK Unified、
//! NFC/NFKC 稳定、聊天 app 视作普通汉字（gating A 实证）。
use crate::error::{Error, Result};
const PRIMARY_BASE: u32 = 0x4E00;
const PRIMARY_N: u32 = 16384;
const SECONDARY_BASE: u32 = 0x8E00;
const SECONDARY_N: u32 = 64;
const BITS_PER_CHAR: u32 = 14;
const BITS_PER_BYTE: u32 = 8;

fn primary(idx: u32) -> char {
    char::from_u32(PRIMARY_BASE + idx).unwrap()
}
fn secondary(idx: u32) -> char {
    char::from_u32(SECONDARY_BASE + idx).unwrap()
}

/// Encode a byte slice to a CJK14 string.
///
/// Output length is `ceil(input.len() * 8 / 14)` characters (counting
/// scalar values, not UTF-8 bytes).
#[must_use]
pub fn encode(input: &[u8]) -> String {
    let mut out = String::new();
    let mut z: u32 = 0;
    let mut n: u32 = 0;
    for &b in input {
        for j in (0..BITS_PER_BYTE).rev() {
            z = (z << 1) | u32::from((b >> j) & 1);
            n += 1;
            if n == BITS_PER_CHAR {
                out.push(primary(z));
                z = 0;
                n = 0;
            }
        }
    }
    if n != 0 {
        while n != BITS_PER_CHAR && n != BITS_PER_CHAR - BITS_PER_BYTE {
            z = (z << 1) | 1;
            n += 1;
        }
        out.push(if n == BITS_PER_CHAR {
            primary(z)
        } else {
            secondary(z)
        });
    }
    out
}

/// Decode a CJK14 string to bytes.
///
/// # Errors
/// - [`Error::Decoding`] if an input character is outside the primary
///   (U+4E00..U+8DFF) or secondary (U+8E00..U+8E3F) ranges, or a 6-bit
///   "secondary" character appears anywhere except the very last position,
///   or the trailing padding bits are not all 1s.
///
/// # Panics
/// Never in practice: the only narrowing is a `(acc & 0xFF) as u8` that is
/// statically masked to 0..=255.
pub fn decode(s: &str) -> Result<Vec<u8>> {
    let total = s.chars().count();
    let mut out = Vec::new();
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    for (i, c) in s.chars().enumerate() {
        let cp = c as u32;
        let (nb, z) = if (PRIMARY_BASE..PRIMARY_BASE + PRIMARY_N).contains(&cp) {
            (BITS_PER_CHAR, cp - PRIMARY_BASE)
        } else if (SECONDARY_BASE..SECONDARY_BASE + SECONDARY_N).contains(&cp) {
            (BITS_PER_CHAR - BITS_PER_BYTE, cp - SECONDARY_BASE)
        } else {
            return Err(Error::Decoding(format!("CJK14: bad char U+{cp:04X}")));
        };
        if nb != BITS_PER_CHAR && i + 1 != total {
            return Err(Error::Decoding(format!(
                "CJK14: secondary char at {i} not last"
            )));
        }
        for j in (0..nb).rev() {
            acc = (acc << 1) | ((z >> j) & 1);
            bits += 1;
            if bits == BITS_PER_BYTE {
                out.push((acc & 0xFF) as u8);
                acc = 0;
                bits = 0;
            }
        }
    }
    if acc != (1u32 << bits) - 1 {
        return Err(Error::Decoding("CJK14: padding not all 1s".into()));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn round_trip_all_lengths() {
        for n in [0usize, 1, 2, 7, 14, 16, 94, 200, 1000] {
            let data: Vec<u8> = (0..n)
                .map(|i| u8::try_from((i * 131 + 7) & 0xFF).unwrap())
                .collect();
            let s = encode(&data);
            assert!(
                s.chars().all(|c| ('\u{4E00}'..='\u{8E3F}').contains(&c)),
                "n={n} non-CJK char"
            );
            assert_eq!(decode(&s).unwrap(), data, "round-trip n={n}");
        }
    }
    #[test]
    fn density_14_bits_per_char() {
        // ceil(n*8/14) chars
        let s = encode(&[0xABu8; 14]); // 112 bits / 14 = 8 chars
        assert_eq!(s.chars().count(), 8);
    }
    #[test]
    fn secondary_char_in_middle_rejected() {
        // 6 bytes = 48 bits = 3*14 + 6 → final char is a secondary (6-bit) char.
        let s = encode(&[0u8; 6]);
        let chars: Vec<char> = s.chars().collect();
        assert_eq!(chars.len(), 4, "expected 3 primary + 1 secondary");
        // Move the trailing secondary char to the front → must be rejected.
        let mut bad = String::new();
        bad.push(chars[chars.len() - 1]);
        bad.extend(&chars[..chars.len() - 1]);
        let err = decode(&bad).unwrap_err();
        assert!(
            matches!(err, Error::Decoding(ref m) if m.contains("secondary") || m.contains("not last")),
            "unexpected error: {err:?}"
        );
    }
    #[test]
    fn invalid_char_rejected() {
        let err = decode("hello").unwrap_err();
        assert!(matches!(err, Error::Decoding(_)));
    }
    #[test]
    fn known_vector_zero_byte() {
        // 8 bits of zero, padded with 6 one-bits to 14 bits → index
        // 0b00000000111111 = 63 → char = U+4E00 + 63 = U+4E3F.
        let s = encode(&[0u8]);
        assert_eq!(s.chars().count(), 1);
        assert_eq!(s.chars().next().unwrap() as u32, 0x4E3F);
        assert_eq!(decode(&s).unwrap(), vec![0u8]);
    }
}
