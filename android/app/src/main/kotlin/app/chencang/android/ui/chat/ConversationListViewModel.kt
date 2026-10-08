package app.chencang.android.ui.chat

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.media.outgoingMediaStatus
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingPairingRecord
import app.chencang.shared.pairing.inband.awaitingInvites
import app.chencang.shared.pairing.inband.unsentResponseFingerprints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 会话列表 = 联系人 × 每会话最新一条消息(联系人即会话,spec §5.1)。没消息的联系人也列出来（配对完
 * 只在「联系人」里能看到，用户会以为丢了再加一遍），只有「回暗号还没发出去」的那种除外（它只在
 * 「配对中」，与联系人 tab 同一排除口径 [unsentResponseFingerprints]）。
 */
class ConversationListViewModel(
    contacts: Flow<List<Contact>>,
    latest: Flow<List<ChatMessage>>,
    /** 隐藏消息摘要开关(设置页)。会话列表用它决定预览文案是正文还是「已加密」。 */
    val summaryPrivacy: StateFlow<Boolean>,
    /** 有失败项的发出媒体消息的全部条目（算「[未上传] 」前缀用；只有失败才可能要前缀）。 */
    failedOutgoingMedia: Flow<List<MediaItem>>,
    /** Pending invites: a shared one counts as "pairing in progress" for the empty state (an unshared one does not). */
    invites: Flow<List<PendingPairingRecord>>,
    /** messageId -> number of media items, for albums (> 1 item) only; the preview then reads "[3 photos]". */
    itemCounts: Flow<Map<String, Int>>,
    /** 配对回应记录：回暗号没发出去的接受方联系人，没消息时不进会话列表。 */
    responses: Flow<List<PairingResponseRecord>>,
    /** 这一项的 `.cca` 还在不在（messageId, 下标）：分「对方还看不到」与永久失败（文件丢失）。 */
    ccaExists: (messageId: String, index: Int) -> Boolean,
) : ViewModel() {

    /**
     * [last]: the latest message, null for a contact with no messages yet (the preview is then
     * [placeholderRes]). [unsentPrefixRes]: the "[Not uploaded] " preview prefix (danger color) when the
     * last message can't be seen by the peer yet, else null. [itemCount]: media items in [last] (pass to
     * `MessagePreview.of`).
     */
    data class Row(
        val contact: Contact,
        val last: ChatMessage?,
        @StringRes val unsentPrefixRes: Int?,
        val itemCount: Int = 1,
    ) {
        /** 没消息时的副标题：接受了对方邀请的在等对方第一条消息，其余提示打个招呼；有消息时为 null。 */
        @get:StringRes
        val placeholderRes: Int?
            get() = when {
                last != null -> null
                contact.acceptedInviteDigest != null -> R.string.conversations_preview_waiting_peer
                else -> R.string.conversations_preview_no_messages
            }

        /** 行上显示、也用来排序的时间：最新一条消息的时间；没消息时为配对时间（≤ 0 = 不知道，返回 null）。 */
        val timeMillis: Long?
            get() = last?.timestamp ?: contact.pairedAt.takeIf { it > 0 }
    }

    val rows: StateFlow<List<Row>> =
        combine(contacts, latest, failedOutgoingMedia, itemCounts, responses) { cs, ms, media, counts, rs ->
            buildRows(cs, ms, media, counts, rs, ccaExists)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null = 还没读出来：界面此时不画空状态，免得「有联系人」的用户冷启动先闪 A 再变 B。 */
    val hasContactsOrPending: StateFlow<Boolean?> =
        combine(contacts, invites) { cs, inv -> cs.isNotEmpty() || inv.any { it.lastSharedAtMillis != null } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 在等对方回复的邀请数（会话 tab 顶部的提示行）；统一走 [awaitingInvites]。 */
    val awaitingCount: StateFlow<Int> = invites
        .map { awaitingInvites(it, System.currentTimeMillis()).size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    companion object {
        /**
         * 会话列表的行（纯函数）：有消息的联系人一行；没消息的联系人也一行，回暗号还没发出去的除外。
         * 消息已在库里但联系人已删的残留不出行。按 [Row.timeMillis] 倒序，不知道时间的排最后；
         * 同时间按指纹升序，顺序稳定。
         */
        fun buildRows(
            contacts: List<Contact>,
            latest: List<ChatMessage>,
            failedOutgoingMedia: List<MediaItem>,
            itemCounts: Map<String, Int>,
            responses: List<PairingResponseRecord>,
            ccaExists: (messageId: String, index: Int) -> Boolean,
        ): List<Row> {
            val byPeer = latest.associateBy { it.peerUsername }
            val itemsByMessage = failedOutgoingMedia.groupBy { it.messageId }
            val unsent = unsentResponseFingerprints(responses, contacts.map { it.fingerprintHex }.toSet())
            return contacts.mapNotNull { c ->
                val last = byPeer[c.username]
                when {
                    last != null -> Row(
                        c, last, unsentPrefixRes(last, itemsByMessage[last.id].orEmpty(), ccaExists), itemCounts[last.id] ?: 1,
                    )
                    c.fingerprintHex in unsent -> null
                    else -> Row(c, null, unsentPrefixRes = null)
                }
            }.sortedWith(
                compareByDescending<Row> { it.timeMillis ?: Long.MIN_VALUE }.thenBy { it.contact.fingerprintHex },
            )
        }

        @StringRes
        private fun unsentPrefixRes(
            last: ChatMessage,
            items: List<MediaItem>,
            ccaExists: (messageId: String, index: Int) -> Boolean,
        ): Int? {
            if (last.direction != ChatMessage.DIRECTION_OUT || items.isEmpty()) return null
            return outgoingMediaStatus(items, shared = !last.shareText.isNullOrEmpty()) { ccaExists(last.id, it) }
                .listPreviewPrefixRes
        }
    }
}
