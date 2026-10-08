package app.chencang.android.ui.chat

import android.content.Context
import android.net.Uri
import app.chencang.shared.media.ImagePreparer
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.media.MediaLimits
import app.chencang.shared.media.MediaRejected
import app.chencang.shared.media.PreparedMedia
import app.chencang.shared.media.VideoPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Uri（相册 / 相机）→ 可加密的明文文件。接口只为让 ViewModel 测试不碰 ContentResolver。 */
interface MediaPreparer {
    suspend fun image(uri: Uri): PreparedMedia
    suspend fun video(uri: Uri): PreparedMedia
    fun mimeOf(uri: Uri): String?

    /** 拍照/录像写进 `cacheDir/capture/` 的原片用完即删（发送成功与否都删）。 */
    fun discardCaptures()
}

class AndroidMediaPreparer(private val context: Context) : MediaPreparer {
    private val videos = VideoPreparer(context)

    override suspend fun image(uri: Uri): PreparedMedia {
        val out = staging("webp")
        return try {
            withContext(Dispatchers.IO) {
                ImagePreparer.prepare(
                    open = {
                        context.contentResolver.openInputStream(uri) ?: throw MediaRejected(MediaLimits.IMAGE_UNREADABLE)
                    },
                    outFile = out,
                )
            }
        } catch (e: Throwable) {
            out.delete() // 成功时文件会被 MediaSender 挪走；失败的半成品不能留在 cache/prep
            throw e
        }
    }

    override suspend fun video(uri: Uri): PreparedMedia {
        val out = staging("mp4")
        return try {
            videos.prepare(uri, out)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    override fun mimeOf(uri: Uri): String? = context.contentResolver.getType(uri)

    override fun discardCaptures() {
        File(context.cacheDir, MediaFiles.CAPTURE_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun staging(ext: String): File =
        File(File(context.cacheDir, MediaFiles.PREP_DIR).apply { mkdirs() }, "${UUID.randomUUID()}.$ext")
}
