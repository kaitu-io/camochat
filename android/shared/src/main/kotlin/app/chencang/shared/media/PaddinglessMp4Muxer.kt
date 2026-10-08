package app.chencang.shared.media

import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 不留 `free` 占位的 MP4 封装器工厂。
 *
 * media3 1.4.1 的 `Mp4Muxer` 默认「尝试输出 streamable 文件」：开头先预留一大块 `free` box 给 moov，
 * 收尾再把 moov 写进去、剩余空间仍是 `free`（实测 5 秒片子 600 KB 里约 396 KB 是空 `free`，随密文一起上传/下载）。
 * `InAppMuxer.Factory` 没暴露这个开关（它内部 `Mp4Muxer.Builder(...).build()`），所以自己包一层：
 * `setAttemptStreamableOutputEnabled(false)` 后文件是 ftyp + mdat + moov，不预留空间，不用改任何 stco 偏移。
 *
 * 元数据条目一律丢弃（不写位置/ilst/XMP，与原先 `setMetadataProvider { clear() }` 等价）；
 * 注意 mvhd/tkhd/mdhd 里仍会写「封装时刻」作创建时间，但拍摄时间与位置都不写；
 * 只保留旋转——与 `InAppMuxer` 一样，视频轨格式带旋转角时补 [Mp4OrientationData]。
 */
@UnstableApi
internal class PaddinglessMp4MuxerFactory : Muxer.Factory {
    override fun create(path: String): Muxer {
        val out = try {
            FileOutputStream(path)
        } catch (e: java.io.FileNotFoundException) {
            throw Muxer.MuxerException("Error creating muxer", e)
        }
        val mp4 = Mp4Muxer.Builder(out).setAttemptStreamableOutputEnabled(false).build()
        return object : Muxer {
            override fun addTrack(format: Format): Muxer.TrackToken {
                val token = mp4.addTrack(format)
                if (MimeTypes.isVideo(format.sampleMimeType) && format.rotationDegrees != 0) {
                    mp4.addMetadataEntry(Mp4OrientationData(format.rotationDegrees))
                }
                return token
            }

            override fun writeSampleData(token: Muxer.TrackToken, data: ByteBuffer, info: MediaCodec.BufferInfo) =
                mp4.writeSampleData(token, data, info)

            override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

            override fun close() = mp4.close()
        }
    }

    /** 与 `InAppMuxer.Factory` 在 1.4.1 的支持表逐项一致（HEVC 不能丢，否则 VideoPreparer 的 HEVC 选择会被换成 H.264）。 */
    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
        C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)
        C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC)
        else -> ImmutableList.of()
    }
}
