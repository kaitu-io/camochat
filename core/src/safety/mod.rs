//! Safety number / emoji fingerprint derivation.
//!
//! Derives an 8-emoji fingerprint from a 32-byte shared session secret.
//! The fingerprint is meant to be read aloud (out of band) to verify that
//! both ends of a session share the same key material — a defense against
//! man-in-the-middle attacks during pairing.
//!
//! Algorithm (deterministic, no randomness):
//! 1. `hash = blake2b(session_secret, key=[], dkLen=9)` → 9 bytes = 72 bits
//! 2. Pack into a 72-bit integer (big-endian)
//! 3. Extract 8 × 9-bit segments (each in `0..512`)
//! 4. Each segment indexes [`emoji::EMOJI_DICTIONARY`]
//!
//! The dictionary is locked by [`emoji::EMOJI_DICTIONARY_HASH`] (32-byte
//! `BLAKE2b` over the canonical-JSON representation), so non-Rust
//! implementations can verify they're using a byte-identical table.

pub mod emoji;

pub use emoji::{derive_safety_emoji, EMOJI_DICTIONARY, EMOJI_DICTIONARY_HASH};
