package app.chencang.shared.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItem>)

    @Query("SELECT * FROM media_item WHERE message_id = :messageId ORDER BY idx ASC")
    suspend fun forMessage(messageId: String): List<MediaItem>

    @Query("SELECT * FROM media_item WHERE message_id = :messageId AND idx = :index")
    suspend fun get(messageId: String, index: Int): MediaItem?

    @Query(
        "SELECT mi.* FROM media_item mi JOIN chat_message m ON m.id = mi.message_id " +
            "WHERE m.peer_username = :peerUsername ORDER BY m.timestamp ASC, mi.idx ASC",
    )
    fun observeForPeer(peerUsername: String): Flow<List<MediaItem>>

    @Query(
        "SELECT mi.* FROM media_item mi JOIN chat_message m ON m.id = mi.message_id " +
            "WHERE m.peer_username = :peerUsername AND mi.state = :state ORDER BY m.timestamp ASC, mi.idx ASC",
    )
    suspend fun forPeerInState(peerUsername: String, state: String): List<MediaItem>

    @Query("UPDATE media_item SET state = :state WHERE message_id = :messageId AND idx = :index")
    suspend fun updateState(messageId: String, index: Int, state: String)

    /**
     * 「等待对方上传」再取时真开始收 blob（首个进度回调）→ 切到下载中。只在仍是 awaiting 时切。
     * **阻塞**版：下载进度回调是非挂起的，跑在 IO 线程上（不得在主线程调用）。
     */
    @Query(
        "UPDATE media_item SET state = '${MediaItem.STATE_DOWNLOADING}' " +
            "WHERE message_id = :messageId AND idx = :index AND state = '${MediaItem.STATE_AWAITING}'",
    )
    fun markDownloadingIfAwaitingBlocking(messageId: String, index: Int)

    /**
     * 这条消息里所有还没上传（≠ `sealed`）的条目改成 `failed`——一条原子 UPDATE。不能写成
     * 「先读再逐条写」：读完之后另一个上传把某项落成 `sealed`、删掉 `.cca`，再被这里写回
     * `failed`，就成了「文件丢失」的假象（对方其实已经能下载）。
     */
    @Query(
        "UPDATE media_item SET state = '${MediaItem.STATE_FAILED}' " +
            "WHERE message_id = :messageId AND state != '${MediaItem.STATE_SEALED}'",
    )
    suspend fun failUnsealed(messageId: String)

    @Query(
        "UPDATE media_item SET state = :state, local_path = :localPath " +
            "WHERE message_id = :messageId AND idx = :index",
    )
    suspend fun updateStateAndPath(messageId: String, index: Int, state: String, localPath: String)

    @Query(
        "UPDATE media_item SET blob_secret = :blobSecret, blob_id = :blobId, byte_len = :byteLen, state = :state " +
            "WHERE message_id = :messageId AND idx = :index",
    )
    suspend fun updateSealedBlob(
        messageId: String,
        index: Int,
        blobSecret: ByteArray,
        blobId: String,
        byteLen: Long,
        state: String,
    )

    @Query("UPDATE media_item SET played = 1 WHERE message_id = :messageId AND idx = :index")
    suspend fun markPlayed(messageId: String, index: Int)

    /**
     * `resetStates`（下方）的 Room `@Query`：SQL `NOT IN ()` 对空列表跑不动（依赖
     * SQLite 扩展），所以这层裸方法要求调用方永远传非空 [busy]；不要直接调它。
     */
    @Query(
        "UPDATE media_item SET state = :to WHERE state IN (:from) " +
            "AND message_id IN (SELECT id FROM chat_message WHERE peer_username = :peerUsername) " +
            "AND message_id NOT IN (:busy)",
    )
    suspend fun resetStatesRaw(peerUsername: String, from: List<String>, to: String, busy: List<String>)

    /**
     * 中断恢复：该线程里处于 [from] 状态、且不在 [busy]（正在跑的消息 id）里的条目改成 [to]。
     * [busy] 可以传空列表——[resetStatesRaw] 的 `NOT IN ()` 空列表限制在这里补一个
     * message_id 永远不会等于的占位值（空串），调用方不用关心这层。
     */
    suspend fun resetStates(peerUsername: String, from: List<String>, to: String, busy: List<String>) =
        resetStatesRaw(peerUsername, from, to, busy.ifEmpty { listOf("") })

    /** [failInterruptedSeals] 的裸 `@Query`：[busy] 必须非空（`NOT IN ()` 的限制同 [resetStatesRaw]）。 */
    @Query(
        "UPDATE media_item SET state = '${MediaItem.STATE_FAILED}' " +
            "WHERE message_id NOT IN (:busy) AND message_id IN " +
            "(SELECT id FROM chat_message WHERE peer_username = :peerUsername) " +
            "AND (state = '${MediaItem.STATE_ENCRYPTING}' OR (state = '${MediaItem.STATE_UPLOADING}' AND message_id IN " +
            "(SELECT id FROM chat_message WHERE share_text IS NULL OR share_text = '')))",
    )
    suspend fun failInterruptedSealsRaw(peerUsername: String, busy: List<String>)

    /**
     * 封缄阶段被中断（不在 [busy] 里）的发送条目改 `failed`：`encrypting`，或 `uploading` 但消息还没有分享文本。
     * 已分享消息的 `uploading` 项一律不动——它们归上传引擎（spec 2026-09-30 §1.2）。
     */
    suspend fun failInterruptedSeals(peerUsername: String, busy: List<String>) =
        failInterruptedSealsRaw(peerUsername, busy.ifEmpty { listOf("") })

    /**
     * 已分享（`share_text` 非空）、还有 `uploading` 项或**可重试的** `failed` 项（没有 `upload_failure`）的
     * 发出消息 id——上传引擎自愈的名单。永久失败（太大 / 被拒 / `.cca` 丢了）的项不算：自愈救不了它们。
     */
    @Query(
        "SELECT DISTINCT m.id FROM chat_message m JOIN media_item mi ON mi.message_id = m.id " +
            "WHERE m.direction = '${ChatMessage.DIRECTION_OUT}' AND m.share_text IS NOT NULL AND m.share_text != '' " +
            "AND (mi.state = '${MediaItem.STATE_UPLOADING}' " +
            "OR (mi.state = '${MediaItem.STATE_FAILED}' AND mi.upload_failure IS NULL))",
    )
    suspend fun sharedOutgoingWithUnuploadedItems(): List<String>

    /** 这一项永久上传失败：标 `failed` 并记下原因（[reason] = `MediaFailure` 的名字）。 */
    @Query(
        "UPDATE media_item SET state = '${MediaItem.STATE_FAILED}', upload_failure = :reason " +
            "WHERE message_id = :messageId AND idx = :index",
    )
    suspend fun failPermanently(messageId: String, index: Int, reason: String)

    /**
     * 这条消息里所有还没上传的项开始（重新）等待上传：`upload_since` = [since]、清掉永久失败原因。
     * 分享文本刚落库时、用户手动重试时调。已 `sealed` 的项不动。
     */
    @Query(
        "UPDATE media_item SET upload_since = :since, upload_failure = NULL " +
            "WHERE message_id = :messageId AND state != '${MediaItem.STATE_SEALED}'",
    )
    suspend fun startUploadWindow(messageId: String, since: Long)

    /** 手动重试：这一项（可重试的失败项）回到「上传中」，重新起算 6 小时窗口。 */
    @Query(
        "UPDATE media_item SET state = '${MediaItem.STATE_UPLOADING}', upload_failure = NULL, upload_since = :since " +
            "WHERE message_id = :messageId AND idx = :index",
    )
    suspend fun restartUpload(messageId: String, index: Int, since: Long)

    /** 这条消息还在等上传的项里最早的 `upload_since`；没有（全传完 / v4 之前的行）→ null。 */
    @Query(
        "SELECT MIN(upload_since) FROM media_item WHERE message_id = :messageId " +
            "AND state != '${MediaItem.STATE_SEALED}' AND upload_failure IS NULL",
    )
    suspend fun uploadSince(messageId: String): Long?

    /**
     * 有 `failed` 项的发出媒体消息的全部条目——会话列表据此算「[未上传] 」前缀（算状态要整条消息的项）。
     * 只取有失败项的消息：正常情况下这张表很小。
     */
    @Query(
        "SELECT mi.* FROM media_item mi JOIN chat_message m ON m.id = mi.message_id " +
            "WHERE m.direction = '${ChatMessage.DIRECTION_OUT}' AND mi.message_id IN " +
            "(SELECT message_id FROM media_item WHERE state = '${MediaItem.STATE_FAILED}') ORDER BY mi.idx ASC",
    )
    fun observeOutgoingWithFailures(): Flow<List<MediaItem>>

    /** Item count of every message with more than one media item (albums), for the conversation-list preview. */
    @Query(
        "SELECT message_id AS messageId, COUNT(*) AS count FROM media_item " +
            "GROUP BY message_id HAVING COUNT(*) > 1",
    )
    fun observeAlbumSizes(): Flow<List<MessageItemCount>>

    /**
     * 收件方过期（spec §3.6）：该线程里「收到的」消息时间 ≤ [cutoffMs]（= now − 24h）且处于 [from]
     * 的条目一律改成 [to]，不发任何请求。发出的消息不受影响（发送方本地留有明文）。
     */
    @Query(
        "UPDATE media_item SET state = :to WHERE state IN (:from) AND message_id IN " +
            "(SELECT id FROM chat_message WHERE peer_username = :peerUsername AND direction = 'in' " +
            "AND timestamp <= :cutoffMs)",
    )
    suspend fun expireStaleIncoming(peerUsername: String, cutoffMs: Long, from: List<String>, to: String)

    /** 该 peer 线程里有媒体条目的消息 id（清会话时只有这些可能挂着上传任务）。 */
    @Query(
        "SELECT DISTINCT mi.message_id FROM media_item mi JOIN chat_message m ON m.id = mi.message_id " +
            "WHERE m.peer_username = :peerUsername",
    )
    suspend fun messageIdsForPeer(peerUsername: String): List<String>

    @Query("DELETE FROM media_item WHERE message_id = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query(
        "DELETE FROM media_item WHERE message_id IN " +
            "(SELECT id FROM chat_message WHERE peer_username = :peerUsername)",
    )
    suspend fun deleteForPeer(peerUsername: String)

    @Query("DELETE FROM media_item")
    suspend fun clearAll()
}

/** Row of [MediaItemDao.observeAlbumSizes]. */
data class MessageItemCount(val messageId: String, val count: Int)
