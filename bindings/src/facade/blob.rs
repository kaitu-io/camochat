//! UDL facade wrapping `chencang_core::blob` media-blob entry points.

use chencang_core::blob;

use super::error::{ChencangError, Result};
use crate::rng::os_rng;

fn secret32(blob_secret: &[u8]) -> Result<[u8; 32]> {
    blob_secret.try_into().map_err(|_| {
        ChencangError::InvalidLength(format!(
            "blob_secret must be 32 bytes, got {}",
            blob_secret.len()
        ))
    })
}

/// Output of [`encrypt_media_blob`]. Mirrors `chencang_core::blob::SealedMedia`
/// with `blob_secret` as a byte vector for the FFI boundary.
#[derive(Clone, PartialEq, Eq)]
pub struct MediaBlob {
    /// 32-byte CSPRNG secret generated for this blob. Never reused.
    pub blob_secret: Vec<u8>,
    /// Complete `.cca` blob (header + ciphertext + tag) ready to upload.
    pub blob: Vec<u8>,
    /// `media_blob_id(blob_secret)`, precomputed.
    pub blob_id: String,
}

// `blob_secret` is capability-bearing; never let it leak into a log via `{:?}`.
impl std::fmt::Debug for MediaBlob {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MediaBlob")
            .field("blob_secret", &"<redacted>")
            .field("blob", &format!("<{} bytes>", self.blob.len()))
            .field("blob_id", &self.blob_id)
            .finish()
    }
}

/// Encrypt a media file into a `.cca` blob. Generates a fresh 32-byte
/// `blob_secret` via the platform CSPRNG (`OsRng`) — callers can no longer
/// supply their own secret, which would risk `XChaCha20` nonce reuse if the
/// same secret were ever passed twice (the nonce is derived from the
/// secret, so secret uniqueness is what keeps the nonce unique).
///
/// # Errors
/// [`ChencangError::Decoding`] for an unknown kind, or when `plaintext` is
/// too large for `kind`'s max blob size.
pub fn encrypt_media_blob(plaintext: Vec<u8>, kind: u8) -> Result<MediaBlob> {
    let mut rng = os_rng();
    let sealed = blob::encrypt_media_blob(&mut rng, &plaintext, kind)?;
    Ok(MediaBlob {
        blob_secret: sealed.blob_secret.to_vec(),
        blob: sealed.blob,
        blob_id: sealed.blob_id,
    })
}

/// Decrypt a downloaded `.cca` blob, checking it matches `expected_kind`.
///
/// # Errors
/// [`ChencangError::InvalidLength`], [`ChencangError::Decoding`] (kind
/// mismatch / malformed), [`ChencangError::AeadFailed`] (tamper).
pub fn decrypt_media_blob(
    blob: Vec<u8>,
    blob_secret: Vec<u8>,
    expected_kind: u8,
) -> Result<Vec<u8>> {
    Ok(blob::decrypt_media_blob(
        &blob,
        &secret32(&blob_secret)?,
        expected_kind,
    )?)
}

/// 22-char base64url object id for `blob_secret`.
///
/// # Errors
/// [`ChencangError::InvalidLength`] for a non-32-byte secret.
pub fn media_blob_id(blob_secret: Vec<u8>) -> Result<String> {
    Ok(blob::media_blob_id(&secret32(&blob_secret)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_round_trip() {
        let sealed = encrypt_media_blob(b"img".to_vec(), 2).unwrap();
        assert_eq!(
            decrypt_media_blob(sealed.blob, sealed.blob_secret, 2).unwrap(),
            b"img"
        );
    }

    #[test]
    fn encrypt_generates_fresh_secret_and_matching_id() {
        let a = encrypt_media_blob(b"img".to_vec(), 2).unwrap();
        let b = encrypt_media_blob(b"img".to_vec(), 2).unwrap();
        assert_ne!(a.blob_secret, b.blob_secret);
        assert_eq!(a.blob_id, media_blob_id(a.blob_secret.clone()).unwrap());
        assert_ne!(a.blob_id, b.blob_id);
    }

    #[test]
    fn media_blob_debug_redacts_secret() {
        let sealed = encrypt_media_blob(b"img".to_vec(), 2).unwrap();
        let dbg = format!("{sealed:?}");
        assert!(!dbg.contains(&format!("{:?}", sealed.blob_secret)), "{dbg}");
        assert!(dbg.contains("redacted"), "{dbg}");
    }

    #[test]
    fn kind_mismatch_is_decoding() {
        let sealed = encrypt_media_blob(b"img".to_vec(), 2).unwrap();
        assert!(matches!(
            decrypt_media_blob(sealed.blob, sealed.blob_secret, 1).unwrap_err(),
            ChencangError::Decoding(_)
        ));
    }

    #[test]
    fn short_secret_is_invalid_length_on_decrypt() {
        let sealed = encrypt_media_blob(b"img".to_vec(), 2).unwrap();
        assert!(matches!(
            decrypt_media_blob(sealed.blob, vec![0u8; 31], 2).unwrap_err(),
            ChencangError::InvalidLength(_)
        ));
        assert!(matches!(
            media_blob_id(vec![0u8; 33]).unwrap_err(),
            ChencangError::InvalidLength(_)
        ));
    }

    #[test]
    fn encrypt_rejects_unknown_kind() {
        assert!(encrypt_media_blob(b"x".to_vec(), 0x04).is_err());
    }
}
