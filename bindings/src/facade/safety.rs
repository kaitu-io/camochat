//! UDL facade wrapping `chencang_core::safety::emoji::derive_safety_emoji`.

use chencang_core::safety::emoji::derive_safety_emoji_from_slice;

use super::error::Result;

/// Derive an 8-emoji safety fingerprint from a 32-byte session secret.
///
/// # Errors
/// Returns [`ChencangError::InvalidLength`] when `session_secret` is not 32 bytes.
pub fn derive_safety_emoji(session_secret: Vec<u8>) -> Result<Vec<String>> {
    let arr = derive_safety_emoji_from_slice(&session_secret)?;
    Ok(arr.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn returns_eight_emoji() {
        let secret = vec![0xA5u8; 32];
        let v = derive_safety_emoji(secret).unwrap();
        assert_eq!(v.len(), 8);
        for e in &v {
            assert_ne!(e, "");
        }
    }

    #[test]
    fn wrong_length_rejected() {
        let err = derive_safety_emoji(vec![0u8; 31]).unwrap_err();
        assert!(matches!(
            err,
            super::super::error::ChencangError::InvalidLength(_)
        ));
    }

    #[test]
    fn distinct_secrets_differ() {
        let a = derive_safety_emoji(vec![0x00u8; 32]).unwrap();
        let b = derive_safety_emoji(vec![0xFFu8; 32]).unwrap();
        assert_ne!(a, b);
    }
}
