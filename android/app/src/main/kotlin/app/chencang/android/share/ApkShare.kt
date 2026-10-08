package app.chencang.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.chencang.android.clipboard.AppClipboard
import app.chencang.shared.R
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把本机已安装的 APK 原样交给系统分享面板（直装渠道抗封锁传播，spec §7）。
 * 只有单 APK 安装才能直接分享；split 安装（如 Play 的 AAB 分发）返回 null。
 */
object ApkShare {
    const val MIME_APK = "application/vnd.android.package-archive"
    const val MIME_ZIP = "application/zip"
    private const val DIR = "share"

    /** 每次先清空 `cacheDir/share/`，再写出 `chencang-<ver>.apk`（或同名 `.zip`）。IO 阻塞，调用方须在后台线程。 */
    fun prepare(context: Context, asZip: Boolean): Uri? {
        val info = context.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        val source = File(info.sourceDir)
        if (!source.isFile) return null
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "app"
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) return null
        val apkName = "chencang-$version.apk"
        val out = if (asZip) {
            File(dir, "chencang-$version.zip").also { zip ->
                ZipOutputStream(zip.outputStream().buffered()).use { z ->
                    z.putNextEntry(ZipEntry(apkName))
                    source.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        } else {
            File(dir, apkName).also { source.copyTo(it, overwrite = true) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.media", out)
    }

    fun shareIntent(context: Context, uri: Uri, asZip: Boolean): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = if (asZip) MIME_ZIP else MIME_APK
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.excludingOwnShareTarget(context)

    /** 后台准备文件再弹分享面板；准备不了就提示。 */
    suspend fun share(context: Context, asZip: Boolean) {
        Toast.makeText(context, R.string.share_app_preparing, Toast.LENGTH_SHORT).show()
        val uri = withContext(Dispatchers.IO) { runCatching { prepare(context, asZip) }.getOrNull() }
        if (uri == null) {
            Toast.makeText(context, R.string.share_app_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        context.startActivity(Intent.createChooser(shareIntent(context, uri, asZip), null))
        AppClipboard.markConsumedOnNextFocus() // after launch succeeded: a throwing launch must not leave the flag set
    }

    /** 只分享文字（下载链接等），不带完成回调。 */
    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }.excludingOwnShareTarget(context)
        context.startActivity(Intent.createChooser(send, null))
        AppClipboard.markConsumedOnNextFocus() // after launch succeeded: a throwing launch must not leave the flag set
    }
}
