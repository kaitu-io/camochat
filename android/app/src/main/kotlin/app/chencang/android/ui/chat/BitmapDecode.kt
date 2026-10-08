package app.chencang.android.ui.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 缩略图/封面解码(不引入图片库):按目标边长 inSampleSize 下采样;视频取第一帧。 */
object BitmapDecode {
    /**
     * ImageDecoder（API 28+）按字节嗅探 JPEG / HEIC / WebP，不看扩展名；
     * 解码时按目标边长下采样，软件分配器保证 `asImageBitmap` 可用。
     */
    fun sampled(path: String, maxEdgePx: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(maxOf(info.size.width, info.size.height), maxEdgePx))
        }
    } catch (e: IOException) {
        null
    }

    internal fun sampleSize(longEdge: Int, target: Int): Int {
        var s = 1
        while (longEdge / (s * 2) >= target) s *= 2
        return s
    }

    fun videoFrame(path: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.getFrameAtTime(0)
        } catch (e: RuntimeException) {
            null
        } finally {
            r.release()
        }
    }
}

/** [stateKey] 变化(例如 pending → ready)时重新解码。 */
@Composable
internal fun rememberThumbnail(path: String?, maxEdgePx: Int, video: Boolean, stateKey: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, stateKey, maxEdgePx) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                if (!File(path).exists()) {
                    null
                } else {
                    runCatching {
                        (if (video) BitmapDecode.videoFrame(path) else BitmapDecode.sampled(path, maxEdgePx))?.asImageBitmap()
                    }.getOrNull()
                }
            }
        }
    }.value
