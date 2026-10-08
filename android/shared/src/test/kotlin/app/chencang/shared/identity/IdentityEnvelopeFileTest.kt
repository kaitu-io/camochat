package app.chencang.shared.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for the file-I/O layer of identity persistence. The uniffi-touching part
 * ([IdentityStore.generateAndSave]) requires a native library and is verified in
 * `IdentityStoreInstrumentedTest`.
 */
class IdentityEnvelopeFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun envelopeFile(envelope: IdentityFileEnvelope = IdentityFileEnvelope.passthrough): IdentityEnvelopeFile {
        val file = File(tmp.root, "identity.enc")
        return IdentityEnvelopeFile(file, envelope)
    }

    @Test
    fun `exists is false when file missing`() {
        assertThat(envelopeFile().exists()).isFalse()
    }

    @Test
    fun `write then read round-trips plaintext bytes (passthrough envelope)`() {
        val ef = envelopeFile()
        val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 42)

        ef.write(payload)

        assertThat(ef.exists()).isTrue()
        assertThat(ef.read()).isEqualTo(payload)
    }

    @Test
    fun `write is atomic via temp file rename`() {
        val ef = envelopeFile()
        ef.write(byteArrayOf(1))
        // Overwrite with new content — no .tmp file should linger
        ef.write(byteArrayOf(2, 3))

        val files = tmp.root.list() ?: emptyArray()
        assertThat(files.toList()).containsExactly("identity.enc")
        assertThat(ef.read()).isEqualTo(byteArrayOf(2, 3))
    }

    @Test
    fun `envelope encrypt is applied before write and decrypt before read`() {
        // Simple XOR-with-0xFF envelope — not crypto, just verifying the transform fires
        val xorEnvelope = IdentityFileEnvelope(
            encrypt = { it.map { b -> (b.toInt() xor 0xFF).toByte() }.toByteArray() },
            decrypt = { it.map { b -> (b.toInt() xor 0xFF).toByte() }.toByteArray() },
        )
        val ef = envelopeFile(xorEnvelope)
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        ef.write(payload)

        // On-disk bytes are XOR'd
        val onDisk = File(tmp.root, "identity.enc").readBytes()
        assertThat(onDisk).isEqualTo(byteArrayOf(0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte()))
        // Read decrypts back
        assertThat(ef.read()).isEqualTo(payload)
    }

    @Test
    fun `clearForTest removes the file`() {
        val ef = envelopeFile()
        ef.write(byteArrayOf(1, 2, 3))
        assertThat(ef.exists()).isTrue()

        ef.clearForTest()

        assertThat(ef.exists()).isFalse()
    }

    @Test
    fun `wipe deletes the identity file and hasIdentity flips false`() {
        val file = File(tmp.root, "identity.enc")
        IdentityEnvelopeFile(file, IdentityFileEnvelope.passthrough).write(byteArrayOf(1, 2, 3))
        val store = IdentityStore(file, IdentityFileEnvelope.passthrough)
        assertThat(store.hasIdentity()).isTrue()
        store.wipe()
        assertThat(store.hasIdentity()).isFalse()
    }
}
