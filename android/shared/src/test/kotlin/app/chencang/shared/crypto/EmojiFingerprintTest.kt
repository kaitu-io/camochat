package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM-only unit tests for [EmojiFingerprint]. Only exercises the Kotlin
 * input-validation path that can run without the native uniffi `.so` (those
 * `.so` are Android-only and only load on a real device/emulator).
 *
 * Behavioral and Known-Answer-Test (KAT) coverage that actually invokes
 * `uniffi.chencang.deriveSafetyEmoji` lives in
 * `shared/src/androidTest/.../EmojiFingerprintBindingTest.kt` and
 * `EmojiFingerprintCrossPlatformGoldenTest.kt`.
 */
class EmojiFingerprintTest {

    @Test
    fun `rejects wrong-size secret`() {
        try {
            EmojiFingerprint.derive(ByteArray(16))
            assertThat("should have thrown").isEmpty()
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("32 bytes")
        }
    }

    @Test
    fun `rejects empty secret`() {
        try {
            EmojiFingerprint.derive(ByteArray(0))
            assertThat("should have thrown").isEmpty()
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("32 bytes")
        }
    }
}
