package app.chencang.android.ui.chat

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import app.chencang.shared.media.MediaFormat
import java.io.File
import java.io.IOException

/** 全屏看图的「保存到相册」:MediaStore(API 29+ 不需要存储权限)。 */
object MediaSaver {
    /** 按字节嗅探出的格式决定扩展名与 MIME；认不出的图片不存（不冒充 jpg）。 */
    internal fun imageName(format: MediaFormat, stamp: Long): Pair<String, String>? =
        if (format.isImage) "chencang_$stamp.${format.extension}" to format.mime else null

    fun saveImage(context: Context, path: String): Boolean {
        val (name, mime) = imageName(MediaFormat.sniff(File(path)), System.currentTimeMillis()) ?: return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CamoChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
            out.use { o -> File(path).inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            // 任何失败（不只 IOException：SecurityException、IllegalStateException……）都要把
            // IS_PENDING 的那一行连同写了一半的明文删掉，不能在公共相册里留半张图（终审 M8）。
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
