package app.chencang.shared.chat

import android.util.Log
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.media.MediaShareText
import app.chencang.shared.media.ShareHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import uniffi.chencang.DecodedMessage
import uniffi.chencang.MediaRef
import uniffi.chencang.decodeFrame
import uniffi.chencang.decodeWire
import uniffi.chencang.encodeMediaRefFrame
import uniffi.chencang.encodeTextFrame
import uniffi.chencang.encodeWire
import uniffi.chencang.mediaBlobId
import java.io.IOException

interface WireReceiver {
    /**
     * 解密一段粘贴/选中的文字;成功→已落库的 in 消息,不是陈仓密文或无会话→null。
     * 解密成功但落库失败 → 抛出(棘轮已推进,不能装作「不是密文」)。
     */
    suspend fun receiveWireText(wire: String): ChatMessage?
}

/**
 * 收发编排层:把 [SessionManager] 的加解密、`uniffi.chencang` 的 wire/帧编解码、
 * 与 [ChatMessageDao] / [MediaItemDao] 的落库粘在一起。媒体的上传/下载不在这里
 * （见 `media/MediaSender`、`media/MediaDownloader`）；这里只负责帧与入库。
 */
class ChatRepository(
    private val dao: ChatMessageDao,
    private val sessions: SessionManager,
    private val mediaDao: MediaItemDao,
    private val mediaFiles: MediaFiles,
    private val inTransaction: InTransaction,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    /**
     * 取消这条消息的后台上传（生产 = WorkManager `cancelUniqueWork`）。删消息/清会话时在删文件**之前**调，
     * 免得上传任务去读正在被删的 `.cca`。只收发文字的测试不关心，默认什么都不做。
     */
    private val cancelUpload: suspend (messageId: String) -> Unit = {},
    /**
     * 一条收到的消息成功落库之后调（生产 = `PairingCoordinator.forgetPeer`）：能解开对方的消息 = 对方已完成配对，
     * 接受方留着的「可重发的回应暗号」可以清掉了（spec three-tab-shell §3.5.6）。解不开、没落库时不调。
     */
    private val onIncomingStored: suspend (peerUsername: String) -> Unit = {},
    /** First line of every share text, in the system language at the moment of sharing/receiving. */
    private val shareHeaders: ShareHeaders,
) : WireReceiver {

    data class Sealed(val message: ChatMessage, val wire: String)

    /** 加密 [text] 给 [peerUsername],落库 out 消息,返回可粘贴的 "🔒…" wire 文本。 */
    suspend fun sendText(peerUsername: String, text: String): String = sendTextSealed(peerUsername, text).wire

    /** 同 [sendText],但把落库的消息一并返回(线程 UI 需要 id 做 markSent)。 */
    suspend fun sendTextSealed(peerUsername: String, text: String): Sealed {
        val frame = encodeTextFrame(text)
        val ct = sessions.encryptToBytes(peerUsername, frame)
        val wire = encodeWire(ct)
        val msg = ChatMessage(
            id = newId(),
            peerUsername = peerUsername,
            direction = ChatMessage.DIRECTION_OUT,
            body = text,
            timestamp = now(),
            shareText = wire,
        )
        dao.insert(msg)
        return Sealed(msg, wire)
    }

    /** The text a user copies or shares for an outgoing text message: header line + newline + [wire]. */
    fun textShareText(wire: String): String = MediaShareText.compose(shareHeaders.text(), wire)

    /** `MEDIA_REF` 帧走和文字一样的 DR 加密，得到 "🔒…" wire（MediaSender 在全部加密完、上传之前调）。 */
    suspend fun sealMediaFrame(peerUsername: String, refs: List<MediaRef>): String =
        encodeWire(sessions.encryptToBytes(peerUsername, encodeMediaRefFrame(refs)))

    /** 用户把 wire 复制/分享出去后,把这条 out 消息标记为已送出。 */
    suspend fun markSent(id: String) = dao.updateStatus(id, ChatMessage.STATUS_SENT)

    suspend fun message(id: String): ChatMessage? = dao.getById(id)

    /** The stored message carrying this exact wire (see [ChatMessageDao.findByWire]), or null. */
    suspend fun findByWire(wire: String): ChatMessage? = dao.findByWire(wire)

    enum class MarkResult { MARKED, UNCHANGED, NOT_OWNED }

    /** Each transition is one guarded UPDATE, so a concurrent copy can never overwrite `sent`. */
    private suspend fun resultOf(changed: Int, id: String, peerUsername: String): MarkResult {
        if (changed == 1) return MarkResult.MARKED
        val msg = dao.getById(id) ?: return MarkResult.NOT_OWNED
        val owned = msg.peerUsername == peerUsername && msg.direction == ChatMessage.DIRECTION_OUT
        return if (owned) MarkResult.UNCHANGED else MarkResult.NOT_OWNED
    }

    /**
     * Share sheet picked a target: `sealed` or `copied` -> `sent`. Only an own outgoing message
     * in [peerUsername]'s thread is touched; anything else is [MarkResult.NOT_OWNED].
     */
    suspend fun markSentIfOwned(id: String, peerUsername: String): MarkResult =
        resultOf(dao.markSentGuarded(id, peerUsername), id, peerUsername)

    /** User tapped copy: `sealed` -> `copied`. `copied`/`sent` stay as they are (never downgrades). */
    suspend fun markCopiedIfOwned(id: String, peerUsername: String): MarkResult =
        resultOf(dao.markCopiedGuarded(id, peerUsername), id, peerUsername)

    override suspend fun receiveWireText(wire: String): ChatMessage? {
        val located = WireLocator.extract(wire) ?: return null
        val ct = runCatching { decodeWire(located) }.getOrNull() ?: return null
        // 解密与落库合在同一个不可取消区间里：RatchetSessionStore.decryptFromBytesAny
        // 一旦推进棘轮就会用一次 suspend Room 写把新状态落盘（persistLocked，即便
        // AEAD 没解开——见 RatchetSessionStore#142 的注释），这次写和消息落库一样，
        // 既不能被 ProcessTextActivity 的 lifecycleScope 取消打断，也不能让持久化
        // 失败悄悄被当成「不是密文」。只有「确实没有会话解得开」才算「不是我们的」——
        // 即 RatchetSessionStore.NoSessionMatched，或不支持多会话查找的后端按接口
        // 约定直接返回 null；除此之外的任何异常（含 persistLocked 的存储失败）一律
        // 向上抛，让 IncomingIntake 报 SAVE_FAILED，而不是把消息悄悄丢掉。
        return withContext(NonCancellable) {
            val any = try {
                sessions.decryptFromBytesAny(ct)
            } catch (_: RatchetSessionStore.NoSessionMatched) {
                null
            } ?: return@withContext null
            val decoded: DecodedMessage? = try {
                decodeFrame(any.plaintext)
            } catch (_: Exception) {
                null
            }
            val id = newId()
            val ts = now()
            var saved: ChatMessage? = null
            inTransaction {
                saved = when (decoded) {
                    is DecodedMessage.Text -> insert(
                        ChatMessage(
                            id = id, peerUsername = any.senderKey, direction = ChatMessage.DIRECTION_IN,
                            body = decoded.value, timestamp = ts, shareText = located,
                        ),
                    )
                    is DecodedMessage.Media -> receiveMedia(id, any.senderKey, ts, located, decoded.refs)
                    null -> insert(unsupportedMessage(id, any.senderKey, ts, located))
                }
            }
            requireNotNull(saved).also { notifyIncomingStored(it.peerUsername) }
        }
    }

    /**
     * 消息已经落库、棘轮已经推进：这之后的回调失败不能让收件报「保存失败」（用户会以为消息丢了），
     * 只记类名；下一条消息到来时回调会再跑一次。调用点在不可取消区间里，所以回调自己抛出的
     * [CancellationException]（它内部的作用域被取消）同样只是「回调没做成」，不再上抛。
     */
    private suspend fun notifyIncomingStored(peerUsername: String) {
        try {
            onIncomingStored(peerUsername)
        } catch (e: Exception) {
            Log.w(TAG, "post-receive pairing cleanup failed: ${e.javaClass.simpleName}")
        }
    }

    private fun unsupportedMessage(id: String, peer: String, ts: Long, located: String) = ChatMessage(
        id = id, peerUsername = peer, direction = ChatMessage.DIRECTION_IN,
        body = "", timestamp = ts,
        kind = ChatMessage.KIND_UNSUPPORTED, shareText = located,
    )

    /**
     * 媒体帧只落引用，不下载（spec §4.2）。调用方已在事务里；条目先入、消息后入。
     * `shareText` 存完整的 R1 两行（「复制密文」要给两行，即使对方只粘了 wire 行）。
     * 收件时间就是本机收到的时间——帧里不带发送时间，过期判定对收件方按此计；
     * 云端真删了会由 403/404 兜底成「已过期」。
     *
     * 一帧里的 refs 混了不同 kind（协议目前不产生这种帧，但线路上收到的字节不可信）：
     * 不按首条 ref 瞎贴标签，落 R3 升级占位（与 iOS 同款裁决），不落任何媒体条目。
     */
    private suspend fun receiveMedia(
        id: String,
        peer: String,
        ts: Long,
        located: String,
        refs: List<MediaRef>,
    ): ChatMessage {
        val kinds = refs.map { it.kind }.toSet()
        if (kinds.size > 1) return insert(unsupportedMessage(id, peer, ts, located))
        val firstKind = refs.first().kind.toInt()
        mediaDao.insertAll(
            refs.mapIndexed { i, r ->
                MediaItem(
                    messageId = id,
                    index = i,
                    kind = r.kind.toInt(),
                    durMs = r.durMs.toInt(),
                    width = r.width.toInt(),
                    height = r.height.toInt(),
                    byteLen = r.byteLen.toLong(),
                    blobSecret = r.blobSecret,
                    blobId = mediaBlobId(r.blobSecret),
                    state = MediaItem.STATE_PENDING,
                )
            },
        )
        return insert(
            ChatMessage(
                id = id,
                peerUsername = peer,
                direction = ChatMessage.DIRECTION_IN,
                body = "",
                timestamp = ts,
                kind = ChatMessage.kindForMedia(firstKind),
                shareText = MediaShareText.compose(
                    shareHeaders.media(firstKind, refs.size, mediaBlobId(refs.first().blobSecret)),
                    located,
                ),
            ),
        )
    }

    private suspend fun insert(msg: ChatMessage): ChatMessage {
        dao.insert(msg)
        return msg
    }

    fun observeThread(peerUsername: String): Flow<List<ChatMessage>> =
        dao.observeThread(peerUsername)

    /** 线程里所有媒体条目（按消息时间、条目序号排）。 */
    fun observeThreadMedia(peerUsername: String): Flow<List<MediaItem>> =
        mediaDao.observeForPeer(peerUsername)

    /** 会话列表:每个 peer 的最新一条(排序在 ViewModel 做)。媒体消息的 body 为空，预览由 [MessagePreview] 给出。 */
    fun observeLatestPerPeer(): Flow<List<ChatMessage>> = dao.observeLatestPerPeer()

    /** 会话列表的「[未上传] 」前缀：有失败项的发出媒体消息的全部条目。 */
    fun observeFailedOutgoingMedia(): Flow<List<MediaItem>> = mediaDao.observeOutgoingWithFailures()

    /** Conversation list: messageId -> item count for albums (messages with more than one media item). */
    fun observeAlbumSizes(): Flow<Map<String, Int>> =
        mediaDao.observeAlbumSizes().map { rows -> rows.associate { it.messageId to it.count } }

    suspend fun markPlayed(messageId: String, index: Int) = mediaDao.markPlayed(messageId, index)

    /** 一个媒体条目的当前状态（接收端轮询据此判断还要不要继续试）。 */
    suspend fun mediaItem(messageId: String, index: Int): MediaItem? = mediaDao.get(messageId, index)

    /**
     * 长按「删除」：仅本机——删消息行、媒体条目，取消它的后台上传，再删 `media/<id>/` 目录。
     * 取消失败不算删除失败（行已经没了，任务之后只会拿到「消息已删」、什么都不写）：只记类名、照删文件。
     *
     * 整段（含事务）不可取消（N2b）：Room 事务在调用方被取消时照样可能提交，而 `withContext` 返回时再抛取消——
     * 事务之后的取消上传 / 删目录若能被打断，就留下一份再也没有行能触发清理的明文。删除是用户意图，取消后也做完。
     */
    suspend fun deleteMessage(id: String) = withContext(NonCancellable) {
        inTransaction {
            mediaDao.deleteForMessage(id)
            dao.deleteById(id)
        }
        try {
            cancelUploadLogged(id)
        } finally {
            mediaFiles.deleteMessage(id)
        }
    }

    /**
     * 联系人删除的一部分:清空该 peer 的整条线程(含媒体文件),不动其他会话。
     * 与 [deleteMessage] 同理，整段（含事务）不可取消：删联系人后立刻返回、配对拒绝后退出向导都不能打断它。
     */
    suspend fun clearThread(peerUsername: String) = withContext(NonCancellable) {
        // id 必须在删行的同一个事务里读（终审 M4）：事务外先读，读完到删之间并发插进来的媒体消息
        // 行会被删掉、目录却不在名单里——留下一份再也没有行能触发清理的明文。
        var ids: List<String> = emptyList()
        var mediaIds: List<String> = emptyList()
        inTransaction {
            ids = dao.idsForPeer(peerUsername)
            mediaIds = mediaDao.messageIdsForPeer(peerUsername)
            mediaDao.deleteForPeer(peerUsername)
            dao.clearPeer(peerUsername)
        }
        // 只有媒体消息才可能有上传任务；先取消、再删目录（取消失败只记日志，同 deleteMessage）。
        mediaIds.forEach { cancelUploadLogged(it) }
        // A directory that fails to delete must not stop the rest of the ids from being
        // attempted too. Attempt all, then report the ones that survived as a single failure.
        val undeleted = ids.filter { id ->
            try {
                mediaFiles.deleteMessage(id)
                false
            } catch (_: IOException) {
                true
            }
        }
        if (undeleted.isNotEmpty()) throw IOException("media dirs not deleted: $undeleted")
    }

    private suspend fun cancelUploadLogged(id: String) {
        try {
            cancelUpload(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cancel upload failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "Chencang"
    }
}
