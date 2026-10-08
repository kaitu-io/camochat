//! Base32768 binary-to-text encoding (qntm canonical alphabet).
//!
//! Reference implementation: <https://github.com/qntm/base32768> (MIT,
//! kreativekorp mirror lists the same code-point ranges). The alphabet
//! must be reproduced *verbatim*: 32 768 BMP code points laid out as 32
//! contiguous blocks of 1 024 (the "15-bit" repertoire), plus a 128-entry
//! "7-bit" repertoire used for the final partial-byte alignment character.
//!
//! ## Encoding
//! Bytes are consumed MSB-first into a sliding 15-bit accumulator. Every
//! full 15 bits emit one character from the 15-bit repertoire. After all
//! bytes, if 1..=14 leftover bits remain, they are padded out to the next
//! quantum (15 or 7 bits) using 1-bits and the final character is emitted
//! from whichever repertoire matches.
//!
//! ## Decoding
//! Mirror of the encoder. Padding bits are validated (must all be 1s); a
//! mismatch is a hard error. Unknown characters are a hard error. Any
//! "secondary" 7-bit character that is not the last character is rejected.
//!
//! Compactness: ~15 bits per `char`, ≈1.875 bits per byte saved vs
//! base32 (5 bits/char). For a 96-byte L3 ciphertext this is ~52 chars vs
//! base32's ~154 chars.

use crate::error::{Error, Result};

/// 15-bit alphabet: BMP code-point ranges totalling 32 768 entries.
///
/// Mirrored exactly from `qntm/base32768` `pairStrings[0]`. The qntm
/// source lists the alphabet as a single UTF-16 string of 104 scalars
/// (52 pairs); each pair `(first, last)` defines an inclusive range. We
/// have decoded that string and expanded it into the table below.
///
/// `sum(last - first + 1) == 32 768` — verified at startup via `debug_assert`.
#[rustfmt::skip]
const PAIRS_15: &[(u32, u32)] = &[
    (0x04A0, 0x04BF), (0x0500, 0x051F), (0x0680, 0x06BF), (0x0760, 0x079F),
    (0x07C0, 0x07DF), (0x1000, 0x101F), (0x10A0, 0x10BF), (0x1100, 0x115F),
    (0x1180, 0x119F), (0x11E0, 0x123F), (0x1260, 0x127F), (0x12E0, 0x12FF),
    (0x1320, 0x133F), (0x13A0, 0x13DF), (0x1420, 0x165F), (0x16A0, 0x16DF),
    (0x1780, 0x179F), (0x1820, 0x185F), (0x18C0, 0x18DF), (0x1980, 0x199F),
    (0x19E0, 0x19FF), (0x1A20, 0x1A3F), (0x1BC0, 0x1BDF), (0x1C00, 0x1C1F),
    (0x1D00, 0x1D1F), (0x21E0, 0x21FF), (0x22C0, 0x22DF), (0x2340, 0x23DF),
    (0x2400, 0x241F), (0x2500, 0x275F), (0x2780, 0x27BF), (0x2800, 0x297F),
    (0x29A0, 0x29BF), (0x2A20, 0x2A5F), (0x2A80, 0x2ABF), (0x2AE0, 0x2B5F),
    (0x2C00, 0x2C1F), (0x2C80, 0x2CDF), (0x2D00, 0x2D1F), (0x2D40, 0x2D5F),
    (0x2EA0, 0x2EDF), (0x31C0, 0x31DF), (0x3400, 0x4D9F), (0x4DC0, 0x9FBF),
    (0xA000, 0xA47F), (0xA4A0, 0xA4BF), (0xA500, 0xA5FF), (0xA640, 0xA65F),
    (0xA6A0, 0xA6DF), (0xA700, 0xA75F), (0xA780, 0xA79F), (0xA840, 0xA85F),
];

/// 7-bit alphabet: 128 entries used only as the final partial-byte
/// alignment character. Mirrors qntm `pairStrings[1]`.
#[rustfmt::skip]
const PAIRS_7: &[(u32, u32)] = &[
    (0x0180, 0x019F), (0x0240, 0x029F),
];

const BITS_PER_CHAR: u32 = 15;
const BITS_PER_BYTE: u32 = 8;

/// Expand a slice of (first, last) inclusive code-point pairs into a flat
/// vector of `char`s. Total length is the sum of `(last - first + 1)`.
fn expand_pairs(pairs: &[(u32, u32)]) -> Vec<char> {
    let mut out = Vec::with_capacity(pairs.iter().map(|(a, b)| (b - a + 1) as usize).sum());
    for &(first, last) in pairs {
        for cp in first..=last {
            // All entries in PAIRS_15 / PAIRS_7 are BMP code points that
            // map to a single `char`. unwrap is safe by table construction.
            out.push(char::from_u32(cp).expect("table contains BMP code points only"));
        }
    }
    out
}

/// Lazy-initialised lookup tables.
struct Tables {
    /// 15-bit forward table: 32 768 entries.
    repertoire_15: Vec<char>,
    /// 7-bit forward table: 128 entries.
    repertoire_7: Vec<char>,
    /// Combined reverse map: `char -> (num_z_bits, z)`.
    decode: std::collections::HashMap<char, (u32, u16)>,
}

impl Tables {
    fn build() -> Self {
        let repertoire_15 = expand_pairs(PAIRS_15);
        let repertoire_7 = expand_pairs(PAIRS_7);
        debug_assert_eq!(repertoire_15.len(), 1 << BITS_PER_CHAR);
        debug_assert_eq!(repertoire_7.len(), 1 << (BITS_PER_CHAR - BITS_PER_BYTE));

        let mut decode =
            std::collections::HashMap::with_capacity(repertoire_15.len() + repertoire_7.len());
        for (z, c) in repertoire_15.iter().enumerate() {
            decode.insert(*c, (BITS_PER_CHAR, u16::try_from(z).expect("< 2^15")));
        }
        for (z, c) in repertoire_7.iter().enumerate() {
            decode.insert(
                *c,
                (
                    BITS_PER_CHAR - BITS_PER_BYTE,
                    u16::try_from(z).expect("< 128"),
                ),
            );
        }
        Self {
            repertoire_15,
            repertoire_7,
            decode,
        }
    }
}

fn tables() -> &'static Tables {
    use std::sync::OnceLock;
    static TABLES: OnceLock<Tables> = OnceLock::new();
    TABLES.get_or_init(Tables::build)
}

/// Encode a byte slice to a Base32768 (qntm) string.
///
/// Output length is `ceil(input.len() * 8 / 15)` characters (counting
/// scalar values, not UTF-8 bytes).
#[must_use]
pub fn encode(input: &[u8]) -> String {
    let t = tables();
    let mut out = String::new();
    let mut z: u32 = 0;
    let mut num_z_bits: u32 = 0;

    for &b in input {
        // Most-significant bit first.
        for j in (0..BITS_PER_BYTE).rev() {
            let bit = u32::from((b >> j) & 1);
            z = (z << 1) | bit;
            num_z_bits += 1;
            if num_z_bits == BITS_PER_CHAR {
                out.push(t.repertoire_15[z as usize]);
                z = 0;
                num_z_bits = 0;
            }
        }
    }

    if num_z_bits != 0 {
        // Pad with 1s until z fits into a known repertoire size (7 or 15).
        // qntm spec: pad to 15 if >= 8 bits left over, else pad to 7.
        while num_z_bits != BITS_PER_CHAR && num_z_bits != BITS_PER_CHAR - BITS_PER_BYTE {
            z = (z << 1) | 1;
            num_z_bits += 1;
        }
        let c = if num_z_bits == BITS_PER_CHAR {
            t.repertoire_15[z as usize]
        } else {
            t.repertoire_7[z as usize]
        };
        out.push(c);
    }

    out
}

/// Decode a Base32768 (qntm) string to bytes.
///
/// # Errors
/// - [`Error::Decoding`] if an input character is not in the alphabet,
///   or a 7-bit "secondary" character appears anywhere except the very
///   last position, or the trailing padding bits are not all 1s.
///
/// # Panics
/// Never in practice: the only `expect` is a `u8::try_from(acc & 0xFF)`
/// that is statically masked to 0..=255.
pub fn decode(s: &str) -> Result<Vec<u8>> {
    let t = tables();
    let total = s.chars().count();
    let mut out = Vec::with_capacity(total * BITS_PER_CHAR as usize / BITS_PER_BYTE as usize);
    let mut acc: u32 = 0;
    let mut acc_bits: u32 = 0;

    for (i, c) in s.chars().enumerate() {
        let (num_z_bits, z) = *t.decode.get(&c).ok_or_else(|| {
            Error::Decoding(format!(
                "Base32768: unrecognised character U+{:04X}",
                c as u32
            ))
        })?;
        if num_z_bits != BITS_PER_CHAR && i + 1 != total {
            return Err(Error::Decoding(format!(
                "Base32768: 7-bit secondary character at index {i} (not last)"
            )));
        }
        // Push num_z_bits bits MSB-first into acc.
        for j in (0..num_z_bits).rev() {
            let bit = u32::from((z >> j) & 1);
            acc = (acc << 1) | bit;
            acc_bits += 1;
            if acc_bits == BITS_PER_BYTE {
                out.push(u8::try_from(acc & 0xFF).expect("masked"));
                acc = 0;
                acc_bits = 0;
            }
        }
    }

    // Trailing padding bits MUST be all 1s.
    let expected_pad = (1u32 << acc_bits) - 1;
    if acc != expected_pad {
        return Err(Error::Decoding(format!(
            "Base32768: padding mismatch (acc=0b{acc:0bits$b}, expected all 1s)",
            bits = acc_bits as usize
        )));
    }

    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_round_trip() {
        assert_eq!(encode(&[]), "");
        assert_eq!(decode("").unwrap(), Vec::<u8>::new());
    }

    #[test]
    fn single_byte_round_trip() {
        for b in 0u8..=255 {
            let s = encode(&[b]);
            // 8 bits → pad up to 15 bits (7 padding bits = all 1s) → 1 char from 15-bit alphabet
            // or, equivalently, length is 1 since 8 < 15.
            assert_eq!(s.chars().count(), 1);
            let decoded = decode(&s).unwrap();
            assert_eq!(decoded, vec![b], "round-trip failed for byte 0x{b:02x}");
        }
    }

    #[test]
    fn round_trip_lengths_match_spec_table() {
        // qntm spec: 15 bits/char, so encoded char count = ceil(n*8/15)
        for n in [1usize, 2, 15, 16, 32, 38, 64, 94, 96, 200] {
            let data: Vec<u8> = (0..n)
                .map(|i| u8::try_from((i * 31 + 7) & 0xFF).unwrap())
                .collect();
            let s = encode(&data);
            let expected_chars = (n * 8).div_ceil(15);
            assert_eq!(
                s.chars().count(),
                expected_chars,
                "n = {n}: expected {expected_chars} chars, got {}",
                s.chars().count()
            );
            assert_eq!(decode(&s).unwrap(), data, "round trip failed for n = {n}");
        }
    }

    #[test]
    fn random_data_round_trip() {
        // Deterministic pseudo-random buffer (LCG, MSB byte picked).
        let mut seed: u64 = 0;
        for n in 0..200 {
            let buf: Vec<u8> = (0..n)
                .map(|_| {
                    seed = seed
                        .wrapping_mul(6_364_136_223_846_793_005)
                        .wrapping_add(1_442_695_040_888_963_407);
                    u8::try_from((seed >> 33) & 0xFF).unwrap()
                })
                .collect();
            let s = encode(&buf);
            let back = decode(&s).unwrap();
            assert_eq!(back, buf, "round-trip mismatch at n={n}");
        }
    }

    #[test]
    fn invalid_char_rejected() {
        // ASCII letters are NOT in the qntm alphabet.
        let err = decode("hello").unwrap_err();
        assert!(matches!(err, Error::Decoding(_)));
    }

    #[test]
    fn secondary_char_in_middle_rejected() {
        // Build a 94-byte buffer, encode it. That gives us 51 chars: 50
        // "primary" 15-bit chars and (since 94*8 = 752, 752/15 = 50 r 2)
        // 1 "secondary" 7-bit char at the end. Splice the secondary into
        // the middle to verify the decoder rejects it.
        let buf = vec![0xA5u8; 94];
        let s = encode(&buf);
        let chars: Vec<char> = s.chars().collect();
        let n = chars.len();
        assert!(n >= 2, "encoded must have at least 2 chars");
        // Move last char to position 0 (so it appears before the end).
        let mut polluted = String::new();
        polluted.push(chars[n - 1]);
        for c in &chars[..n - 1] {
            polluted.push(*c);
        }
        let err = decode(&polluted).unwrap_err();
        assert!(
            matches!(err, Error::Decoding(ref m) if m.contains("secondary") || m.contains("padding")),
            "unexpected error: {err:?}"
        );
    }

    #[test]
    fn alphabet_sizes_correct() {
        let t = tables();
        assert_eq!(t.repertoire_15.len(), 32_768);
        assert_eq!(t.repertoire_7.len(), 128);
    }

    #[test]
    fn known_vector_zero_byte() {
        // For input [0u8] the encoder collects 8 bits (all 0), then pads
        // with 1s up to 15 bits (because 8 > 7 so we pad to the next
        // valid quantum which is 15). z = 0b00000000_1111111 = 127.
        // PAIRS_15 walking: 0..=31 -> 0x04A0..=0x04BF, 32..=63 ->
        // 0x0500..=0x051F, 64..=127 -> 0x0680..=0x06BF. So entry 127 maps
        // to 0x0680 + (127 - 64) = 0x06BF.
        let s = encode(&[0u8]);
        assert_eq!(s.chars().count(), 1);
        let c = s.chars().next().unwrap() as u32;
        assert_eq!(c, 0x06BF, "expected entry 127 of 15-bit alphabet for [0u8]");
        assert_eq!(decode(&s).unwrap(), vec![0u8]);
    }

    #[test]
    fn known_vector_7bit_path() {
        // Encoding a length-N buffer where total bits ≡ {1..=7} mod 15
        // exercises the 7-bit secondary character. For N = 1, 8 bits in,
        // we pad to 15 (15-bit primary). For N = 8 (64 bits): 64 = 4*15 + 4
        // → 4 bits leftover, pad to 7 → secondary char. Verify the encoded
        // string ends with a 7-bit alphabet char.
        let buf = [0u8; 8];
        let s = encode(&buf);
        let last = s.chars().last().unwrap() as u32;
        let in_7bit = PAIRS_7.iter().any(|&(a, b)| (a..=b).contains(&last));
        assert!(
            in_7bit,
            "expected last char in 7-bit alphabet, got U+{last:04X}"
        );
        assert_eq!(decode(&s).unwrap(), buf.to_vec());
    }
}
