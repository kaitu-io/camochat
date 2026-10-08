package app.chencang.shared.crypto

import uniffi.chencang.deriveSafetyEmoji as nativeDeriveSafetyEmoji

/**
 * 8-emoji safety fingerprint over a 32-byte session secret.
 *
 * Thin wrapper around `uniffi.chencang.deriveSafetyEmoji` (chencang-core ≥0.2.0-rc1).
 * The native binding is the SINGLE SOURCE OF TRUTH for the algorithm AND the
 * 512-entry emoji dictionary (pinned in Rust by [`EMOJI_DICTIONARY_HASH`]). Any
 * pure-Kotlin reimplementation here would silently diverge from iOS / Rust and
 * break the safety-number verification feature — opening a man-in-the-middle
 * window. Don't do it.
 *
 * Cross-platform Known-Answer-Tests live in
 * `EmojiFingerprintCrossPlatformGoldenTest` and pin the binding output to the
 * Rust-source KAT in `core/src/safety/emoji.rs`.
 */
object EmojiFingerprint {

    /**
     * Derive 8 safety emojis from a 32-byte session secret.
     *
     * @param sessionSecret exactly 32 bytes
     * @return list of exactly 8 emoji strings (each is one grapheme cluster).
     *   Byte-identical to iOS and Rust outputs.
     */
    fun derive(sessionSecret: ByteArray): List<String> {
        require(sessionSecret.size == 32) {
            "sessionSecret must be 32 bytes, got ${sessionSecret.size}"
        }
        return nativeDeriveSafetyEmoji(sessionSecret)
    }
}
