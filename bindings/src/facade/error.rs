//! Facade error type. Maps `chencang_core::Error` into a flat
//! variant set suitable for cross-language enums.

use chencang_core::error::Error as CoreError;
use thiserror::Error;

/// All errors surfaced through the UDL boundary.
///
/// Variants are flat (no inner payloads) to keep the generated Swift / Kotlin
/// enums simple. The `Display` impl carries human-readable detail.
#[derive(Debug, Error)]
pub enum ChencangError {
    /// Input byte buffer had the wrong length.
    #[error("invalid length: {0}")]
    InvalidLength(String),
    /// Wire format suite identifier unsupported.
    #[error("unsupported suite")]
    UnsupportedSuite,
    /// Wire format version unsupported.
    #[error("unsupported version")]
    UnsupportedVersion,
    /// AEAD authentication failed (tamper, wrong key, etc.).
    #[error("AEAD verification failed")]
    AeadFailed,
    /// Signature verification failed.
    #[error("signature verification failed")]
    SignatureFailed,
    /// Decoding error (URL / serialized blob / wire format).
    #[error("decoding error: {0}")]
    Decoding(String),
    /// Internal invariant violation (should not occur).
    #[error("internal error: {0}")]
    Internal(String),
}

impl From<CoreError> for ChencangError {
    fn from(e: CoreError) -> Self {
        match e {
            CoreError::InvalidLength { expected, got } => {
                Self::InvalidLength(format!("expected {expected} got {got}"))
            }
            CoreError::UnsupportedSuite(_) => Self::UnsupportedSuite,
            CoreError::UnsupportedVersion(_) => Self::UnsupportedVersion,
            CoreError::AeadFailed => Self::AeadFailed,
            CoreError::SignatureFailed => Self::SignatureFailed,
            CoreError::Decoding(s) => Self::Decoding(s),
            CoreError::Internal(s) => Self::Internal(s),
        }
    }
}

/// Convenience alias used throughout the facade.
pub type Result<T> = std::result::Result<T, ChencangError>;
