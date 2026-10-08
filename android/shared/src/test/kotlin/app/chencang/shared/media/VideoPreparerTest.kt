package app.chencang.shared.media

import androidx.media3.common.MimeTypes
import androidx.media3.transformer.ExportException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 转码参数的纯函数：输出尺寸（短边 ≤ 720、不放大、偶数）、编码器与码率、帧率上限。 */
class VideoPreparerTest {
    private fun size(w: Int, h: Int) = VideoPreparer.VideoSize(w, h)

    @Test
    fun `landscape above 720 is scaled so the short edge is 720`() {
        assertThat(VideoPreparer.targetSize(1920, 1080, 0)).isEqualTo(size(1280, 720))
        assertThat(VideoPreparer.targetSize(3840, 2160, 0)).isEqualTo(size(1280, 720))
        assertThat(VideoPreparer.targetSize(4000, 3000, 180)).isEqualTo(size(960, 720))
    }

    @Test
    fun `portrait above 720 keeps the short edge at 720 not the height`() {
        // 竖拍：短边是宽 → 720x1280，而不是旧逻辑的「高 720」(405x720)
        assertThat(VideoPreparer.targetSize(1080, 1920, 0)).isEqualTo(size(720, 1280))
    }

    @Test
    fun `rotation 90 and 270 swap to display orientation`() {
        // 手机竖拍常见存法：编码 1920x1080 + rotation 90 → 显示 1080x1920
        assertThat(VideoPreparer.targetSize(1920, 1080, 90)).isEqualTo(size(720, 1280))
        assertThat(VideoPreparer.targetSize(1920, 1080, 270)).isEqualTo(size(720, 1280))
        assertThat(VideoPreparer.targetSize(1920, 1080, -90)).isEqualTo(size(720, 1280))
        assertThat(VideoPreparer.targetSize(640, 360, 90)).isEqualTo(size(360, 640))
    }

    @Test
    fun `sources at or below 720 short edge are never upscaled`() {
        assertThat(VideoPreparer.targetSize(1280, 720, 0)).isEqualTo(size(1280, 720))
        assertThat(VideoPreparer.targetSize(854, 480, 0)).isEqualTo(size(854, 480))
        assertThat(VideoPreparer.targetSize(360, 640, 0)).isEqualTo(size(360, 640))
    }

    @Test
    fun `odd dimensions are made even without upscaling`() {
        assertThat(VideoPreparer.targetSize(719, 405, 0)).isEqualTo(size(718, 404))
        assertThat(VideoPreparer.targetSize(405, 719, 90)).isEqualTo(size(718, 404))
        // 缩放后的长边算出奇数/小数也取偶数：1000x721 → 720 短边，长边 998.6 → 998
        assertThat(VideoPreparer.targetSize(1000, 721, 0)).isEqualTo(size(998, 720))
        val s = VideoPreparer.targetSize(1921, 1081, 0)!!
        assertThat(s.width % 2).isEqualTo(0)
        assertThat(s.height).isEqualTo(720)
    }

    @Test
    fun `unknown source size yields null`() {
        assertThat(VideoPreparer.targetSize(0, 1080, 0)).isNull()
        assertThat(VideoPreparer.targetSize(1920, -1, 0)).isNull()
    }

    @Test
    fun `hardware HEVC encoder picks HEVC at 1_8 Mbps`() {
        val choice = VideoPreparer.chooseVideoCodec(listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265))
        assertThat(choice).isEqualTo(VideoPreparer.VideoCodecChoice(MimeTypes.VIDEO_H265, 1_800_000))
    }

    @Test
    fun `no HEVC encoder falls back to H264 at 2_5 Mbps`() {
        val choice = VideoPreparer.chooseVideoCodec(listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP8))
        assertThat(choice).isEqualTo(VideoPreparer.VideoCodecChoice(MimeTypes.VIDEO_H264, 2_500_000))
        assertThat(VideoPreparer.chooseVideoCodec(emptyList()))
            .isEqualTo(VideoPreparer.VideoCodecChoice(MimeTypes.VIDEO_H264, 2_500_000))
    }

    @Test
    fun `MIME matching is case insensitive`() {
        assertThat(VideoPreparer.chooseVideoCodec(listOf("VIDEO/HEVC")).mimeType).isEqualTo(MimeTypes.VIDEO_H265)
    }

    @Test
    fun `bitrate follows the MIME actually encoded`() {
        assertThat(VideoPreparer.bitrateForVideoMime(MimeTypes.VIDEO_H265)).isEqualTo(1_800_000)
        assertThat(VideoPreparer.bitrateForVideoMime("VIDEO/HEVC")).isEqualTo(1_800_000)
        // media3 静默把 HEVC 换成 H.264（或别的编码）时，码率跟着换成 2.5 Mbps，而不是沿用 HEVC 的 1.8 Mbps
        assertThat(VideoPreparer.bitrateForVideoMime(MimeTypes.VIDEO_H264)).isEqualTo(2_500_000)
        assertThat(VideoPreparer.bitrateForVideoMime(MimeTypes.VIDEO_MP4V)).isEqualTo(2_500_000)
        assertThat(VideoPreparer.bitrateForVideoMime(null)).isEqualTo(2_500_000)
    }

    @Test
    fun `H264 fallback choice is what the HEVC runtime retry uses`() {
        assertThat(VideoPreparer.H264_CHOICE).isEqualTo(VideoPreparer.VideoCodecChoice(MimeTypes.VIDEO_H264, 2_500_000))
    }

    @Test
    fun `frame rate is capped only above 30 or when unknown`() {
        assertThat(VideoPreparer.needsFrameRateCap(60f)).isTrue()
        assertThat(VideoPreparer.needsFrameRateCap(30.5f)).isTrue()
        assertThat(VideoPreparer.needsFrameRateCap(null)).isTrue()
        assertThat(VideoPreparer.needsFrameRateCap(30f)).isFalse()
        assertThat(VideoPreparer.needsFrameRateCap(29.97f)).isFalse()
        assertThat(VideoPreparer.needsFrameRateCap(24f)).isFalse()
    }

    @Test
    fun `HEVC encoder and muxer failures retry with H264`() {
        val hevc = VideoPreparer.HEVC_CHOICE
        listOf(
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
        ).forEach { assertThat(VideoPreparer.shouldRetryWithH264(hevc, it)).isTrue() }
    }

    @Test
    fun `source and decoder failures do not retry`() {
        val hevc = VideoPreparer.HEVC_CHOICE
        listOf(
            ExportException.ERROR_CODE_UNSPECIFIED,
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
        ).forEach { assertThat(VideoPreparer.shouldRetryWithH264(hevc, it)).isFalse() }
    }

    @Test
    fun `H264 attempt never retries`() {
        assertThat(
            VideoPreparer.shouldRetryWithH264(VideoPreparer.H264_CHOICE, ExportException.ERROR_CODE_ENCODING_FAILED),
        ).isFalse()
    }
}
