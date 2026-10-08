package app.chencang.shared.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chencang.shared.media.MediaConstants

/**
 * 对话线程里的一条消息。富媒体（语音/图片/视频）的每个媒体条目另存
 * [MediaItem]；媒体消息与升级占位的 body 存 `""`，显示文案由 [MessagePreview] 按 kind 现取
 * （跟随系统语言）。旧版本写下的中文摘要不迁移，界面也不再读它。
 * `peer_username` 与 RatchetSessionStore 的会话键同一套。
 */
@Entity(
    tableName = "chat_message",
    indices = [Index(value = ["peer_username", "timestamp"])],
)
data class ChatMessage(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "peer_username")
    val peerUsername: String,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "status", defaultValue = "sealed")
    val status: String = STATUS_SEALED,
    /** text | voice | image | video | unsupported（裁决 R4）。 */
    @ColumnInfo(name = "kind", defaultValue = "text")
    val kind: String = KIND_TEXT,
    /**
     * 交出去的密文：发出的文字 = wire（分享/复制时再由 `ChatRepository.textShareText` 套上首行；
     * 卡片被顶掉或取消后仍可从气泡长按再分享）；发出的媒体 = R1 两行；
     * 收到的消息 = 定位出的 wire 行。媒体上传完成前、v3 之前的老行为 null。
     */
    @ColumnInfo(name = "share_text")
    val shareText: String? = null,
) {
    companion object {
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"
        const val STATUS_SEALED = "sealed"
        const val STATUS_COPIED = "copied"

        /**
         * The share sheet picked a target. Rows written by older versions also use `sent`
         * for a plain copy; that cannot be told apart and is shown as shared.
         */
        const val STATUS_SENT = "sent"

        const val KIND_TEXT = "text"
        const val KIND_VOICE = "voice"
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_UNSUPPORTED = "unsupported"

        fun kindForMedia(mediaKind: Int): String = when (mediaKind) {
            MediaConstants.KIND_VOICE -> KIND_VOICE
            MediaConstants.KIND_IMAGE -> KIND_IMAGE
            MediaConstants.KIND_VIDEO -> KIND_VIDEO
            else -> throw IllegalArgumentException("unknown media kind $mediaKind")
        }
    }
}
