package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionManagerTest {

    /**
     * Pass-through fake (identity transform). Real coverage of the
     * native-binding-backed path lives in the in-process loopback test
     * (`SessionV1WireRoundTripTest`) which spins up two PQXDH sessions.
     */
    private class FakeBytesCrypto : SessionCrypto {
        override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
            plaintext.copyOf()
        override suspend fun decryptFromBytes(
            peerUsername: String,
            ciphertext: ByteArray,
        ): ByteArray = ciphertext.copyOf()
    }

    @Test
    fun `encryptToBytes then decryptFromBytes round-trips bytes through the fake crypto`() = runTest {
        val sm = SessionManager(FakeBytesCrypto())
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)

        val ct = sm.encryptToBytes("alice", plaintext)
        val decrypted = sm.decryptFromBytes("alice", ct)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `different peers get isolated mutex slots`() = runTest {
        val sm = SessionManager(FakeBytesCrypto())
        // Just exercise the lazy creation path; no race here, but smoke-tests the map.
        sm.encryptToBytes("alice", byteArrayOf(1))
        sm.encryptToBytes("bob", byteArrayOf(2))
        sm.decryptFromBytes("alice", sm.encryptToBytes("alice", byteArrayOf(3)))
    }
}
