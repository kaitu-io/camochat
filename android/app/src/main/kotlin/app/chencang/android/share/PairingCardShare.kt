package app.chencang.android.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** 把配对卡片 PNG 写进 `cacheDir/share/` 并交给系统分享面板（FileProvider 授权同 [ApkShare]）。 */
object PairingCardShare {
    const val MIME_PNG = "image/png"
    /** 独立子目录（`file_paths.xml` 的 share/ 已覆盖），不动 [ApkShare] 正在分享的文件。 */
    private const val DIR = "share/pairing"

    /** 每次清空自己的子目录，再写出文件名唯一的卡片。IO 阻塞，调用方须在后台线程。 */
    fun prepare(context: Context, card: Bitmap): Uri {
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        check(dir.mkdirs()) { "cannot create share dir" }
        val file = File(dir, "pairing-card-${java.util.UUID.randomUUID()}.png")
        file.outputStream().buffered().use { check(card.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return FileProvider.getUriForFile(context, "${context.packageName}.media", file)
    }

    fun intent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
