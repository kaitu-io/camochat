package app.chencang.android.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.MediaFiles
import java.io.File
import java.util.UUID

/** 「录像」:系统相机,限 60 秒(裁决 R8,不做 App 内相机)。 */
class CaptureVideoLimited : ActivityResultContracts.CaptureVideo() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, MediaConstants.MAX_VIDEO_MS / 1000)
}

enum class CameraAction { PHOTO, VIDEO }

/** 相机输出写到 `cacheDir/capture/`,经 manifest 里 `${applicationId}.media` 的 FileProvider 交给相机。 */
object CaptureFiles {
    fun newUri(context: Context, ext: String): Uri {
        val dir = File(context.cacheDir, MediaFiles.CAPTURE_DIR).apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${context.packageName}.media", File(dir, "${UUID.randomUUID()}.$ext"))
    }
}
