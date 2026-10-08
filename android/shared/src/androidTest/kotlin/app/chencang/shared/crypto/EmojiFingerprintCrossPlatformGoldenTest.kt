package app.chencang.shared.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-platform Known-Answer-Tests pinning [EmojiFingerprint] output to the
 * canonical Rust-side KAT vectors in `core/src/safety/emoji.rs` (the
 * `KAT_VECTORS` array, locked by `EMOJI_DICTIONARY_HASH`).
 *
 * These vectors MUST match byte-for-byte with the Rust and iOS sides — any
 * divergence means the chencang-core AAR's native code has drifted from the
 * source-of-truth dictionary, which would break the safety-number verification
 * feature across paired Android↔iOS clients.
 *
 * If one of these tests fails on Android, do NOT change the expected values
 * here. Open `core/src/safety/emoji.rs` and confirm the canonical
 * vectors; the bug is in the chencang-bindings AAR build.
 */
@RunWith(AndroidJUnit4::class)
class EmojiFingerprintCrossPlatformGoldenTest {

    @Test
    fun kat_zeros_32_zero_bytes() {
        val secret = ByteArray(32) { 0 }
        val expected = listOf("🎚", "💸", "✒", "🌤", "🛺", "⛷", "🏗", "🌲")
        assertThat(EmojiFingerprint.derive(secret)).isEqualTo(expected)
    }

    @Test
    fun kat_ones_32_0xFF_bytes() {
        val secret = ByteArray(32) { 0xFF.toByte() }
        val expected = listOf("🤸", "🥅", "📞", "🌏", "🚤", "🌃", "📝", "🥓")
        assertThat(EmojiFingerprint.derive(secret)).isEqualTo(expected)
    }

    @Test
    fun kat_alternating_0123456789abcdef_repeated_four_times() {
        // pattern: 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef — repeated 4× = 32 bytes
        val pattern = byteArrayOf(
            0x01,
            0x23,
            0x45,
            0x67,
            0x89.toByte(),
            0xab.toByte(),
            0xcd.toByte(),
            0xef.toByte(),
        )
        val secret = ByteArray(32) { i -> pattern[i % 8] }
        val expected = listOf("🏋", "📽", "💿", "🥈", "🌱", "📥", "🚕", "💮")
        assertThat(EmojiFingerprint.derive(secret)).isEqualTo(expected)
    }
}
