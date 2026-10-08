package app.chencang.shared.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * spec 2026-09-25 §3.3 / 2026-09-30 §4.1、§4.3：先读源信息（> 60 s 直接拦下，不转码），再用 media3
 * Transformer 转成 HEVC 1.8 Mbps（设备没有硬件 HEVC 编码器则 H.264 2.5 Mbps，见 [chooseVideoCodec]）+ AAC MP4：
 * 按显示方向短边 ≤ 720、不放大、宽高偶数（[targetSize]），帧率 > 30 降到 30（[needsFrameRateCap]）。
 *
 * 去隐私：封装走自带的 [PaddinglessMp4MuxerFactory]（media3 Mp4Muxer、不留 free 占位），metadata 条目一律不写（位置、拍摄时间、XMP、mdta 设备信息都不写；mvhd 等里只有封装时刻作创建时间；
 * 画面方向不经过这个集合，不受影响）；落盘后再用 [Mp4MetadataScanner] 兜底扫一遍，命中任何隐私 box 即判失败，
 * 发送报错、不上传。码率按实际编码 MIME 定（media3 静默换编码也对得上）。HEVC 转码中途失败（编码器/封装器运行时出错）会删掉残片、用 H.264 重试一次。
 *
 * 转完超过明文预算拦下「视频太大」。Transformer 必须在有 Looper 的线程上启动与回调，这里统一用主线程。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPreparer(private val context: Context) {

    suspend fun prepare(source: Uri, outFile: File): PreparedMedia {
        val src = withContext(Dispatchers.IO) { readSource(source) }
        MediaLimits.videoDurationError(src.durMs)?.let { throw MediaRejected(it) }
        requireNotNull(outFile.parentFile).mkdirs()
        val size = targetSize(src.width, src.height, src.rotation)
        val frameCap = needsFrameRateCap(src.frameRate)
        val first = chooseVideoCodec(withContext(Dispatchers.IO) { hardwareEncoderMimeTypes() })
        try {
            transcode(source, outFile, size, frameCap, first)
        } catch (e: ExportFailed) {
            if (!shouldRetryWithH264(first, e.errorCode)) throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
            // HEVC 编码器在真机上可能「列出来却跑不动」：残片已删，退 H.264 再来一次（源/解码错误不重试）
            try {
                transcode(source, outFile, size, frameCap, H264_CHOICE)
            } catch (e2: ExportFailed) {
                throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
            }
        }
        MediaLimits.videoSizeError(outFile.length())?.let {
            outFile.delete()
            throw MediaRejected(it)
        }
        val meta = try {
            withContext(Dispatchers.IO) {
                assertNoPrivacyBoxes(outFile)
                readOutputMeta(outFile)
            }
        } catch (e: MediaRejected) {
            outFile.delete()
            throw e
        }
        return PreparedMedia(
            kind = MediaConstants.KIND_VIDEO,
            file = outFile,
            durMs = MediaLimits.clampDurationMs(meta.durMs),
            width = meta.width,
            height = meta.height,
        )
    }

    /** Transformer 报错（含 HEVC 编码器运行时失败）；[prepare] 按 [errorCode] 决定是否退 H.264 重试。 */
    private class ExportFailed(cause: ExportException) : Exception(cause) {
        val errorCode: Int = cause.errorCode
    }

    private suspend fun transcode(
        source: Uri,
        outFile: File,
        size: VideoSize?,
        frameCap: Boolean,
        codec: VideoCodecChoice,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            // 不设请求码率：码率由 ForcedReencodeEncoderFactory 按「实际编码的 MIME」写进格式（见其注释）。
            // setEnableFallback 只放宽同一 MIME 下的分辨率/码率夹取，不改编码。
            val encoderFactory = ForcedReencodeEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true)
                    .build(),
            )
            // 自带封装器工厂：不留 streamable 预留的 free 占位（见 PaddinglessMp4MuxerFactory），元数据一律丢弃
            val muxerFactory = PaddinglessMp4MuxerFactory()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(codec.mimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .setMuxerFactory(muxerFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        outFile.delete()
                        cont.resumeWithException(ExportFailed(exportException))
                    }
                })
                .build()
            val videoEffects = buildList<Effect> {
                if (frameCap) add(FrameDropEffect.createDefaultFrameDropEffect(MediaConstants.VIDEO_MAX_FPS.toFloat()))
                add(
                    if (size == null) {
                        // 读不到源尺寸：按高 720 缩（竖拍时短边更小），至少不超短边上限
                        Presentation.createForHeight(MediaConstants.VIDEO_MAX_SHORT_EDGE)
                    } else {
                        Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_STRETCH_TO_FIT)
                    },
                )
            }
            val edited = EditedMediaItem.Builder(ExoMediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
            // HDR（HLG/HDR10）一律色调映射成 SDR，与 iOS 输出的 SDR BT.709 一致，老接收端也能正常播
            val composition = Composition.Builder(EditedMediaItemSequence(edited))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            transformer.start(composition, outFile.absolutePath)
            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { transformer.cancel() }
                outFile.delete()
            }
        }
    }

    /**
     * 音画一律重编码：否则源已是目标编码且尺寸/帧率不用改时，Transformer 会原样透传（码率、编码、AAC 码率都不归我们管）。
     * DefaultEncoderFactory 在 1.4.1 只能配视频码率，音频在这里把请求格式的码率设成 64 kbps。
     *
     * 视频码率按**实际要编的 MIME** 定（[bitrateForVideoMime]）：media3 1.4.1 的 `SampleExporter.findSupportedMimeTypeForEncoderAndMuxer`
     * 在编码器/封装器不支持请求的 MIME 时会无条件换编码，没有开关可关。换了编码后传到这里的 format 已是新 MIME，
     * 所以在这里按它写 averageBitrate（DefaultEncoderFactory 未设请求码率时就用它，再按编码器范围夹取），
     * 保证 H.264 永远配 2.5 Mbps、HEVC 配 1.8 Mbps。
     */
    private class ForcedReencodeEncoderFactory(private val delegate: Codec.EncoderFactory) : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format): Codec = delegate.createForAudioEncoding(
            format.buildUpon().setAverageBitrate(MediaConstants.VIDEO_AUDIO_BITRATE_AAC).build(),
        )

        override fun createForVideoEncoding(format: Format): Codec = delegate.createForVideoEncoding(
            format.buildUpon().setAverageBitrate(bitrateForVideoMime(format.sampleMimeType)).build(),
        )
        override fun audioNeedsEncoding(): Boolean = true
        override fun videoNeedsEncoding(): Boolean = true
    }

    /** 兜底：输出里任何位置/XMP/ilst box 都判失败（不上传）。 */
    private fun assertNoPrivacyBoxes(file: File) {
        val hits = Mp4MetadataScanner.findPrivacyBoxes(file.readBytes())
        if (hits.isNotEmpty()) {
            // 只记 box 名（不含路径与内容），便于 UAT 定位
            Log.w(TAG, "privacy boxes in transcoded video: ${hits.joinToString(",")}")
            throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
        }
    }

    /** 本机硬件视频编码器支持的 MIME（软件 HEVC 编码器太慢，不算）。 */
    private fun hardwareEncoderMimeTypes(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.isHardwareAccelerated }
            .flatMap { it.supportedTypes.asList() }

    /** 源信息；宽高是编码尺寸（未旋转），读不到为 0；[frameRate] 读不到为 null。 */
    private data class Source(val durMs: Long, val width: Int, val height: Int, val rotation: Int, val frameRate: Float?)

    private fun readSource(source: Uri): Source {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, source)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            Source(dur, w, h, rotation, readFrameRate(source))
        } catch (e: RuntimeException) {
            throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
        } finally {
            r.release()
        }
    }

    /** 视频轨的标称帧率；容器没写或读不出来返回 null（调用方按「可能超 30」处理）。 */
    private fun readFrameRate(source: Uri): Float? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, source, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.let { f ->
                    try {
                        f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    } catch (e: ClassCastException) {
                        f.getFloat(MediaFormat.KEY_FRAME_RATE)
                    }
                }
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    data class VideoSize(val width: Int, val height: Int)

    data class VideoCodecChoice(val mimeType: String, val bitrate: Int)

    companion object {
        private const val TAG = "VideoPreparer"

        val HEVC_CHOICE = VideoCodecChoice(MimeTypes.VIDEO_H265, MediaConstants.VIDEO_BITRATE_HEVC)
        val H264_CHOICE = VideoCodecChoice(MimeTypes.VIDEO_H264, MediaConstants.VIDEO_BITRATE_H264)

        /**
         * 输出尺寸（显示方向）：rotation 90/270 先对调宽高；短边高于 [MediaConstants.VIDEO_MAX_SHORT_EDGE]
         * 才等比缩到它，**绝不放大**；宽高各向下取偶数（编码器要求，也保证不放大）。读不到尺寸返回 null。
         */
        fun targetSize(srcW: Int, srcH: Int, rotation: Int): VideoSize? {
            if (srcW <= 0 || srcH <= 0) return null
            val r = ((rotation % 360) + 360) % 360
            val (dw, dh) = if (r == 90 || r == 270) srcH to srcW else srcW to srcH
            val limit = MediaConstants.VIDEO_MAX_SHORT_EDGE
            val short = minOf(dw, dh)
            val (w, h) = if (short > limit) {
                // 整数运算向下取整：避免 1920 * (720 / 1080.0) = 1279.999… 这种浮点误差
                val long = maxOf(dw, dh).toLong() * limit / short
                if (dw <= dh) limit to long.toInt() else long.toInt() to limit
            } else {
                dw to dh
            }
            return VideoSize(even(w), even(h))
        }

        private fun even(v: Int): Int = maxOf(2, v - v % 2)

        /**
         * 首选编码：本机有（硬件）HEVC 编码器 → HEVC 1.8 Mbps；否则 H.264 2.5 Mbps。
         * 事先查清再定码率，而不是让 Transformer 静默回退后拿 HEVC 的码率去编 H.264。
         */
        fun chooseVideoCodec(encoderMimeTypes: Collection<String>): VideoCodecChoice =
            if (encoderMimeTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }) HEVC_CHOICE else H264_CHOICE

        /**
         * 实际编码 MIME 对应的码率：HEVC 1.8 Mbps；H.264（及 media3 可能静默换成的其他编码）一律 2.5 Mbps。
         * 由编码器工厂在 MIME 最终确定后调用，堵住「静默换编码仍用 HEVC 码率」。
         */
        fun bitrateForVideoMime(mimeType: String?): Int =
            if (mimeType.equals(MimeTypes.VIDEO_H265, ignoreCase = true)) HEVC_CHOICE.bitrate else H264_CHOICE.bitrate

        /**
         * HEVC 转码失败后是否退 H.264 重试：只有编码器（4xxx）或封装器（7xxx）侧的错误才可能是 HEVC 的锅；
         * 源读不了、解码失败等换编码也救不回来，直接报错。
         */
        fun shouldRetryWithH264(attempted: VideoCodecChoice, errorCode: Int): Boolean =
            attempted.mimeType == MimeTypes.VIDEO_H265 && (errorCode in 4000..4999 || errorCode in 7000..7999)

        /** 帧率高于 30（或读不到）才加降帧效果。 */
        fun needsFrameRateCap(frameRate: Float?): Boolean =
            frameRate == null || frameRate > MediaConstants.VIDEO_MAX_FPS
    }

    private data class Meta(val durMs: Long, val width: Int, val height: Int)

    /** 转码刚落盘就读不出宽高，说明 Transformer 吐出的文件本身有问题——按「无法处理这个视频」拦下，不是 0x0 糊弄过去。 */
    private fun readOutputMeta(file: File): Meta {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rotation == 90 || rotation == 270) Meta(dur, h, w) else Meta(dur, w, h)
        } catch (e: RuntimeException) {
            throw MediaRejected(MediaLimits.VIDEO_UNREADABLE)
        } finally {
            r.release()
        }
    }
}
