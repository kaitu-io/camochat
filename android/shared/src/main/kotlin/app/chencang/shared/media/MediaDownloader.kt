package app.chencang.shared.media

import app.chencang.shared.chat.ChatMessageDao
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.MediaItemDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * spec §3.6 收件：打开线程时自动拉语音与图片，视频点了才拉；全局最多两个并发。
 * 过期（收到时间 + 24h ≤ now）直接标「已过期」、不发请求；403/404 按 [classifyGone] 区分：收到不满 24 h →
 * 「等待对方上传」（`awaiting`，可再取），否则「已过期」（先分享、后上传 spec §2）。
 *
 * `awaiting` 再取（轮询 / 点重试）：请求期间保持 `awaiting`（403 时气泡不闪），真开始收 blob（首个进度回调）
 * 才切到 `downloading`；这次再取遇断网 / 5xx / 429（含下到一半失败）→ 回到 / 保持 `awaiting`、不提示，交给
 * 下一轮轮询。首次下载遇同样错误仍是 `failed`。同一项同时只发一个请求（[busy] 认领）。与 iOS 同口径。
 * 下载后先比长度（≠ 帧里的 byte_len → 「文件已损坏」，不解密），再解密。落盘顺序是纪律：
 * 先写 `.bin`、再落库 `ready`、最后才删 `.cca`——落库这步失败也不能提前把密文删了，
 * 不然重试要么白白重新下载，要么撞上中转对象已经过期，而明文其实早就写好了。
 *
 * 下载途中用户长按删掉了这条消息（同一组件 iOS 复审裁决，Android 一并落实，mirrors
 * [MediaSender]）：在传输调用之后、解密之后各查一次消息是否还在——不在就清掉整个消息
 * 目录（可能是空的，也可能刚写了 `.cca`），什么记录都不写，安静结束，不复活。
 */
class MediaDownloader(
    private val dao: ChatMessageDao,
    private val mediaDao: MediaItemDao,
    private val files: MediaFiles,
    private val crypto: MediaCrypto,
    private val transport: MediaTransport,
    private val now: () -> Long = System::currentTimeMillis,
    maxConcurrent: Int = MediaConstants.MAX_CONCURRENT_DOWNLOADS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val gate = Semaphore(maxConcurrent)

    /** 正在以「等待对方上传」语境再取的条目（progress key）：被取消时退回 awaiting 而不是 pending。 */
    private val awaitingContext: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** 正在下载的条目（progress key）。 */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** 该线程所有 `pending` 的语音/图片（不含视频）并发下载，总并发受 [gate] 限制到 ≤ [MediaConstants.MAX_CONCURRENT_DOWNLOADS]。 */
    suspend fun autoDownload(peerUsername: String) = coroutineScope {
        mediaDao.forPeerInState(peerUsername, MediaItem.STATE_PENDING)
            .filter { it.kind == MediaConstants.KIND_VOICE || it.kind == MediaConstants.KIND_IMAGE }
            .forEach { item -> launch { download(item.messageId, item.index) } }
    }

    /** `null` = 成功或无事可做（条目/消息不存在、不在可下载状态里）。 */
    suspend fun download(messageId: String, index: Int): MediaFailure? {
        val key = MediaConstants.progressKey(messageId, index)
        if (!claim(key)) return null
        try {
            return gate.withPermit { withContext(io) { fetch(messageId, index, key) } }
        } catch (e: CancellationException) {
            // 被取消（离开线程 / 界面效应重启）：条目若停在 downloading，放回 pending，
            // 下一次自动下载或点击就能接上——不然它既不在 pending 集合里、也点不动、也不会过期，
            // 一直转圈（终审 I2）。这一步必须在 NonCancellable 里跑，且先于释放 busy（finally）。
            withContext(NonCancellable) { releaseInterrupted(messageId, index) }
            throw e
        } finally {
            // 非挂起：协程被取消时 finally 里也一定会跑到，key 一定释放。
            _busy.update { it - key }
            awaitingContext -= key // 在 releaseInterrupted 之后才清：它要据此退回 awaiting
        }
    }

    /** 线程可见时先调：收到已超 24 h 的待下载/失败/等待上传条目（包括没点开的视频）直接标「已过期」，不发请求。 */
    suspend fun expireStale(peerUsername: String) = withContext(io) {
        mediaDao.expireStaleIncoming(
            peerUsername = peerUsername,
            cutoffMs = now() - MediaConstants.EXPIRY_MS,
            from = listOf(MediaItem.STATE_PENDING, MediaItem.STATE_FAILED, MediaItem.STATE_AWAITING),
            to = MediaItem.STATE_EXPIRED,
        )
    }

    /**
     * 进线程时调：不在 [busy] 里的 `downloading` 条目都是被中断的，改回 `pending`。
     * `awaiting` 不动——冷启动后仍是「等待对方上传」，由轮询接着试。
     */
    suspend fun recoverInterrupted(peerUsername: String) = withContext(io) {
        val busyMessages = _busy.value.map { it.substringBeforeLast(':') }
        mediaDao.resetStates(
            peerUsername = peerUsername,
            from = listOf(MediaItem.STATE_DOWNLOADING),
            to = MediaItem.STATE_PENDING,
            busy = busyMessages.ifEmpty { listOf("") },
        )
    }

    private suspend fun releaseInterrupted(messageId: String, index: Int) {
        try {
            val item = mediaDao.get(messageId, index) ?: return
            if (item.state == MediaItem.STATE_DOWNLOADING) {
                // 等待语境里的再取被取消：回到「等待对方上传」，不是「未下载」。
                val back = if (MediaConstants.progressKey(messageId, index) in awaitingContext) {
                    MediaItem.STATE_AWAITING
                } else {
                    MediaItem.STATE_PENDING
                }
                mediaDao.updateState(messageId, index, back)
            }
        } catch (e: Exception) {
            // 复位本身失败（数据库已关等）不能顶替原本的取消；进线程时 recoverInterrupted 还会兜底。
        }
    }

    /** 认领不到（已经在 [busy] 里）返回 false；调用方据此原样返回，不重复处理同一条目。 */
    private fun claim(key: String): Boolean {
        var claimed = false
        _busy.update { current ->
            if (key in current) {
                current
            } else {
                claimed = true
                current + key
            }
        }
        return claimed
    }

    private suspend fun fetch(messageId: String, index: Int, key: String): MediaFailure? {
        val item = mediaDao.get(messageId, index) ?: return null
        if (item.state !in FETCHABLE) return null
        val msg = dao.getById(messageId) ?: return null
        if (MediaConstants.isExpired(msg.timestamp, now())) {
            mediaDao.updateState(messageId, index, MediaItem.STATE_EXPIRED)
            return MediaFailure.GONE
        }
        // 「等待对方上传」语境（轮询 / 点重试的再取）：请求期间保持 awaiting，首个进度回调才切下载中；
        // 暂时性失败不打成 failed。
        val wasAwaiting = item.state == MediaItem.STATE_AWAITING
        if (wasAwaiting) {
            awaitingContext += key
        } else {
            mediaDao.updateState(messageId, index, MediaItem.STATE_DOWNLOADING)
        }

        val blob = try {
            var switched = !wasAwaiting
            transport.download(item.blobId, item.byteLen) { p ->
                if (!switched) {
                    switched = true
                    // 回调在 IO 线程上、非挂起：用阻塞版 DAO，且只在仍是 awaiting 时切。
                    mediaDao.markDownloadingIfAwaitingBlocking(messageId, index)
                }
                _progress.update { it + (key to p.toFloat()) }
            }
        } catch (e: MediaFailureException) {
            val next = when {
                e.failure == MediaFailure.GONE -> classifyGone(msg.timestamp, now())
                e.failure == MediaFailure.CORRUPT -> MediaItem.STATE_CORRUPT
                // 轮询中的暂时性失败：保持（或从下载中退回）awaiting，不提示，下一轮再试；
                // 一直不通由 30 分钟窗口收尾（「还没收到文件 · 点击重试」）。
                wasAwaiting && e.failure in TRANSIENT -> MediaItem.STATE_AWAITING
                else -> MediaItem.STATE_FAILED
            }
            mediaDao.updateState(messageId, index, next)
            return e.failure
        } finally {
            _progress.update { it - key }
        }

        if (abandonedMidTransfer(messageId)) return MediaFailure.DELETED

        // 传输层已按 byte_len 截断/校验；这里是兜底。长度不符是传输问题（终审 F3）→ NETWORK，与传输层同一口径：
        // 轮询中保持 awaiting，否则 failed（可点重试），不打成终态「文件已损坏」。
        if (blob.size.toLong() != item.byteLen) {
            mediaDao.updateState(messageId, index, if (wasAwaiting) MediaItem.STATE_AWAITING else MediaItem.STATE_FAILED)
            return MediaFailure.NETWORK
        }

        val cca = files.cca(messageId, index)
        val bin = files.bin(messageId, index)
        val plain: ByteArray
        try {
            files.write(cca, blob)
            plain = try {
                crypto.decrypt(blob, item.blobSecret, item.kind)
            } catch (e: uniffi.chencang.ChencangException) {
                cca.delete()
                mediaDao.updateState(messageId, index, MediaItem.STATE_CORRUPT)
                return MediaFailure.CORRUPT
            }
        } catch (e: IOException) {
            return failWrite(messageId, index, e, cca)
        }

        if (abandonedMidTransfer(messageId)) return MediaFailure.DELETED

        // 顺序是纪律:先写明文、再落库「已就绪」、最后才删密文。中途(尤其落库这一步)
        // 被杀掉,`.cca` 必须还在——不然重试要么白白重新下载,要么撞上中转对象已经
        // 过期,而明文其实早就写好了(复审 Important)。
        try {
            files.write(bin, plain)
        } catch (e: IOException) {
            // 明文没写成功,密文本来就还在磁盘上,不用删。
            return failWrite(messageId, index, e)
        }
        try {
            mediaDao.updateStateAndPath(messageId, index, MediaItem.STATE_READY, bin.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 落库失败:明文已经在盘上了,但没法信它——`.cca` 留着,交给重试。
            mediaDao.updateState(messageId, index, MediaItem.STATE_FAILED)
            return MediaFailure.NOT_READY
        }
        cca.delete()
        return null
    }

    /** `.cca`/`.bin` 写失败（多半是存储空间不足）：标 `failed`，可再下载；[cca] 非空才删。 */
    private suspend fun failWrite(messageId: String, index: Int, e: IOException, cca: File? = null): MediaFailure {
        cca?.delete()
        mediaDao.updateState(messageId, index, MediaItem.STATE_FAILED)
        return if (e is StorageFullException) MediaFailure.STORAGE_FULL else MediaFailure.NOT_READY
    }

    /**
     * 消息在下载途中被删（用户长按「删除」）：目录可能还是空的，也可能刚写了 `.cca`——
     * 一律清掉，不写任何记录（消息行已经不在，再落库也是无效更新）。
     */
    private suspend fun abandonedMidTransfer(messageId: String): Boolean {
        if (dao.getById(messageId) != null) return false
        files.deleteMessage(messageId)
        return true
    }

    private companion object {
        val FETCHABLE = setOf(
            MediaItem.STATE_PENDING,
            MediaItem.STATE_FAILED,
            MediaItem.STATE_DOWNLOADING,
            MediaItem.STATE_AWAITING,
        )

        /** 断网 / 5xx / 429：过一会儿再试可能就好了（下载侧的 [MediaFailure.SERVER] = 403/404/429 之外的非 200）。 */
        val TRANSIENT = setOf(MediaFailure.NETWORK, MediaFailure.SERVER, MediaFailure.RATE_LIMITED)
    }
}

/**
 * 中转对这条引用答 403/404（「还没上传」与「已过期」在中转上都是 403）时的判定（先分享、后上传 spec §2）：
 * 本地收到不满 24 h → [MediaItem.STATE_AWAITING]（等待对方上传），否则 [MediaItem.STATE_EXPIRED]。
 * 与 iOS `classifyGone` 同口径。
 */
fun classifyGone(receivedAtMs: Long, nowMs: Long): String =
    if (nowMs - receivedAtMs < MediaConstants.EXPIRY_MS) MediaItem.STATE_AWAITING else MediaItem.STATE_EXPIRED

