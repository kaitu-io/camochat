package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * Pure JVM unit tests for [HandshakeHeader] CBOR codec.
 * No Android or uniffi dependencies needed.
 */
class HandshakeHeaderTest {

    private fun randomBytes(size: Int): ByteArray = Random.nextBytes(size)

    private fun buildRealisticHeader(bobDisplayName: String? = "Alice"): HandshakeHeader =
        HandshakeHeader(
            bobIkX25519    = randomBytes(32),
            bobIkEd25519   = randomBytes(32),
            bobIkMlkem768  = randomBytes(1184),
            bobIkMldsa65   = randomBytes(1952),
            ekX25519Pub    = randomBytes(32),
            ekMlkemPub     = randomBytes(1184),
            kemCtToSpk     = randomBytes(1088),
            kemCtToIk      = randomBytes(1088),
            sessionId      = randomBytes(5),
            bobDisplayName = bobDisplayName,
        )

    @Test
    fun `round-trip with display name`() {
        val header = buildRealisticHeader("Alice")
        val decoded = HandshakeHeader.decode(header.encode())
        assertThat(decoded).isEqualTo(header)
    }

    @Test
    fun `round-trip with null display name`() {
        val header = buildRealisticHeader(null)
        val decoded = HandshakeHeader.decode(header.encode())
        assertThat(decoded).isEqualTo(header)
        assertThat(decoded.bobDisplayName).isNull()
    }

    @Test
    fun `encoded size is below server header cap of 16384 bytes`() {
        val header = buildRealisticHeader("Bob")
        val encoded = header.encode()
        assertThat(encoded.size).isLessThan(16384)
    }
}
