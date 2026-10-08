package app.chencang.shared.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented behavioral tests for [EmojiFingerprint] that actually load the
 * native `libuniffi_chencang.so` from the chencang-core-android AAR and call
 * `uniffi.chencang.deriveSafetyEmoji`.
 *
 * Cross-platform Known-Answer-Tests pinned against the Rust source live in
 * [EmojiFingerprintCrossPlatformGoldenTest] (same package, alongside).
 */
@RunWith(AndroidJUnit4::class)
class EmojiFingerprintBindingTest {

    @Test
    fun derives_exactly_eight_emojis() {
        val secret = ByteArray(32) { it.toByte() }
        val emojis = EmojiFingerprint.derive(secret)
        assertThat(emojis).hasSize(8)
    }

    @Test
    fun derivation_is_deterministic() {
        val secret = ByteArray(32) { 7 }
        val first = EmojiFingerprint.derive(secret)
        val second = EmojiFingerprint.derive(secret)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun different_secrets_yield_different_fingerprints() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertThat(EmojiFingerprint.derive(b)).isNotEqualTo(EmojiFingerprint.derive(a))
    }
}
