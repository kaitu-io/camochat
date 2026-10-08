package app.chencang.android.ui.chat

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.OutgoingMediaStatus
import app.chencang.shared.media.outgoingMediaStatus

/**
 * 线程里的一行：文字/占位 = 一条消息一行；媒体 = 每个条目一行（多图展开，spec §3.4）。
 * [outStatus]：自己发出的媒体消息的整体状态（spec 2026-09-30 §1.3），只挂在这条消息的**最后一行**上
 * （状态行与红「!」一条消息只出一次，与 iOS 同）；其余行为 null。
 */
data class ThreadRow(
    val key: String,
    val message: ChatMessage,
    val item: MediaItem?,
    val outStatus: OutgoingMediaStatus? = null,
)

object ThreadRows {
    private val MEDIA_KINDS = setOf(ChatMessage.KIND_VOICE, ChatMessage.KIND_IMAGE, ChatMessage.KIND_VIDEO)

    /** [ccaExists]（messageId, 下标）只对失败项调用，决定「对方还看不到」还是永久失败。 */
    fun build(
        messages: List<ChatMessage>,
        items: List<MediaItem>,
        ccaExists: (messageId: String, index: Int) -> Boolean,
    ): List<ThreadRow> {
        val byMessage = items.groupBy { it.messageId }
        return messages.flatMap { m ->
            if (m.kind in MEDIA_KINDS) {
                val own = byMessage[m.id].orEmpty().sortedBy { it.index }
                val status = if (m.direction == ChatMessage.DIRECTION_OUT && own.isNotEmpty()) {
                    outgoingMediaStatus(own, shared = !m.shareText.isNullOrEmpty()) { ccaExists(m.id, it) }
                } else {
                    null
                }
                own.mapIndexed { i, item ->
                    ThreadRow(MediaConstants.progressKey(m.id, item.index), m, item, status.takeIf { i == own.lastIndex })
                }
            } else {
                listOf(ThreadRow(m.id, m, null))
            }
        }
    }

    /** 这一行的媒体文件在本机可用（自己发的有本地路径即可；收到的须已下载完成）——决定能不能「转发」。 */
    fun hasLocalFile(row: ThreadRow): Boolean {
        val item = row.item ?: return false
        return item.localPath != null &&
            (row.message.direction == ChatMessage.DIRECTION_OUT || item.state == MediaItem.STATE_READY)
    }

    /**
     * 「转发全部 N 张」：相册（同一条消息的多张图）里每一张都已在本机才出；有一张没下载就不出，
     * 免得点了才报「文件还没下载完成」。
     */
    fun canForwardAll(album: List<ThreadRow>): Boolean =
        album.size > 1 &&
            album.all { it.item?.kind == MediaConstants.KIND_IMAGE && hasLocalFile(it) }
}
