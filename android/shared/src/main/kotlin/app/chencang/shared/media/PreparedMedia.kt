package app.chencang.shared.media

import androidx.annotation.StringRes
import java.io.File

/**
 * 预处理完成、可以直接加密的明文文件（JPEG / Ogg Opus / MP4）。
 * [file] 在 cache 里，[MediaSender.send] 会把它挪进 `media/<messageId>/<index>.bin`。
 */
data class PreparedMedia(
    val kind: Int,
    val file: File,
    val durMs: Int,
    val width: Int,
    val height: Int,
)

/** 选择/录制时就拦下的情况（spec §5.1「超出大小或时长」），[messageRes] 直接给用户看。 */
class MediaRejected(@StringRes val messageRes: Int) : Exception("media rejected: $messageRes")
