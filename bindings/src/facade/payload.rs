//! UDL facade for the V1 wire-protocol codec (`chencang_core::payload`).
//!
//! Exposes:
//! - [`encode_text_frame`] / [`decode_frame`] for the L2 boundary.
//! - [`encode_wire`] / [`decode_wire`] for the L4 boundary.
//! - [`derive_blob_material`] for the HKDF derivation each peer runs
//!   locally to obtain `(blob_id, blob_key, blob_nonce)`.
//! - [`encode_media_ref_frame`] for rich-media `MEDIA_REF` frames.
//!
//! The old CBOR `MessagePayload` envelope (with `Text` / `AudioRef`
//! variants) was removed when V1 replaced V0. The platform sides
//! (`chencang-ios`, `chencang-android`) must be updated in lockstep.

use chencang_core::payload as core_payload;

use super::error::{ChencangError, Result};

/// One encrypted-blob reference. Mirrors `chencang_core::payload::MediaRef`
/// with `blob_secret` as a byte vector (must be 32 bytes).
#[derive(Clone, PartialEq, Eq)]
pub struct MediaRef {
    /// 1 voice / 2 image / 3 video.
    pub kind: u8,
    /// Voice/video duration in ms (0 for images).
    pub dur_ms: u16,
    /// Pixel width (0 for voice).
    pub width: u16,
    /// Pixel height (0 for voice).
    pub height: u16,
    /// Total `.cca` size in bytes.
    pub byte_len: u32,
    /// 32-byte per-blob secret.
    pub blob_secret: Vec<u8>,
}

// `blob_secret` is capability-bearing; never let it leak into a log via `{:?}`.
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

impl MediaRef {
    fn to_core(&self) -> Result<core_payload::MediaRef> {
        let blob_secret: [u8; 32] = self.blob_secret.as_slice().try_into().map_err(|_| {
            ChencangError::InvalidLength(format!(
                "blob_secret must be 32 bytes, got {}",
                self.blob_secret.len()
            ))
        })?;
        Ok(core_payload::MediaRef {
            kind: self.kind,
            dur_ms: self.dur_ms,
            width: self.width,
            height: self.height,
            byte_len: self.byte_len,
            blob_secret,
        })
    }

    fn from_core(r: core_payload::MediaRef) -> Self {
        Self {
            kind: r.kind,
            dur_ms: r.dur_ms,
            width: r.width,
            height: r.height,
            byte_len: r.byte_len,
            blob_secret: r.blob_secret.to_vec(),
        }
    }
}

/// V1 L2 frame variants. Mirrors `chencang_core::payload::DecodedMessage`.
///
/// Treat as an open enum on the platform side: match exhaustively but expect
/// new cases over time (minor wire versions may add variants).
///
/// uniffi UDL enums are always generated with struct-style (named-field)
/// variants, so even the single-payload `Text` case carries a named `value`
/// field (rather than core's tuple shape).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedMessage {
    /// `msg_type = 0x10` UTF-8 text message.
    Text {
        /// The decoded UTF-8 string.
        value: String,
    },
    /// `msg_type = 0x40` references to 1..=9 encrypted media blobs.
    Media {
        /// The blob references, in send order.
        refs: Vec<MediaRef>,
    },
}

/// HKDF derivation outputs.
#[derive(Clone, PartialEq, Eq)]
pub struct BlobMaterial {
    /// 16-byte S3 path identifier.
    pub blob_id: Vec<u8>,
    /// 32-byte XChaCha20-Poly1305 key for the blob.
    pub blob_key: Vec<u8>,
    /// 24-byte `XChaCha20` nonce.
    pub blob_nonce: Vec<u8>,
}

// `blob_key` / `blob_nonce` decrypt the blob; keep them out of `{:?}` output.
impl std::fmt::Debug for BlobMaterial {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BlobMaterial")
            .field("blob_id", &self.blob_id)
            .field("blob_key", &"<redacted>")
            .field("blob_nonce", &"<redacted>")
            .finish()
    }
}

/// Build an L2 TEXT frame (`msg_type = 0x10`) from a UTF-8 string.
#[must_use]
pub fn encode_text_frame(text: String) -> Vec<u8> {
    core_payload::encode_text_frame(&text)
}

/// Parse an L2 frame.
///
/// # Errors
/// All variants of `chencang_core::payload::FrameError` are mapped to
/// [`ChencangError::Decoding`] with a human-readable message.
pub fn decode_frame(bytes: Vec<u8>) -> Result<DecodedMessage> {
    let msg =
        core_payload::decode_frame(&bytes).map_err(|e| ChencangError::Decoding(e.to_string()))?;
    Ok(match msg {
        core_payload::DecodedMessage::Text(value) => DecodedMessage::Text { value },
        core_payload::DecodedMessage::MediaRef(refs) => DecodedMessage::Media {
            refs: refs.into_iter().map(MediaRef::from_core).collect(),
        },
    })
}

/// Build an L2 `MEDIA_REF` frame (`msg_type = 0x40`).
///
/// # Errors
/// [`ChencangError::InvalidLength`] for a non-32-byte secret;
/// [`ChencangError::Decoding`] for count/kind/duration violations.
pub fn encode_media_ref_frame(refs: Vec<MediaRef>) -> Result<Vec<u8>> {
    let core_refs = refs
        .iter()
        .map(MediaRef::to_core)
        .collect::<Result<Vec<_>>>()?;
    core_payload::encode_media_ref_frame(&core_refs)
        .map_err(|e| ChencangError::Decoding(e.to_string()))
}

/// Wrap raw L3 ciphertext bytes as `🔒 + Base32768(bytes)`.
#[must_use]
pub fn encode_wire(ciphertext: Vec<u8>) -> String {
    core_payload::encode_wire(&ciphertext)
}

/// Strip the 🔒 prefix and decode Base32768 back to raw bytes.
///
/// # Errors
/// - [`ChencangError::Decoding`] when the input does not start with
///   U+1F512 🔒, or when Base32768 decoding fails.
pub fn decode_wire(s: String) -> Result<Vec<u8>> {
    core_payload::decode_wire(&s).map_err(|e| ChencangError::Decoding(e.to_string()))
}

/// HKDF-Expand-SHA256 derivation of `(blob_id, blob_key, blob_nonce)`.
///
/// # Errors
/// - [`ChencangError::InvalidLength`] when `blob_secret.len() != 32`.
pub fn derive_blob_material(blob_secret: Vec<u8>) -> Result<BlobMaterial> {
    let secret: [u8; 32] = blob_secret.as_slice().try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "blob_secret must be 32 bytes, got {}",
            blob_secret.len()
        ))
    })?;
    let m = core_payload::derive_blob_material(&secret);
    Ok(BlobMaterial {
        blob_id: m.blob_id.to_vec(),
        blob_key: m.blob_key.to_vec(),
        blob_nonce: m.blob_nonce.to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_frame_round_trip() {
        let bytes = encode_text_frame("hello 陈仓".to_string());
        let decoded = decode_frame(bytes).unwrap();
        match decoded {
            DecodedMessage::Text { value } => assert_eq!(value, "hello 陈仓"),
            other @ DecodedMessage::Media { .. } => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn media_ref_frame_round_trip() {
        let r = MediaRef {
            kind: 3,
            dur_ms: 5000,
            width: 1280,
            height: 720,
            byte_len: 123_456,
            blob_secret: vec![9u8; 32],
        };
        let bytes = encode_media_ref_frame(vec![r.clone()]).unwrap();
        match decode_frame(bytes).unwrap() {
            DecodedMessage::Media { refs } => assert_eq!(refs, vec![r]),
            other @ DecodedMessage::Text { .. } => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn media_ref_bad_secret_length_rejected() {
        let r = MediaRef {
            kind: 1,
            dur_ms: 1,
            width: 0,
            height: 0,
            byte_len: 1,
            blob_secret: vec![0u8; 31],
        };
        assert!(matches!(
            encode_media_ref_frame(vec![r]).unwrap_err(),
            ChencangError::InvalidLength(_)
        ));
    }

    #[test]
    fn wire_round_trip() {
        let ct = vec![0xABu8; 80];
        let s = encode_wire(ct.clone());
        let back = decode_wire(s).unwrap();
        assert_eq!(back, ct);
    }

    #[test]
    fn decode_wire_missing_prefix() {
        let err = decode_wire("nope".into()).unwrap_err();
        assert!(matches!(err, ChencangError::Decoding(_)));
    }

    #[test]
    fn invalid_blob_secret_length_rejected() {
        let err = derive_blob_material(vec![0u8; 31]).unwrap_err();
        assert!(matches!(err, ChencangError::InvalidLength(_)));
    }

    #[test]
    fn media_ref_debug_redacts_secret() {
        let secret: Vec<u8> = (0u8..32)
            .map(|i| i.wrapping_mul(7).wrapping_add(0xA0))
            .collect();
        let r = MediaRef {
            kind: 2,
            dur_ms: 0,
            width: 640,
            height: 480,
            byte_len: 4096,
            blob_secret: secret.clone(),
        };
        let direct = format!("{r:?}");
        let nested = format!(
            "{:?}",
            DecodedMessage::Media {
                refs: vec![r.clone()]
            }
        );
        for dbg in [&direct, &nested] {
            assert!(!dbg.contains(&format!("{secret:?}")), "{dbg}");
            assert!(!dbg.contains(&format!("{:?}", &secret[..4])[1..8]), "{dbg}");
            assert!(dbg.contains("<redacted>"), "{dbg}");
            assert!(dbg.contains("byte_len: 4096"), "{dbg}");
        }
    }

    #[test]
    fn blob_material_debug_redacts_key_and_nonce() {
        let m = derive_blob_material(vec![0x5Au8; 32]).unwrap();
        let dbg = format!("{m:?}");
        assert!(!dbg.contains(&format!("{:?}", m.blob_key)), "{dbg}");
        assert!(!dbg.contains(&format!("{:?}", m.blob_nonce)), "{dbg}");
        assert!(dbg.contains("<redacted>"), "{dbg}");
    }

    #[test]
    fn derive_blob_material_lengths() {
        let m = derive_blob_material(vec![0u8; 32]).unwrap();
        assert_eq!(m.blob_id.len(), 16);
        assert_eq!(m.blob_key.len(), 32);
        assert_eq!(m.blob_nonce.len(), 24);
    }
}
