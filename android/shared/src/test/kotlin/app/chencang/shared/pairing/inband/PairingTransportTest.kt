package app.chencang.shared.pairing.inband

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.decodeClassicalBundle
import uniffi.chencang.decodeClassicalHeader
import uniffi.chencang.encodeWire

/**
 * Unit coverage for [PairingTransport] — the 🔒 in-band wire envelope and the
 * session-vs-pairing disambiguator. Exercises real CBOR bundles/headers minted by
 * the [InbandPairing] engine round-tripped through the uniffi wire codec on the
 * JVM (no Android, no network).
 *
 * Native lib guard mirrors [InbandPairingLoopbackTest]: the host bindings dylib
 * must be on `jna.library.path` with the uniffi `libraryOverride` set (wired by
 * shared/build.gradle.kts for `testDebugUnitTest`).
 */
class PairingTransportTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun assumeNativeBindingsAvailable() {
            val override = System.getProperty("uniffi.component.chencang.libraryOverride")
            val jnaPath = System.getProperty("jna.library.path")
            Assume.assumeTrue(
                "host bindings not configured (jna.library.path / libraryOverride absent)",
                override != null && jnaPath != null,
            )
        }
    }

    private fun freshBundleCbor(): ByteArray {
        val aIk = SecretIdentity()
        val aSpk = SecretSignedPreKey(aIk, 1u)
        return InbandPairing.buildInviteBundle(aIk, aSpk, inviterUsername = "alice").bundleBytes
    }

    private fun freshHeaderCbor(): ByteArray {
        val aIk = SecretIdentity()
        val aSpk = SecretSignedPreKey(aIk, 1u)
        val bIk = SecretIdentity()
        val pending = InbandPairing.buildInviteBundle(aIk, aSpk, inviterUsername = "alice")
        val accept = InbandPairing.accept(bIk, pending.bundleBytes, bDisplayName = "bob")
        accept.session.close()
        return accept.headerBytes
    }

    @Test
    fun bundle_wire_roundtrip() {
        val cbor = freshBundleCbor()
        val wire = PairingTransport.bundleToWire(cbor)

        assertThat(wire).startsWith("🔒")
        assertThat(PairingTransport.classify(wire)).isEqualTo(PairingTransport.WireKind.PAIRING_BUNDLE)

        val payload = PairingTransport.pairingPayload(wire)
        assertThat(payload).isEqualTo(cbor)
        // Must still decode as a real classical bundle.
        decodeClassicalBundle(payload)
    }

    @Test
    fun header_wire_roundtrip() {
        val cbor = freshHeaderCbor()
        val wire = PairingTransport.headerToWire(cbor)

        assertThat(wire).startsWith("🔒")
        assertThat(PairingTransport.classify(wire)).isEqualTo(PairingTransport.WireKind.PAIRING_HEADER)

        val payload = PairingTransport.pairingPayload(wire)
        assertThat(payload).isEqualTo(cbor)
        decodeClassicalHeader(payload)
    }

    @Test
    fun session_ciphertext_classified_as_session() {
        // L3 session ciphertext (DR wire, text and media alike) whose first
        // two bytes are the wire MAGIC.
        val fakeSession = encodeWire(byteArrayOf(0xCC.toByte(), 0xC8.toByte()) + ByteArray(20))
        assertThat(PairingTransport.classify(fakeSession)).isEqualTo(PairingTransport.WireKind.SESSION)
    }

    @Test
    fun non_wire_text_is_unknown() {
        assertThat(PairingTransport.classify("hello 你好")).isEqualTo(PairingTransport.WireKind.UNKNOWN)
    }

    @Test
    fun empty_and_short_decoded_payloads_are_unknown_no_crash() {
        assertThat(PairingTransport.classify(encodeWire(ByteArray(0)))).isEqualTo(PairingTransport.WireKind.UNKNOWN)
        assertThat(PairingTransport.classify(encodeWire(byteArrayOf(0xCB.toByte())))).isEqualTo(PairingTransport.WireKind.UNKNOWN)
    }

    @Test
    fun pairing_payload_rejects_non_pairing_wire() {
        val fakeSession = encodeWire(byteArrayOf(0xCC.toByte(), 0xC8.toByte()) + ByteArray(20))
        try {
            PairingTransport.pairingPayload(fakeSession)
            throw AssertionError("expected pairingPayload to reject a session wire")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun size_fits_one_message() {
        val wire = PairingTransport.bundleToWire(freshBundleCbor())
        // The 🔒 + Base32768(envelope + CBOR) must comfortably fit one WeChat
        // message. Proves the in-band paste/QR path is single-blob.
        assertThat(wire.length).isLessThan(250)
    }

    /** 跨端已知向量：与 iOS 同一组（负载字节的 SHA-256 小写十六进制）。 */
    @Test
    fun `invite digest known vector`() {
        val payload = ByteArray(32) { it.toByte() }
        assertThat(payload.joinToString("") { "%02x".format(it) })
            .isEqualTo("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val wire = PairingTransport.bundleToWire(payload)
        assertThat(PairingTransport.inviteDigest(wire))
            .isEqualTo("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd")
    }
}
