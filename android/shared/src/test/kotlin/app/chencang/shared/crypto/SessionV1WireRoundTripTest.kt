package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import uniffi.chencang.DecodedMessage
import uniffi.chencang.decodeFrame
import uniffi.chencang.decodeWire
import uniffi.chencang.encodeTextFrame
import uniffi.chencang.encodeWire

/**
 * In-process round-trip for the V1 wire codec on the JVM.
 *
 * Mints two PQXDH sessions, encodes a text frame, runs the full sender flow
 * (encode TEXT frame → Session.encryptToBytes → encodeWire), then reverses it
 * on the receiver session and asserts the recovered text matches.
 *
 * Native lib loading: the JVM test JVM is configured with
 * `jna.library.path=<repo>/target/release` and
 * `uniffi.component.chencang.libraryOverride=chencang_bindings` (see
 * `shared/build.gradle.kts`), so the host-built `libchencang_bindings.dylib`
 * is loaded in place of the Android `libuniffi_chencang.so`.
 */
class SessionV1WireRoundTripTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun assumeNativeBindingsAvailable() {
            // Skip cleanly when the host dylib hasn't been built or the JVM
            // arch can't load what's available — the test asks the build to
            // wire `jna.library.path` + `libraryOverride` and falls over if
            // either is missing. CI matrices that don't build the host crate
            // (e.g., a pure-Android instrumentation lane) should be allowed
            // to run the rest of the unit-test suite.
            val arch = System.getProperty("os.arch", "")
            val override = System.getProperty(
                "uniffi.component.chencang.libraryOverride",
            )
            val jnaPath = System.getProperty("jna.library.path")
            Assume.assumeTrue(
                "host bindings not configured (jna.library.path / libraryOverride absent; arch=$arch)",
                override != null && jnaPath != null,
            )
        }
    }

    @Test
    fun `V1 TEXT frame round-trips through PQXDH session pair and wire codec`() {
        val pair = PqxdhHandshakeFixture.mintLoopbackSessionPair()
        try {
            val text = "陈仓 UAT round-trip 🔒"

            val frame = encodeTextFrame(text)

            val ct = pair.initiator.encryptToBytes(frame)
            val wire = encodeWire(ct)

            // String.length counts UTF-16 code units, and 🔒 (U+1F512) is a
            // surrogate pair (2 units). Compute the code-point count so the
            // log matches the spec's *visible* char units.
            val visibleChars = wire.codePointCount(0, wire.length)
            println(
                "[V1 round-trip] wire length: $visibleChars visible chars " +
                    "(${wire.length} UTF-16 units), frame=${frame.size}B, ct=${ct.size}B",
            )
            println("[V1 round-trip] wire prefix: ${wire.take(8)}…")

            assertThat(wire).startsWith("🔒") // U+1F512 🔒, surrogate pair

            // Reverse on receiver side.
            val ct2 = decodeWire(wire)
            assertThat(ct2).isEqualTo(ct)

            val frame2 = pair.responder.decryptFromBytes(ct2)
            assertThat(frame2).isEqualTo(frame)

            val msg = decodeFrame(frame2)
            assertThat(msg).isInstanceOf(DecodedMessage.Text::class.java)
            val decoded = msg as DecodedMessage.Text
            assertThat(decoded.value).isEqualTo(text)
        } finally {
            pair.closeAll()
        }
    }
}
