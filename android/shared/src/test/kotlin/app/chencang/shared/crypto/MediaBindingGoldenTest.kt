package app.chencang.shared.crypto

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uniffi.chencang.DecodedMessage
import uniffi.chencang.decodeFrame
import uniffi.chencang.decryptMediaBlob
import uniffi.chencang.encryptMediaBlob
import uniffi.chencang.mediaBlobId

/**
 * Cross-language golden vector coverage for the media blob + MEDIA_REF
 * bindings, mirroring `core/tests` and the Swift equivalent in
 * `ios/ChencangShared/Tests/ChencangSharedTests/MediaBindingGoldenTests.swift`.
 *
 * `:shared`'s plain JVM unit tests run without a real Android runtime, so
 * `org.json.JSONObject` (an unmocked stub in this environment) throws. We
 * parse `vectors.json` with kotlinx.serialization instead, which is already
 * an `api` dependency of this module.
 */
class MediaBindingGoldenTest {
    private val media by lazy {
        val start = File(System.getProperty("user.dir")!!).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "bindings/golden-vectors/vectors.json").exists() }
            ?: error("bindings/golden-vectors/vectors.json not found walking up from $start")
        val text = File(root, "bindings/golden-vectors/vectors.json").readText()
        Json.parseToJsonElement(text).jsonObject["media"]!!.jsonObject
    }

    private fun str(key: String) = media.getValue(key).jsonPrimitive.content

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun goldenBlobDecryptsAndIdMatches() {
        val secret = hex(str("blob_secret_hex"))
        val kind = media.getValue("kind").jsonPrimitive.int.toUByte()
        assertThat(mediaBlobId(secret)).isEqualTo(str("blob_id"))
        assertThat(decryptMediaBlob(hex(str("cca_hex")), secret, kind))
            .isEqualTo(hex(str("plaintext_hex")))
        // encrypt_media_blob no longer takes a caller-supplied secret (F2
        // fix — core generates it to rule out nonce reuse), so it can't
        // reproduce the golden ciphertext byte-for-byte any more. Cover the
        // same ground with a round trip plus the id/secret derivation
        // relation instead.
        val sealed = encryptMediaBlob(hex(str("plaintext_hex")), kind)
        assertThat(sealed.blobId).isEqualTo(mediaBlobId(sealed.blobSecret))
        assertThat(decryptMediaBlob(sealed.blob, sealed.blobSecret, kind))
            .isEqualTo(hex(str("plaintext_hex")))
    }

    @Test
    fun goldenFrameDecodesToMediaRef() {
        val msg = decodeFrame(hex(str("media_ref_frame_hex")))
        assertThat(msg).isInstanceOf(DecodedMessage.Media::class.java)
        val ref = (msg as DecodedMessage.Media).refs.single()
        assertThat(ref.width).isEqualTo(1920.toUShort())
        assertThat(ref.blobSecret).isEqualTo(hex(str("blob_secret_hex")))
    }
}
