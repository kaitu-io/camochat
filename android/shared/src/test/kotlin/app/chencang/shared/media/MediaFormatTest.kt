package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaFormatTest {
    private val webp = intArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x46, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50,
        0x56, 0x50, 0x38, 0x20, 0x3a, 0x00, 0x00, 0x00, 0xd0, 0x01, 0x00, 0x9d,
        0x01, 0x2a, 0x04, 0x00, 0x04, 0x00, 0x02, 0xc0, 0x4c, 0x25, 0xb0, 0x02,
        0x74, 0x01, 0x0e, 0xfe, 0x03, 0x8e, 0x00, 0x00, 0xf9, 0x4e, 0x05, 0xff,
        0x6f, 0x2f, 0x8d, 0x14, 0xba, 0x9d, 0x41, 0xed, 0xdd, 0xc8, 0x70, 0x84,
        0xef, 0xab, 0xca, 0x96, 0xd6, 0xd4, 0xe0, 0x51, 0x65, 0x87, 0x71, 0x90,
        0xc4, 0xde, 0x74, 0x00, 0x00, 0x00,
    ).let { a -> ByteArray(a.size) { a[it].toByte() } }

    private fun ftyp(brand: String) =
        byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(12)

    @Test
    fun sniff() {
        assertThat(MediaFormat.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0))).isEqualTo(MediaFormat.JPEG)
        assertThat(MediaFormat.sniff(webp)).isEqualTo(MediaFormat.WEBP)
        for (b in listOf("heic", "heix", "mif1", "msf1", "hevc", "hevx")) {
            assertThat(MediaFormat.sniff(ftyp(b))).isEqualTo(MediaFormat.HEIC)
        }
        for (b in listOf("isom", "mp42", "avc1")) assertThat(MediaFormat.sniff(ftyp(b))).isEqualTo(MediaFormat.MP4)
        assertThat(MediaFormat.sniff("OggS".toByteArray() + ByteArray(20))).isEqualTo(MediaFormat.OGG)
        assertThat(MediaFormat.sniff(byteArrayOf())).isEqualTo(MediaFormat.UNKNOWN)
        assertThat(MediaFormat.sniff("RIFF....WAVE".toByteArray())).isEqualTo(MediaFormat.UNKNOWN)
        assertThat(MediaFormat.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))).isEqualTo(MediaFormat.UNKNOWN)
    }

    @Test
    fun `extension and mime per format`() {
        assertThat(MediaFormat.HEIC.extension).isEqualTo("heic")
        assertThat(MediaFormat.HEIC.mime).isEqualTo("image/heic")
        assertThat(MediaFormat.WEBP.extension).isEqualTo("webp")
        assertThat(MediaFormat.WEBP.mime).isEqualTo("image/webp")
        assertThat(MediaFormat.JPEG.extension).isEqualTo("jpg")
        assertThat(MediaFormat.JPEG.mime).isEqualTo("image/jpeg")
        assertThat(MediaFormat.MP4.extension).isEqualTo("mp4")
        assertThat(MediaFormat.MP4.mime).isEqualTo("video/mp4")
    }

    private fun chunk(tag: String, len: Int): ByteArray {
        val pad = len and 1
        return tag.toByteArray() + byteArrayOf(len.toByte(), 0, 0, 0) + ByteArray(len + pad)
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".toByteArray() + chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray() + byteArrayOf(body.size.toByte(), 0, 0, 0) + body
    }

    @Test
    fun `privacy chunks found even after odd-sized padded chunk`() {
        assertThat(WebpChunks.privacyChunks(webp)).isEmpty()
        assertThat(WebpChunks.privacyChunks(riff(chunk("VP8 ", 5), chunk("EXIF", 6), chunk("XMP ", 3))))
            .containsExactly("EXIF", "XMP ").inOrder()
        assertThat(WebpChunks.privacyChunks(riff(chunk("VP8 ", 4)))).isEmpty()
        assertThat(WebpChunks.privacyChunks(byteArrayOf(1, 2, 3))).containsExactly(WebpChunks.NOT_WEBP)
        assertThat(WebpChunks.privacyChunks(byteArrayOf())).containsExactly(WebpChunks.NOT_WEBP)
        assertThat(WebpChunks.privacyChunks("RIFF....WAVE".toByteArray() + ByteArray(8))).containsExactly(WebpChunks.NOT_WEBP)
    }
}
