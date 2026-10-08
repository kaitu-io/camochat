package uniffi.chencang

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 payload instrumented tests — mirror Swift PayloadTests.
 *
 * Exercises:
 * - V1 wire codec: encodeTextFrame / decodeFrame / encodeWire / decodeWire
 * - HKDF blob-material derivation
 * - .cca media blob encrypt/decrypt (spec 2026-09-25 rich-media)
 * - 8-emoji safety fingerprint derivation
 */
@RunWith(AndroidJUnit4::class)
class PayloadInstrumentedTest {
    // ─── Media blob crypto (spec 2026-09-25) ─────────────────────────

    @Test
    fun blobRoundTripImage() {
        val plaintext = "jpeg bytes".toByteArray(Charsets.UTF_8)
        val sealed = encryptMediaBlob(plaintext, 2.toUByte())
        assertArrayEquals(byteArrayOf(0x43, 0x43, 0x41, 0x31), sealed.blob.sliceArray(0..3))
        assertEquals(0x02.toByte(), sealed.blob[5])
        assertEquals(32, sealed.blobSecret.size)
        assertEquals(mediaBlobId(sealed.blobSecret), sealed.blobId)
        val round = decryptMediaBlob(sealed.blob, sealed.blobSecret, 2.toUByte())
        assertArrayEquals(plaintext, round)
    }

    @Test
    fun encryptGeneratesDistinctSecretsEachCall() {
        val a = encryptMediaBlob("x".toByteArray(), 2.toUByte())
        val b = encryptMediaBlob("x".toByteArray(), 2.toUByte())
        assertFalse(a.blobSecret.contentEquals(b.blobSecret))
        assertNotEquals(a.blobId, b.blobId)
    }

    @Test
    fun decryptWrongSecretLengthThrows() {
        val sealed = encryptMediaBlob(ByteArray(0), 2.toUByte())
        val badSecret = ByteArray(31) { 0x42.toByte() }
        try {
            decryptMediaBlob(sealed.blob, badSecret, 2.toUByte())
            org.junit.Assert.fail("expected InvalidLength")
        } catch (e: ChencangException.InvalidLength) {
            // OK
        }
    }

    @Test
    fun blobDecryptWrongSecretFails() {
        val wrong = ByteArray(32) { 0xFF.toByte() }
        val sealed = encryptMediaBlob("hello".toByteArray(), 2.toUByte())
        try {
            decryptMediaBlob(sealed.blob, wrong, 2.toUByte())
            org.junit.Assert.fail("expected AeadFailed")
        } catch (e: ChencangException.AeadFailed) {
            // OK
        }
    }

    @Test
    fun blobDecryptKindMismatchFails() {
        val sealed = encryptMediaBlob("hello".toByteArray(), 2.toUByte())
        try {
            decryptMediaBlob(sealed.blob, sealed.blobSecret, 1.toUByte())
            org.junit.Assert.fail("expected Decoding")
        } catch (e: ChencangException.Decoding) {
            // OK
        }
    }

    @Test
    fun mediaBlobIdDeterministic() {
        val secret = ByteArray(32) { 0x42.toByte() }
        assertEquals(mediaBlobId(secret), mediaBlobId(secret))
    }

    // ─── V1 wire codec ──────────────────────────────────────────────

    @Test
    fun wireRoundTrip() {
        val ct = ByteArray(80) { 0xAB.toByte() }
        val s = encodeWire(ct)
        assertTrue("must start with 🔒", s.startsWith("🔒"))
        val back = decodeWire(s)
        assertArrayEquals(ct, back)
    }

    @Test
    fun decodeWireMissingPrefix() {
        try {
            decodeWire("nope")
            org.junit.Assert.fail("expected Decoding")
        } catch (e: ChencangException.Decoding) {
            // OK
        }
    }

    @Test
    fun decodeFrameGarbageFails() {
        val garbage = byteArrayOf(0xFF.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte())
        try {
            decodeFrame(garbage)
            org.junit.Assert.fail("expected Decoding")
        } catch (e: ChencangException.Decoding) {
            // OK
        }
    }

    // ─── HKDF blob-material derivation ──────────────────────────────

    @Test
    fun deriveBlobMaterialDeterministic() {
        val secret = ByteArray(32) { 0x42.toByte() }
        val a = deriveBlobMaterial(secret)
        val b = deriveBlobMaterial(secret)
        assertArrayEquals(a.blobId, b.blobId)
        assertArrayEquals(a.blobKey, b.blobKey)
        assertArrayEquals(a.blobNonce, b.blobNonce)
        assertEquals(16, a.blobId.size)
        assertEquals(32, a.blobKey.size)
        assertEquals(24, a.blobNonce.size)
    }

    @Test
    fun deriveBlobMaterialSpecVector1() {
        val m = deriveBlobMaterial(ByteArray(32))
        assertEquals(
            "31766f35b0acb82ed8cb94d35eae977e",
            m.blobId.joinToString("") { String.format("%02x", it) }
        )
        assertEquals(
            "4b00904f34f16918709cb862aa8225b66514ea91a8d9ca114e14a10ba320f4c9",
            m.blobKey.joinToString("") { String.format("%02x", it) }
        )
        assertEquals(
            "22e9328a3fda5a567c35cb2e0bc19616ce2f7efab72af8e2",
            m.blobNonce.joinToString("") { String.format("%02x", it) }
        )
    }

    // ─── Safety emoji ───────────────────────────────────────────────

    @Test
    fun safetyEmojiLength() {
        val secret = ByteArray(32) { 0xA5.toByte() }
        val emoji = deriveSafetyEmoji(secret)
        assertEquals(8, emoji.size)
        for (e in emoji) {
            assertFalse(e.isEmpty())
        }
    }

    @Test
    fun safetyEmojiWrongLengthThrows() {
        val bad = ByteArray(31)
        try {
            deriveSafetyEmoji(bad)
            org.junit.Assert.fail("expected InvalidLength")
        } catch (e: ChencangException.InvalidLength) {
            // OK
        }
    }

    @Test
    fun safetyEmojiDistinctForDistinctSecrets() {
        val a = deriveSafetyEmoji(ByteArray(32))
        val b = deriveSafetyEmoji(ByteArray(32) { 0xFF.toByte() })
        assertNotEquals(a.toList(), b.toList())
    }
}
