//! V1 wire-protocol L2 frame (tagged union) + L4 string codec.
//!
//! See `docs/protocol/README.md` §7 (wire layers) and §8 (media locator).
//! This module replaces the V0 CBOR `MessagePayload` envelope; the V0
//! z-base32 wire is also gone, replaced by `🔒 + CJK14(L3)`.
//!
//! Post-pivot (in-band, zero-server) the L2 frame is a tagged union keyed by
//! `msg_type`. `TEXT` (0x10) and `MEDIA_REF` (0x40) are live. `0x30` was
//! `VOICE_TOKENS` (Mimi neural-codec voice, retired 2026-09-24 with the
//! keyboard form factor) and is **reserved**: decoders treat it as an
//! unknown type. Rich media (voice / image / video) uses the `MEDIA_REF`
//! type, see `docs/protocol/README.md` (media container and locator section).
//!
//! ```text
//! L4  wire string : "🔒" + Base32768( L3 bytes )
//! L3  DR cipher   : Session::encrypt(L2 bytes)        (handled by `session`)
//! L2  app frame   : [magic 1B][version 1B][type 1B][flags 1B][body]
//!     TEXT           (0x10): [utf8 bytes]
//!     MEDIA_REF      (0x40): [count 1B] count × [kind 1B][dur_ms u16][w u16][h u16][byte_len u32][blob_secret 32B]
//! ```
//!
//! ## Public API
//!
//! - [`encode_text_frame`] and [`decode_frame`] for the L2 boundary.
//! - [`encode_wire`] / [`decode_wire`] for the L4 boundary.
//!
//! Encoders and decoders are deterministic, side-effect free, and operate
//! on owned byte buffers — safe to call from any platform binding.

mod hkdf;

// `derive_blob_material`/`BlobMaterial` are the per-blob key-schedule used by
// the rich-media (object-storage) path; kept on purpose.
pub use self::hkdf::{derive_blob_material, BlobMaterial};

use crate::encoding::cjk14;

/// 🔒 (U+1F512) — the only character the IME clipboard listener checks
/// to decide whether a clipboard event is a chencang message. Always the
/// very first scalar of the wire string.
pub const WIRE_PREFIX: char = '\u{1F512}';

/// L2 magic byte. Never changes across protocol versions.
pub const MAGIC: u8 = 0xCC;

/// L2 version byte for V1.0 = `(1 << 4) | 0`.
pub const VERSION_V1_0: u8 = 0x10;

/// L2 `msg_type` for a TEXT message (UTF-8 body).
pub const MSG_TYPE_TEXT: u8 = 0x10;

/// L2 `msg_type` for a `MEDIA_REF` message (1..=9 encrypted-blob references).
pub const MSG_TYPE_MEDIA_REF: u8 = 0x40;

/// Media kind: voice (Opus in Ogg).
pub const MEDIA_KIND_VOICE: u8 = 0x01;
/// Media kind: image (JPEG).
pub const MEDIA_KIND_IMAGE: u8 = 0x02;
/// Media kind: video (MP4, H.264 + AAC).
pub const MEDIA_KIND_VIDEO: u8 = 0x03;

/// Encoded size of one [`MediaRef`]: kind 1 + dur 2 + w 2 + h 2 + len 4 + secret 32.
pub const MEDIA_REF_LEN: usize = 43;
/// Maximum references in one `MEDIA_REF` frame (`WeChat`'s 9-image limit).
pub const MEDIA_REF_MAX_COUNT: usize = 9;
/// Maximum voice/video duration in milliseconds.
pub const MEDIA_MAX_DUR_MS: u16 = 60_000;

/// Maximum total `.cca` blob size for a voice message (2 MiB, matches
/// `infra/media/lambda/sign.mjs`'s `LIMITS[1]` — keep both in sync).
pub const MEDIA_MAX_BLOB_LEN_VOICE: u32 = 2_097_152;
/// Maximum total `.cca` blob size for an image (2 MiB, matches
/// `infra/media/lambda/sign.mjs`'s `LIMITS[2]`).
pub const MEDIA_MAX_BLOB_LEN_IMAGE: u32 = 2_097_152;
/// Maximum total `.cca` blob size for a video (30 MiB, matches
/// `infra/media/lambda/sign.mjs`'s `LIMITS[3]`).
pub const MEDIA_MAX_BLOB_LEN_VIDEO: u32 = 31_457_280;
/// Minimum possible `.cca` blob size: an empty plaintext still costs the
/// 34-byte header (`blob::CCA_HEADER_LEN`) plus the 16-byte AEAD tag.
/// Written as a literal (rather than importing from `blob`) to keep
/// `payload` from depending on `blob`, which already depends on `payload`
/// for the `MEDIA_KIND_*` constants and `derive_blob_material`; a test
/// below asserts the two stay in sync.
pub const MEDIA_MIN_BLOB_LEN: u32 = 50;

/// Maximum `.cca` blob size allowed for `kind`, or `None` for an unknown kind.
#[must_use]
pub fn max_blob_len_for_kind(kind: u8) -> Option<u32> {
    match kind {
        MEDIA_KIND_VOICE => Some(MEDIA_MAX_BLOB_LEN_VOICE),
        MEDIA_KIND_IMAGE => Some(MEDIA_MAX_BLOB_LEN_IMAGE),
        MEDIA_KIND_VIDEO => Some(MEDIA_MAX_BLOB_LEN_VIDEO),
        _ => None,
    }
}

/// One encrypted-blob reference inside a `MEDIA_REF` frame. The blob's
/// object id, key and nonce are all derived from `blob_secret` via
/// [`derive_blob_material`]; nothing else about the blob is secret.
#[derive(Clone, PartialEq, Eq)]
pub struct MediaRef {
    /// [`MEDIA_KIND_VOICE`] / [`MEDIA_KIND_IMAGE`] / [`MEDIA_KIND_VIDEO`].
    pub kind: u8,
    /// Voice/video duration in ms (0 for images).
    pub dur_ms: u16,
    /// Pixel width (0 for voice).
    pub width: u16,
    /// Pixel height (0 for voice).
    pub height: u16,
    /// Total `.cca` blob size in bytes (header + ciphertext + tag).
    pub byte_len: u32,
    /// 32-byte CSPRNG secret, unique per blob.
    pub blob_secret: [u8; 32],
}

// `blob_secret` is capability-bearing (decrypts the blob + derives its
// object-store id) — never let a `{:?}` log line leak it.
impl std::fmt::Debug for MediaRef {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MediaRef")
            .field("kind", &self.kind)
            .field("dur_ms", &self.dur_ms)
            .field("width", &self.width)
            .field("height", &self.height)
            .field("byte_len", &self.byte_len)
            .field("blob_secret", &"<redacted>")
            .finish()
    }
}

/// L2 `flags` value for V1.0 (must be zero on send; receiver ignores
/// unknown bits).
pub const FLAGS_V1_0: u8 = 0x00;

/// L2 header length (magic + version + `msg_type` + flags).
pub const L2_HEADER_LEN: usize = 4;

/// Decoded L2 message — a tagged union selected by `msg_type`. Future
/// minor versions may add more `msg_type` variants and old receivers MUST
/// surface those as `UnsupportedStandardMsgType`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedMessage {
    /// `msg_type = 0x10` UTF-8 text message.
    Text(String),
    /// `msg_type = 0x40` references to 1..=9 encrypted media blobs.
    MediaRef(Vec<MediaRef>),
}

/// Structured decode error for the L2 layer. Maps cleanly to UI text
/// (see `docs/protocol/README.md` §7.1 for the `msg_type` rules).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FrameError {
    /// L2 buffer shorter than the minimum required length (the 4-byte
    /// header, or the fixed sub-header of a typed body).
    #[error("frame too short: got {got} bytes, need at least {need}")]
    TooShort {
        /// Actual length checked: the full frame length for header checks, or the body-slice length for typed-body sub-header checks.
        got: usize,
        /// Minimum expected length.
        need: usize,
    },
    /// First byte was not the protocol magic `0xCC`.
    #[error("bad magic byte: got 0x{got:02x}, want 0xCC")]
    BadMagic {
        /// Observed magic byte.
        got: u8,
    },
    /// Major version segment of byte 1 did not equal 1.
    #[error("unsupported major version {0}; upgrade chencang")]
    UnsupportedMajor(u8),
    /// `msg_type = 0x00` is reserved as a null-byte guard.
    #[error("reserved msg_type 0x00")]
    ReservedMsgType,
    /// `msg_type` in the 0x02..=0x7F standard reservation range that this
    /// build does not understand. UI should prompt the user to upgrade.
    #[error("unsupported standard msg_type 0x{0:02x}; upgrade chencang")]
    UnsupportedStandardMsgType(u8),
    /// `msg_type` in the 0x80..=0xFF experimental range — silently ignore.
    #[error("experimental msg_type 0x{0:02x} ignored")]
    ExperimentalMsgType(u8),
    /// Body length is inconsistent with the declared `msg_type` (e.g. a
    /// `prosody_len` that overruns the remaining bytes, or a body that
    /// cannot hold its required fixed sub-header).
    #[error("payload length mismatch for msg_type 0x{msg_type:02x}: got {got}, want {want}")]
    BadPayloadLen {
        /// The declared `msg_type` byte.
        msg_type: u8,
        /// Observed payload length.
        got: usize,
        /// Required (minimum/exact) payload length.
        want: usize,
    },
    /// TEXT body was not valid UTF-8.
    #[error("text body is not valid UTF-8")]
    BadUtf8,
    /// `MEDIA_REF` body is structurally valid-length but semantically wrong
    /// (count out of 1..=9, unknown kind, duration over the limit).
    #[error("bad media ref: {0}")]
    BadMediaRef(&'static str),
}

/// L4 wire-string decode error.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum WireError {
    /// Input did not start with the U+1F512 🔒 prefix.
    #[error("input does not start with 🔒 prefix")]
    MissingPrefix,
    /// Base32768 decoding of the post-🔒 body failed.
    #[error("base32768 decode failed: {0}")]
    Base32768(String),
}

// ─── Encoders ────────────────────────────────────────────────────────

/// Build the 4-byte L2 header for the given `msg_type`.
fn header(msg_type: u8) -> [u8; L2_HEADER_LEN] {
    [MAGIC, VERSION_V1_0, msg_type, FLAGS_V1_0]
}

/// Build an L2 TEXT frame from a UTF-8 string.
#[must_use]
pub fn encode_text_frame(s: &str) -> Vec<u8> {
    let mut o = header(MSG_TYPE_TEXT).to_vec();
    o.extend_from_slice(s.as_bytes());
    o
}

fn check_media_ref(r: &MediaRef) -> std::result::Result<(), FrameError> {
    let Some(max) = max_blob_len_for_kind(r.kind) else {
        return Err(FrameError::BadMediaRef("unknown kind"));
    };
    if r.dur_ms > MEDIA_MAX_DUR_MS {
        return Err(FrameError::BadMediaRef("duration over limit"));
    }
    if r.byte_len < MEDIA_MIN_BLOB_LEN || r.byte_len > max {
        return Err(FrameError::BadMediaRef("byte_len out of range"));
    }
    Ok(())
}

/// Build an L2 `MEDIA_REF` frame (`msg_type = 0x40`) carrying 1..=9 refs.
///
/// # Errors
/// [`FrameError::BadMediaRef`] when `refs` is empty, longer than
/// [`MEDIA_REF_MAX_COUNT`], or any ref has an unknown kind / over-long duration.
pub fn encode_media_ref_frame(refs: &[MediaRef]) -> std::result::Result<Vec<u8>, FrameError> {
    if refs.is_empty() || refs.len() > MEDIA_REF_MAX_COUNT {
        return Err(FrameError::BadMediaRef("count out of range"));
    }
    let mut o = Vec::with_capacity(L2_HEADER_LEN + 1 + refs.len() * MEDIA_REF_LEN);
    o.extend_from_slice(&header(MSG_TYPE_MEDIA_REF));
    #[allow(clippy::cast_possible_truncation)] // bounded by MEDIA_REF_MAX_COUNT above
    o.push(refs.len() as u8);
    for r in refs {
        check_media_ref(r)?;
        o.push(r.kind);
        o.extend_from_slice(&r.dur_ms.to_be_bytes());
        o.extend_from_slice(&r.width.to_be_bytes());
        o.extend_from_slice(&r.height.to_be_bytes());
        o.extend_from_slice(&r.byte_len.to_be_bytes());
        o.extend_from_slice(&r.blob_secret);
    }
    Ok(o)
}

/// Encode a length-prefixed L3 ciphertext (the bytes produced by the DR
/// layer) as the L4 wire string: `🔒` + Base32768.
#[must_use]
pub fn encode_wire(ciphertext: &[u8]) -> String {
    let mut out = String::with_capacity(WIRE_PREFIX.len_utf8() + ciphertext.len() * 2);
    out.push(WIRE_PREFIX);
    out.push_str(&cjk14::encode(ciphertext));
    out
}

// ─── Decoders ────────────────────────────────────────────────────────

/// Parse a candidate L2 frame into a [`DecodedMessage`].
///
/// # Errors
/// See [`FrameError`] variants.
pub fn decode_frame(bytes: &[u8]) -> std::result::Result<DecodedMessage, FrameError> {
    if bytes.len() < L2_HEADER_LEN {
        return Err(FrameError::TooShort {
            got: bytes.len(),
            need: L2_HEADER_LEN,
        });
    }
    let magic = bytes[0];
    if magic != MAGIC {
        return Err(FrameError::BadMagic { got: magic });
    }
    let version = bytes[1];
    let major = version >> 4;
    if major != 1 {
        return Err(FrameError::UnsupportedMajor(major));
    }
    let msg_type = bytes[2];
    // bytes[3] = flags — V1.0 receiver ignores per spec.
    let body = &bytes[L2_HEADER_LEN..];

    match msg_type {
        0x00 => Err(FrameError::ReservedMsgType),
        MSG_TYPE_TEXT => decode_text_body(body),
        MSG_TYPE_MEDIA_REF => decode_media_ref_body(body),
        // 0x10 and 0x40 are matched above; this arm catches all OTHER
        // standard-reservation types (0x01..=0x7F) the build doesn't know
        // (including the retired 0x20 SPEAKER_ENROLL and 0x30 VOICE_TOKENS).
        0x01..=0x7F => Err(FrameError::UnsupportedStandardMsgType(msg_type)),
        0x80..=0xFF => Err(FrameError::ExperimentalMsgType(msg_type)),
    }
}

fn decode_text_body(body: &[u8]) -> std::result::Result<DecodedMessage, FrameError> {
    let s = String::from_utf8(body.to_vec()).map_err(|_| FrameError::BadUtf8)?;
    Ok(DecodedMessage::Text(s))
}

fn decode_media_ref_body(body: &[u8]) -> std::result::Result<DecodedMessage, FrameError> {
    let Some((&count, rest)) = body.split_first() else {
        return Err(FrameError::TooShort { got: 0, need: 1 });
    };
    let count = usize::from(count);
    if count == 0 || count > MEDIA_REF_MAX_COUNT {
        return Err(FrameError::BadMediaRef("count out of range"));
    }
    let want = 1 + count * MEDIA_REF_LEN;
    if body.len() != want {
        return Err(FrameError::BadPayloadLen {
            msg_type: MSG_TYPE_MEDIA_REF,
            got: body.len(),
            want,
        });
    }
    let refs = rest
        .chunks_exact(MEDIA_REF_LEN)
        .map(|c| {
            let be16 = |i: usize| u16::from_be_bytes([c[i], c[i + 1]]);
            let mut blob_secret = [0u8; 32];
            blob_secret.copy_from_slice(&c[11..43]);
            let r = MediaRef {
                kind: c[0],
                dur_ms: be16(1),
                width: be16(3),
                height: be16(5),
                byte_len: u32::from_be_bytes([c[7], c[8], c[9], c[10]]),
                blob_secret,
            };
            check_media_ref(&r).map(|()| r)
        })
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(DecodedMessage::MediaRef(refs))
}

/// Strip the 🔒 prefix and decode the Base32768 body to bytes.
///
/// # Errors
/// - [`WireError::MissingPrefix`] when the input does not start with U+1F512.
/// - [`WireError::Base32768`] when the body is not valid Base32768.
pub fn decode_wire(s: &str) -> std::result::Result<Vec<u8>, WireError> {
    let mut chars = s.chars();
    match chars.next() {
        Some(WIRE_PREFIX) => {}
        _ => return Err(WireError::MissingPrefix),
    }
    let body: String = chars.collect();
    cjk14::decode(&body).map_err(|e| WireError::Base32768(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    // ─── round-trip ──────────────────────────────────────────────────

    #[test]
    fn text_frame_round_trip() {
        let f = encode_text_frame("你好");
        assert!(matches!(decode_frame(&f).unwrap(), DecodedMessage::Text(ref s) if s == "你好"));
    }

    // ─── header / dispatch error paths ───────────────────────────────

    #[test]
    fn decode_too_short() {
        assert!(matches!(
            decode_frame(&[]).unwrap_err(),
            FrameError::TooShort { got: 0, need: 4 }
        ));
        assert!(matches!(
            decode_frame(&[0xCC, 0x10, 0x10]).unwrap_err(),
            FrameError::TooShort { got: 3, .. }
        ));
    }

    #[test]
    fn decode_bad_magic() {
        let bytes = [0x00, 0x10, 0x10, 0x00];
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::BadMagic { got: 0x00 }
        ));
    }

    #[test]
    fn decode_unsupported_major() {
        // Major = 2 → 0x20.
        let mut bytes = encode_text_frame("hi");
        bytes[1] = 0x20;
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::UnsupportedMajor(2)
        ));
    }

    #[test]
    fn decode_reserved_msg_type() {
        let mut bytes = encode_text_frame("hi");
        bytes[2] = 0x00;
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::ReservedMsgType
        ));
    }

    #[test]
    fn decode_unsupported_standard_msg_type() {
        let mut bytes = encode_text_frame("hi");
        bytes[2] = 0x42;
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::UnsupportedStandardMsgType(0x42)
        ));
    }

    #[test]
    fn decode_experimental_msg_type() {
        let mut bytes = encode_text_frame("hi");
        bytes[2] = 0xCC;
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::ExperimentalMsgType(0xCC)
        ));
    }

    #[test]
    fn decode_ignores_unknown_flag_bits_text() {
        // Set flags = 0xFE. V1.0 decoder ignores.
        let mut bytes = encode_text_frame("hello");
        bytes[3] = 0xFE;
        assert!(
            matches!(decode_frame(&bytes).unwrap(), DecodedMessage::Text(ref s) if s == "hello")
        );
    }

    // ─── TEXT malformed bodies ────────────────────────────────────────

    #[test]
    fn decode_text_rejects_invalid_utf8() {
        let mut bytes = header(MSG_TYPE_TEXT).to_vec();
        bytes.extend_from_slice(&[0xFF, 0xFE, 0x00, 0x9F]); // invalid UTF-8
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::BadUtf8
        ));
    }

    #[test]
    fn retired_speaker_enroll_type_is_now_unknown() {
        // 0x20 SPEAKER_ENROLL was removed with the factorized-voice teardown.
        // It must now decode as an unknown standard type, not a typed frame.
        let mut bytes = header(MSG_TYPE_TEXT).to_vec();
        bytes[2] = 0x20;
        assert!(matches!(
            decode_frame(&bytes).unwrap_err(),
            FrameError::UnsupportedStandardMsgType(0x20)
        ));
    }

    /// A frame from a pre-teardown keyboard (`msg_type` 0x30, `VOICE_TOKENS`)
    /// must surface as an unknown-type error, never a panic.
    #[test]
    fn decode_rejects_reserved_voice_tokens_type() {
        let mut bytes = encode_text_frame("x");
        bytes[2] = 0x30; // msg_type
        let err = decode_frame(&bytes).unwrap_err();
        assert!(
            matches!(err, FrameError::UnsupportedStandardMsgType(0x30)),
            "got {err:?}"
        );
    }

    // ─── L4 wire (unchanged from Task 2.1) ────────────────────────────

    #[test]
    fn wire_round_trip() {
        let ct = vec![0xFFu8; 94]; // typical L3 size.
        let wire = encode_wire(&ct);
        assert!(wire.starts_with(WIRE_PREFIX));
        let back = decode_wire(&wire).unwrap();
        assert_eq!(back, ct);
    }

    #[test]
    fn decode_wire_rejects_missing_prefix() {
        assert!(matches!(decode_wire("nope"), Err(WireError::MissingPrefix)));
        assert!(matches!(decode_wire(""), Err(WireError::MissingPrefix)));
        // 🔓 (open lock, U+1F513) is similar but the wrong scalar.
        assert!(matches!(
            decode_wire("\u{1F513}abc"),
            Err(WireError::MissingPrefix)
        ));
    }

    #[test]
    fn decode_wire_rejects_invalid_base32768() {
        // 🔒 + ASCII (ASCII is not in the qntm alphabet).
        let bad = format!("{WIRE_PREFIX}hello");
        assert!(matches!(decode_wire(&bad), Err(WireError::Base32768(_))));
    }

    // ─── MEDIA_REF (0x40) ────────────────────────────────────────────

    fn sample_ref(kind: u8, seed: u8) -> MediaRef {
        MediaRef {
            kind,
            dur_ms: if kind == MEDIA_KIND_IMAGE { 0 } else { 12_345 },
            width: if kind == MEDIA_KIND_VOICE { 0 } else { 1920 },
            height: if kind == MEDIA_KIND_VOICE { 0 } else { 1080 },
            byte_len: 123_456,
            blob_secret: [seed; 32],
        }
    }

    #[test]
    fn media_ref_debug_redacts_secret() {
        let r = sample_ref(MEDIA_KIND_IMAGE, 0xAB);
        let dbg = format!("{r:?}");
        assert!(
            !dbg.contains(&format!("{:?}", r.blob_secret)),
            "secret leaked into Debug: {dbg}"
        );
        assert!(dbg.contains("redacted"), "{dbg}");
    }

    #[test]
    fn media_ref_single_round_trip() {
        let r = sample_ref(MEDIA_KIND_VOICE, 7);
        let bytes = encode_media_ref_frame(std::slice::from_ref(&r)).unwrap();
        assert_eq!(bytes.len(), L2_HEADER_LEN + 1 + MEDIA_REF_LEN);
        assert_eq!(
            &bytes[..4],
            &[MAGIC, VERSION_V1_0, MSG_TYPE_MEDIA_REF, FLAGS_V1_0]
        );
        assert_eq!(
            decode_frame(&bytes).unwrap(),
            DecodedMessage::MediaRef(vec![r])
        );
    }

    #[test]
    fn media_ref_nine_round_trip() {
        let refs: Vec<MediaRef> = (0..9).map(|i| sample_ref(MEDIA_KIND_IMAGE, i)).collect();
        let bytes = encode_media_ref_frame(&refs).unwrap();
        assert_eq!(bytes.len(), L2_HEADER_LEN + 1 + 9 * MEDIA_REF_LEN);
        assert_eq!(
            decode_frame(&bytes).unwrap(),
            DecodedMessage::MediaRef(refs)
        );
    }

    #[test]
    fn media_ref_field_layout_is_big_endian() {
        // byte_len must stay within MEDIA_MAX_BLOB_LEN_VIDEO (0x01E0_0000);
        // 0x0102_0304 keeps four distinct bytes to verify big-endian order.
        let r = MediaRef {
            kind: MEDIA_KIND_VIDEO,
            dur_ms: 0x0102,
            width: 0x0304,
            height: 0x0506,
            byte_len: 0x0102_0304,
            blob_secret: [0xEE; 32],
        };
        let b = encode_media_ref_frame(&[r]).unwrap();
        assert_eq!(
            &b[4..16],
            &[1, 0x03, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x01, 0x02, 0x03, 0x04]
        );
        assert_eq!(&b[16..48], &[0xEE; 32]);
    }

    #[test]
    fn min_blob_len_matches_cca_header_plus_tag() {
        // MEDIA_MIN_BLOB_LEN is a literal (to avoid a payload<->blob module
        // cycle); this test is the single source of truth tying it back to
        // blob::CCA_HEADER_LEN + the 16-byte AEAD tag.
        assert_eq!(
            MEDIA_MIN_BLOB_LEN as usize,
            crate::blob::CCA_HEADER_LEN + 16
        );
    }

    #[test]
    fn check_media_ref_enforces_byte_len_bounds() {
        let mut r = sample_ref(MEDIA_KIND_IMAGE, 1);
        r.byte_len = MEDIA_MAX_BLOB_LEN_IMAGE;
        assert!(encode_media_ref_frame(&[r.clone()]).is_ok());
        r.byte_len = MEDIA_MAX_BLOB_LEN_IMAGE + 1;
        assert!(matches!(
            encode_media_ref_frame(&[r.clone()]),
            Err(FrameError::BadMediaRef(_))
        ));
        r.byte_len = MEDIA_MIN_BLOB_LEN;
        assert!(encode_media_ref_frame(&[r.clone()]).is_ok());
        r.byte_len = MEDIA_MIN_BLOB_LEN - 1;
        assert!(matches!(
            encode_media_ref_frame(&[r]),
            Err(FrameError::BadMediaRef(_))
        ));
    }

    #[test]
    fn check_media_ref_per_kind_max_is_enforced_independently() {
        let mut voice = sample_ref(MEDIA_KIND_VOICE, 2);
        voice.byte_len = MEDIA_MAX_BLOB_LEN_VOICE + 1;
        assert!(encode_media_ref_frame(&[voice]).is_err());

        let mut video = sample_ref(MEDIA_KIND_VIDEO, 3);
        video.byte_len = MEDIA_MAX_BLOB_LEN_VIDEO;
        assert!(encode_media_ref_frame(&[video.clone()]).is_ok());
        video.byte_len = MEDIA_MAX_BLOB_LEN_VIDEO + 1;
        assert!(encode_media_ref_frame(&[video]).is_err());
    }

    #[test]
    fn encode_rejects_empty_and_ten() {
        assert!(encode_media_ref_frame(&[]).is_err());
        let ten: Vec<MediaRef> = (0..10).map(|i| sample_ref(MEDIA_KIND_IMAGE, i)).collect();
        assert!(encode_media_ref_frame(&ten).is_err());
    }

    #[test]
    fn encode_rejects_unknown_kind_and_long_duration() {
        assert!(encode_media_ref_frame(&[sample_ref(0x04, 1)]).is_err());
        let mut r = sample_ref(MEDIA_KIND_VOICE, 1);
        r.dur_ms = MEDIA_MAX_DUR_MS + 1;
        assert!(encode_media_ref_frame(&[r]).is_err());
    }

    fn valid_frame() -> Vec<u8> {
        encode_media_ref_frame(&[sample_ref(MEDIA_KIND_VOICE, 3)]).unwrap()
    }

    #[test]
    fn decode_rejects_count_zero() {
        let mut b = valid_frame();
        b[4] = 0;
        b.truncate(5);
        assert!(matches!(decode_frame(&b), Err(FrameError::BadMediaRef(_))));
    }

    #[test]
    fn decode_rejects_count_ten() {
        let refs: Vec<MediaRef> = (0..9).map(|i| sample_ref(MEDIA_KIND_IMAGE, i)).collect();
        let mut b = encode_media_ref_frame(&refs).unwrap();
        b[4] = 10;
        b.extend_from_slice(&[0u8; MEDIA_REF_LEN]);
        assert!(matches!(decode_frame(&b), Err(FrameError::BadMediaRef(_))));
    }

    #[test]
    fn decode_rejects_length_off_by_one() {
        let mut longer = valid_frame();
        longer.push(0);
        assert!(matches!(
            decode_frame(&longer),
            Err(FrameError::BadPayloadLen { .. })
        ));
        let mut shorter = valid_frame();
        shorter.pop();
        assert!(matches!(
            decode_frame(&shorter),
            Err(FrameError::BadPayloadLen { .. })
        ));
    }

    #[test]
    fn decode_rejects_missing_count_byte() {
        let b = [MAGIC, VERSION_V1_0, MSG_TYPE_MEDIA_REF, FLAGS_V1_0];
        assert!(matches!(decode_frame(&b), Err(FrameError::TooShort { .. })));
    }

    #[test]
    fn decode_rejects_unknown_kind() {
        let mut b = valid_frame();
        b[5] = 0x04;
        assert!(matches!(decode_frame(&b), Err(FrameError::BadMediaRef(_))));
    }

    #[test]
    fn decode_rejects_long_duration() {
        let mut b = valid_frame();
        b[6..8].copy_from_slice(&(MEDIA_MAX_DUR_MS + 1).to_be_bytes());
        assert!(matches!(decode_frame(&b), Err(FrameError::BadMediaRef(_))));
    }

    #[test]
    fn retired_voice_tokens_type_still_unsupported() {
        let b = [MAGIC, VERSION_V1_0, 0x30, FLAGS_V1_0];
        assert_eq!(
            decode_frame(&b),
            Err(FrameError::UnsupportedStandardMsgType(0x30))
        );
    }
}
