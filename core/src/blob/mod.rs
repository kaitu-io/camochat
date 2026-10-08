//! `.cca` media blob format — XChaCha20-Poly1305 encrypted voice / image /
//! video, stored in object storage by the rich-media path
//! (`docs/protocol/README.md`, media container section).
//!
//! ## Layout
//! ```text
//! magic[4]       = b"CCA1"
//! version[1]     = 0x01
//! kind[1]        = 0x01 voice | 0x02 image | 0x03 video  (payload::MEDIA_KIND_*)
//! nonce[24]      = XChaCha20 nonce (derived from blob_secret on the media path)
//! cipher_len[4]  = big-endian u32
//! ciphertext[N]  = XChaCha20-Poly1305 output (plaintext + 16-byte tag)
//! ```
//! AAD = `magic || version || kind` (6 bytes).
//!
//! The 32-byte `blob_secret` travels inside the DR-encrypted `MEDIA_REF` frame;
//! the object store only ever sees ciphertext and a derived, unlinkable id.

use base64::Engine as _;
use rand_core::{CryptoRng, RngCore};

use crate::error::{Error, Result};
use crate::payload::{derive_blob_material, max_blob_len_for_kind};
use crate::primitives::aead;

/// Four-byte magic prefix.
pub const CCA_MAGIC: &[u8; 4] = b"CCA1";
/// Format version byte.
pub const CCA_VERSION: u8 = 1;
/// Fixed header byte length: magic(4)+version(1)+kind(1)+nonce(24)+`cipher_len`(4).
pub const CCA_HEADER_LEN: usize = 34;

fn build_aad(kind: u8) -> [u8; 6] {
    [b'C', b'C', b'A', b'1', CCA_VERSION, kind]
}

/// Seal `plaintext` into a complete `.cca` blob with an explicit key/nonce.
///
/// # Errors
/// [`Error::Internal`] if AEAD seal fails or the ciphertext exceeds `u32`.
pub fn seal_cca(plaintext: &[u8], key: &[u8; 32], nonce: &[u8; 24], kind: u8) -> Result<Vec<u8>> {
    let aad = build_aad(kind);
    let ciphertext = aead::seal(key, nonce, &aad, plaintext)?;
    let cipher_len = u32::try_from(ciphertext.len())
        .map_err(|_| Error::Internal("ciphertext too large for u32".into()))?;
    let mut out = Vec::with_capacity(CCA_HEADER_LEN + ciphertext.len());
    out.extend_from_slice(CCA_MAGIC);
    out.push(CCA_VERSION);
    out.push(kind);
    out.extend_from_slice(nonce);
    out.extend_from_slice(&cipher_len.to_be_bytes());
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Open a `.cca` blob, returning `(kind, plaintext)`.
///
/// # Errors
/// - [`Error::Decoding`] — too short, wrong magic/version, `cipher_len` overflow
/// - [`Error::AeadFailed`] — authentication tag mismatch (incl. rewritten kind)
///
/// # Panics
/// Indexes into validated 24- / 4-byte slices use `expect`; the bounds
/// have already been checked by `blob.len() >= CCA_HEADER_LEN`.
pub fn open_cca(blob: &[u8], key: &[u8; 32]) -> Result<(u8, Vec<u8>)> {
    if blob.len() < CCA_HEADER_LEN {
        return Err(Error::Decoding(format!(
            "cca blob too short: {} bytes (min {CCA_HEADER_LEN})",
            blob.len()
        )));
    }
    if &blob[..4] != CCA_MAGIC {
        return Err(Error::Decoding("cca magic mismatch".into()));
    }
    if blob[4] != CCA_VERSION {
        return Err(Error::Decoding(format!(
            "unsupported cca version 0x{:02x}",
            blob[4]
        )));
    }
    let kind = blob[5];
    let nonce: &[u8; 24] = blob[6..30].try_into().expect("slice is 24 bytes");
    let cipher_len = u32::from_be_bytes(blob[30..34].try_into().expect("4 bytes")) as usize;
    let end = CCA_HEADER_LEN
        .checked_add(cipher_len)
        .ok_or_else(|| Error::Decoding("cipher_len overflow".into()))?;
    // Exact match, not `end > blob.len()`: trailing bytes after a
    // structurally valid header must be rejected too, not silently ignored.
    if end != blob.len() {
        return Err(Error::Decoding(format!(
            "cipher_len {cipher_len} does not match remaining {} bytes",
            blob.len() - CCA_HEADER_LEN
        )));
    }
    let plaintext = aead::open(key, nonce, &build_aad(kind), &blob[CCA_HEADER_LEN..end])?;
    Ok((kind, plaintext))
}

/// Output of [`encrypt_media_blob`]: a freshly generated per-blob secret
/// plus the sealed `.cca` bytes and the object-store id derived from that
/// secret. The caller is the only place `blob_secret` exists in plaintext
/// outside this call — it travels onward inside the DR-encrypted
/// `MEDIA_REF` frame ([`crate::payload::MediaRef::blob_secret`]).
#[derive(Clone, PartialEq, Eq)]
pub struct SealedMedia {
    /// 32-byte CSPRNG secret generated for this blob. Never reused.
    pub blob_secret: [u8; 32],
    /// Complete `.cca` blob (header + ciphertext + tag) ready to upload.
    pub blob: Vec<u8>,
    /// `media_blob_id(&blob_secret)` — precomputed so the caller doesn't
    /// need to derive it again to know where to `PUT` `blob`.
    pub blob_id: String,
}

// `blob_secret` is capability-bearing (whoever holds it can decrypt the
// blob and derive its object-store id); never let it leak into a log via
// `{:?}`.
impl std::fmt::Debug for SealedMedia {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SealedMedia")
            .field("blob_secret", &"<redacted>")
            .field("blob", &format!("<{} bytes>", self.blob.len()))
            .field("blob_id", &self.blob_id)
            .finish()
    }
}

/// Encrypt a media file for upload. Generates a fresh 32-byte `blob_secret`
/// via `rng` (never caller-supplied — see spec's key-management rationale:
/// a caller-chosen secret with the derived, deterministic nonce would allow
/// nonce reuse if the same secret were ever passed twice); key and nonce
/// are derived from that secret, so the deterministic nonce is safe because
/// the secret is single-use by construction.
///
/// # Errors
/// [`Error::Decoding`] for an unknown `kind`, or when the sealed size
/// (header + `plaintext.len()` + 16-byte tag) would exceed the per-kind
/// maximum (`payload::MEDIA_MAX_BLOB_LEN_*`); otherwise as [`seal_cca`].
pub fn encrypt_media_blob<R: RngCore + CryptoRng>(
    rng: &mut R,
    plaintext: &[u8],
    kind: u8,
) -> Result<SealedMedia> {
    let max = max_blob_len_for_kind(kind)
        .ok_or_else(|| Error::Decoding(format!("unknown media kind 0x{kind:02x}")))?;
    let sealed_len = (CCA_HEADER_LEN as u64) + (plaintext.len() as u64) + 16;
    if sealed_len > u64::from(max) {
        return Err(Error::Decoding(format!(
            "plaintext too large for kind 0x{kind:02x}: sealed size {sealed_len} exceeds max {max}"
        )));
    }
    let mut blob_secret = [0u8; 32];
    rng.fill_bytes(&mut blob_secret);
    let m = derive_blob_material(&blob_secret);
    let blob = seal_cca(plaintext, &m.blob_key, &m.blob_nonce, kind)?;
    let blob_id = media_blob_id(&blob_secret);
    Ok(SealedMedia {
        blob_secret,
        blob,
        blob_id,
    })
}

/// Decrypt a downloaded media blob and check it is the kind the `MEDIA_REF`
/// frame declared (an image blob must never play as voice).
///
/// # Errors
/// [`Error::Decoding`] on kind mismatch or malformed header;
/// [`Error::AeadFailed`] on tamper / wrong secret.
pub fn decrypt_media_blob(
    blob: &[u8],
    blob_secret: &[u8; 32],
    expected_kind: u8,
) -> Result<Vec<u8>> {
    let m = derive_blob_material(blob_secret);
    let (kind, plaintext) = open_cca(blob, &m.blob_key)?;
    if kind != expected_kind {
        return Err(Error::Decoding(format!(
            "media kind mismatch: blob 0x{kind:02x}, expected 0x{expected_kind:02x}"
        )));
    }
    Ok(plaintext)
}

/// Object-store id for a blob: base64url (no padding) of the derived
/// 16-byte `blob_id` — always 22 characters of `[A-Za-z0-9_-]`.
#[must_use]
pub fn media_blob_id(blob_secret: &[u8; 32]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(derive_blob_material(blob_secret).blob_id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::payload::{
        derive_blob_material, MEDIA_KIND_IMAGE, MEDIA_KIND_VIDEO, MEDIA_KIND_VOICE,
        MEDIA_MAX_BLOB_LEN_IMAGE, MEDIA_MAX_BLOB_LEN_VIDEO, MEDIA_MAX_BLOB_LEN_VOICE,
    };
    use rand_chacha::ChaCha20Rng;
    use rand_core::SeedableRng;

    const K: [u8; 32] = [0x42u8; 32];
    const N: [u8; 24] = [0x11u8; 24];
    const S: [u8; 32] = [0x5Au8; 32];

    /// Deterministic RNG for tests — never `OsRng` (see plan brief F2).
    fn test_rng() -> ChaCha20Rng {
        ChaCha20Rng::seed_from_u64(0xC0FF_EE42)
    }

    #[test]
    fn seal_open_round_trip_keeps_kind() {
        let blob = seal_cca(b"fake opus frame", &K, &N, MEDIA_KIND_VOICE).unwrap();
        assert_eq!(&blob[..4], CCA_MAGIC);
        assert_eq!(blob[4], CCA_VERSION);
        assert_eq!(blob[5], MEDIA_KIND_VOICE);
        assert_eq!(&blob[6..30], &N);
        assert_eq!(
            open_cca(&blob, &K).unwrap(),
            (MEDIA_KIND_VOICE, b"fake opus frame".to_vec())
        );
    }

    #[test]
    fn empty_plaintext_round_trip() {
        let blob = seal_cca(&[], &K, &N, MEDIA_KIND_IMAGE).unwrap();
        assert_eq!(open_cca(&blob, &K).unwrap().1, [] as [u8; 0]);
    }

    #[test]
    fn wrong_key_returns_aead_failed() {
        let blob = seal_cca(b"x", &K, &N, MEDIA_KIND_VOICE).unwrap();
        assert!(matches!(
            open_cca(&blob, &[0xFFu8; 32]).unwrap_err(),
            Error::AeadFailed
        ));
    }

    #[test]
    fn tampered_ciphertext_returns_aead_failed() {
        let mut blob = seal_cca(b"audio", &K, &N, MEDIA_KIND_VOICE).unwrap();
        *blob.last_mut().unwrap() ^= 0xFF;
        assert!(matches!(
            open_cca(&blob, &K).unwrap_err(),
            Error::AeadFailed
        ));
    }

    #[test]
    fn rewritten_kind_byte_fails_aead() {
        // kind is in the AAD: flipping it in the header must break the tag.
        let mut blob = seal_cca(b"img", &K, &N, MEDIA_KIND_IMAGE).unwrap();
        blob[5] = MEDIA_KIND_VOICE;
        assert!(matches!(
            open_cca(&blob, &K).unwrap_err(),
            Error::AeadFailed
        ));
    }

    #[test]
    fn wrong_magic_and_too_short_return_decoding() {
        let mut blob = seal_cca(b"x", &K, &N, MEDIA_KIND_VOICE).unwrap();
        blob[0] = 0xFF;
        assert!(matches!(
            open_cca(&blob, &K).unwrap_err(),
            Error::Decoding(_)
        ));
        assert!(matches!(
            open_cca(&[0u8; 10], &K).unwrap_err(),
            Error::Decoding(_)
        ));
    }

    #[test]
    fn media_blob_uses_derived_key_and_nonce_and_generates_secret() {
        let mut rng = test_rng();
        let sealed = encrypt_media_blob(&mut rng, b"jpeg bytes", MEDIA_KIND_IMAGE).unwrap();
        let m = derive_blob_material(&sealed.blob_secret);
        assert_eq!(&sealed.blob[6..30], &m.blob_nonce);
        assert_eq!(sealed.blob_id, media_blob_id(&sealed.blob_secret));
        assert_eq!(
            open_cca(&sealed.blob, &m.blob_key).unwrap(),
            (MEDIA_KIND_IMAGE, b"jpeg bytes".to_vec())
        );
        assert_eq!(
            decrypt_media_blob(&sealed.blob, &sealed.blob_secret, MEDIA_KIND_IMAGE).unwrap(),
            b"jpeg bytes"
        );
    }

    #[test]
    fn encrypt_media_blob_generates_distinct_secrets_each_call() {
        let mut rng = test_rng();
        let a = encrypt_media_blob(&mut rng, b"x", MEDIA_KIND_IMAGE).unwrap();
        let b = encrypt_media_blob(&mut rng, b"x", MEDIA_KIND_IMAGE).unwrap();
        assert_ne!(a.blob_secret, b.blob_secret);
        assert_ne!(a.blob_id, b.blob_id);
        // Same plaintext + kind, different (derived) nonce/key -> different ciphertext.
        assert_ne!(a.blob, b.blob);
    }

    #[test]
    fn sealed_media_debug_redacts_secret() {
        let mut rng = test_rng();
        let sealed = encrypt_media_blob(&mut rng, b"x", MEDIA_KIND_IMAGE).unwrap();
        let dbg = format!("{sealed:?}");
        assert!(!dbg.contains(&hex::encode(sealed.blob_secret)), "{dbg}");
        assert!(dbg.contains("redacted"), "{dbg}");
    }

    #[test]
    fn decrypt_rejects_kind_mismatch() {
        let mut rng = test_rng();
        let sealed = encrypt_media_blob(&mut rng, b"jpeg bytes", MEDIA_KIND_IMAGE).unwrap();
        let err =
            decrypt_media_blob(&sealed.blob, &sealed.blob_secret, MEDIA_KIND_VOICE).unwrap_err();
        assert!(matches!(err, Error::Decoding(_)), "got {err:?}");
    }

    #[test]
    fn encrypt_rejects_unknown_kind() {
        let mut rng = test_rng();
        assert!(encrypt_media_blob(&mut rng, b"x", 0x04).is_err());
        assert!(encrypt_media_blob(&mut rng, b"x", 0x00).is_err());
    }

    #[test]
    fn video_round_trip_one_mib() {
        let mut rng = test_rng();
        let video = vec![0xA5u8; 1 << 20];
        let sealed = encrypt_media_blob(&mut rng, &video, MEDIA_KIND_VIDEO).unwrap();
        assert_eq!(sealed.blob.len(), CCA_HEADER_LEN + video.len() + 16);
        assert_eq!(
            decrypt_media_blob(&sealed.blob, &sealed.blob_secret, MEDIA_KIND_VIDEO).unwrap(),
            video
        );
    }

    // ─── F4: size bounds (spec 2026-09-25 §2.2 / lambda LIMITS) ───────

    #[test]
    fn encrypt_media_blob_accepts_exact_max_and_rejects_one_over() {
        let mut rng = test_rng();
        let max_plain_len = (MEDIA_MAX_BLOB_LEN_IMAGE as usize) - CCA_HEADER_LEN - 16;
        let ok = vec![0u8; max_plain_len];
        let sealed = encrypt_media_blob(&mut rng, &ok, MEDIA_KIND_IMAGE).unwrap();
        assert_eq!(
            u32::try_from(sealed.blob.len()).unwrap(),
            MEDIA_MAX_BLOB_LEN_IMAGE
        );

        let over = vec![0u8; max_plain_len + 1];
        assert!(matches!(
            encrypt_media_blob(&mut rng, &over, MEDIA_KIND_IMAGE).unwrap_err(),
            Error::Decoding(_)
        ));
    }

    #[test]
    fn encrypt_media_blob_enforces_per_kind_max_independently() {
        let mut rng = test_rng();
        let voice_over = vec![0u8; (MEDIA_MAX_BLOB_LEN_VOICE as usize) - CCA_HEADER_LEN - 16 + 1];
        assert!(encrypt_media_blob(&mut rng, &voice_over, MEDIA_KIND_VOICE).is_err());

        let video_max = vec![0u8; (MEDIA_MAX_BLOB_LEN_VIDEO as usize) - CCA_HEADER_LEN - 16];
        assert!(encrypt_media_blob(&mut rng, &video_max, MEDIA_KIND_VIDEO).is_ok());
    }

    #[test]
    fn open_cca_rejects_trailing_byte() {
        let mut blob = seal_cca(b"x", &K, &N, MEDIA_KIND_VOICE).unwrap();
        blob.push(0);
        assert!(matches!(
            open_cca(&blob, &K).unwrap_err(),
            Error::Decoding(_)
        ));
    }

    #[test]
    fn media_blob_id_is_22_char_base64url_of_derived_id() {
        let id = media_blob_id(&S);
        assert_eq!(id.len(), 22);
        assert!(id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_'));
        let raw = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(&id)
            .unwrap();
        assert_eq!(raw, derive_blob_material(&S).blob_id);
    }

    #[test]
    fn header_layout_constants_are_consistent() {
        let blob = seal_cca(b"x", &K, &N, MEDIA_KIND_VOICE).unwrap();
        let cipher_len = u32::from_be_bytes(blob[30..34].try_into().unwrap()) as usize;
        assert_eq!(blob.len(), CCA_HEADER_LEN + cipher_len);
        assert_eq!(cipher_len, 1 + 16);
    }
}
