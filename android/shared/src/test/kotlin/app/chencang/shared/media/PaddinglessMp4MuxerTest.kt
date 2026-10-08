package app.chencang.shared.media

import android.media.MediaCodec
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.Mp4LocationData
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

/** 封装输出不带 streamable 预留的 `free` 占位：顶层 box 只有 ftyp/mdat/moov，stco 不受影响。 */
@RunWith(RobolectricTestRunner::class)
class PaddinglessMp4MuxerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sps = byteArrayOf(0, 0, 0, 1) + intArrayOf(
        0x67, 0x64, 0x00, 0x0A, 0xAC, 0xD9, 0x40, 0xA0, 0x2F, 0xF9, 0x70, 0x11, 0x00, 0x00, 0x03, 0x00,
        0x01, 0x00, 0x00, 0x03, 0x00, 0x3C, 0x0F, 0x14, 0x29, 0x60,
    ).map { it.toByte() }.toByteArray()
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    /** 顶层 box 类型与大小，按出现顺序。 */
    private fun topLevel(b: ByteArray): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        var o = 0
        while (b.size - o >= 8) {
            var size = 0L
            for (k in 0 until 4) size = (size shl 8) or (b[o + k].toLong() and 0xFF)
            if (size == 1L) { // 64 位 largesize（mdat 常这么写）
                size = 0L
                for (k in 8 until 16) size = (size shl 8) or (b[o + k].toLong() and 0xFF)
            }
            out += String(b, o + 4, 4, Charsets.ISO_8859_1) to size
            if (size < 8) break
            o += size.toInt()
        }
        return out
    }

    private fun mux(rotation: Int = 0, location: Boolean = false): ByteArray {
        val file = tmp.newFile()
        val muxer = PaddinglessMp4MuxerFactory().create(file.absolutePath)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(64).setHeight(64)
            .setRotationDegrees(rotation)
            .setInitializationData(listOf(sps, pps))
            .build()
        val token = muxer.addTrack(format)
        if (location) muxer.addMetadataEntry(Mp4LocationData(31.23f, 121.47f))
        for (i in 0 until 3) {
            val data = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, i.toByte())
            val info = MediaCodec.BufferInfo().apply {
                set(0, data.size, i * 33_000L, if (i == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            muxer.writeSampleData(token, ByteBuffer.wrap(data), info)
        }
        muxer.close()
        return file.readBytes()
    }

    private fun be32(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    /** 沿 [path]（如 moov/trak/tkhd）找第一个 box，返回 (payload 起点, box 终点)；找不到为 null。 */
    private fun find(b: ByteArray, path: List<String>, start: Int = 0, end: Int = b.size): Pair<Int, Int>? {
        var o = start
        while (end - o >= 8) {
            var size = be32(b, o)
            var header = 8
            if (size == 1L) {
                size = (be32(b, o + 8) shl 32) or be32(b, o + 12)
                header = 16
            }
            if (size < header) return null
            val boxEnd = minOf(end.toLong(), o + size).toInt()
            if (String(b, o + 4, 4, Charsets.ISO_8859_1) == path[0]) {
                return if (path.size == 1) (o + header) to boxEnd else find(b, path.drop(1), o + header, boxEnd)
            }
            o = boxEnd
        }
        return null
    }

    @Test
    fun `muxed file has no free or skip padding box`() {
        val bytes = mux()
        val boxes = topLevel(bytes)
        assertThat(boxes.map { it.first }).containsNoneOf("free", "skip")
        assertThat(boxes.map { it.first }).containsAtLeast("ftyp", "mdat", "moov")
        assertThat(boxes.sumOf { it.second }).isEqualTo(bytes.size.toLong())
        assertThat(Mp4MetadataScanner.findPrivacyBoxes(bytes)).isEmpty()
    }

    @Test
    fun `location metadata entry is dropped`() {
        val bytes = mux(location = true)
        // 若 addMetadataEntry 被转发给 Mp4Muxer，输出会有 udta/©xyz，扫描器会命中
        assertThat(Mp4MetadataScanner.findPrivacyBoxes(bytes)).isEmpty()
        assertThat(find(bytes, listOf("moov", "udta"))).isNull()
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("\u00A9xyz")
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("loci")
    }

    @Test
    fun `rotation 90 is preserved in tkhd matrix`() {
        fun matrix(b: ByteArray): List<Long> {
            val (payload, _) = find(b, listOf("moov", "trak", "tkhd"))!!
            val version = b[payload].toInt()
            val at = payload + if (version == 1) 52 else 40
            return (0 until 9).map { be32(b, at + it * 4) }
        }
        val identity = listOf(0x10000L, 0, 0, 0, 0x10000L, 0, 0, 0, 0x40000000L)
        assertThat(matrix(mux(rotation = 0))).isEqualTo(identity)
        // 90°：a=0 b=1 c=-1 d=0（16.16 定点，-1 为 0xFFFF0000）
        assertThat(matrix(mux(rotation = 90)))
            .isEqualTo(listOf(0L, 0x10000L, 0, 0xFFFF0000L, 0, 0, 0, 0, 0x40000000L))
    }

    @Test
    fun `stco offsets point inside mdat`() {
        val bytes = mux()
        val (mdatPayload, mdatEnd) = find(bytes, listOf("mdat"))!!
        val stbl = listOf("moov", "trak", "mdia", "minf", "stbl")
        // media3 按偏移是否超 32 位选 stco 或 co64；两种都要认
        val co64 = find(bytes, stbl + "co64")
        val (table, width) = if (co64 != null) co64.first to 8 else find(bytes, stbl + "stco")!!.first to 4
        val count = be32(bytes, table + 4).toInt()
        assertThat(count).isGreaterThan(0)
        for (i in 0 until count) {
            val at = table + 8 + i * width
            val off = if (width == 8) (be32(bytes, at) shl 32) or be32(bytes, at + 4) else be32(bytes, at)
            assertThat(off).isAtLeast(mdatPayload.toLong())
            assertThat(off).isLessThan(mdatEnd.toLong())
        }
    }

    @Test
    fun `supported mime types keep HEVC and AAC`() {
        val f = PaddinglessMp4MuxerFactory()
        assertThat(f.getSupportedSampleMimeTypes(androidx.media3.common.C.TRACK_TYPE_VIDEO))
            .containsAtLeast(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
        assertThat(f.getSupportedSampleMimeTypes(androidx.media3.common.C.TRACK_TYPE_AUDIO)).contains(MimeTypes.AUDIO_AAC)
    }
}
