package app.chencang.shared.media

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatMessageDao
import app.chencang.shared.chat.InTransaction
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.MediaItemDao
import app.chencang.shared.chat.MessagePreview
import app.chencang.shared.i18n.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import uniffi.chencang.MediaRef
import java.io.File
import java.io.IOException
import java.util.UUID

/** [MediaSender.upload] 的结果；上传引擎（Task 2 的 WorkManager Worker）据此决定成功/重试/放弃。 */
sealed class UploadOutcome {
    /** 这条消息的每一项都已在中转上（含 412 = 之前那次其实传上去了）。 */
    object Done : UploadOutcome()

    /** 网络 / 3xx / 5xx / 403 / 408 / 429 / 读文件偶发错：项保持 `uploading`，`.cca` 保留，稍后再试同一份密文。 */
    data class Retryable(val failure: MediaFailure) : UploadOutcome()

    /**
     * `TOO_LARGE`、`REJECTED`（其余 4xx）、`.cca` 丢失：出问题的**那几项**已标 `failed`，其余项照常传完；
     * [failure] 是第一个。
     * 消息已删（`DELETED`）：什么都不写。都别再试。
     */
    data class Permanent(val failure: MediaFailure) : UploadOutcome()
}

/**
 * 发送分两段（spec 2026-09-30「先分享、后上传」，修订 2026-09-25 §3.5 的时序）：
 *
 * **[seal]**（= [send]，零网络）
 * 1. 明文挪进 `media/<id>/<i>.bin` → 落库（消息 + 条目 `encrypting`，两条写在一个事务里）
 * 2. 每个条目 `encryptMediaBlob` → 先落库新 secret/blob_id/byte_len（`uploading`）→ 再写 `.cca`
 *    （崩在中间最多留一条「有 secret 没 .cca」的记录，下次会安全地用新 secret 重新加密；
 *    反过来会让新密文配着旧 secret 卡住——旧 secret 解不开新密文，对端永远读不出来）
 * 3. 全部加密完 → 合成一个 `MEDIA_REF` 帧 → [sealFrame] 得 wire → R1 两行文本存进 `share_text`
 * 4. 返回 [Result.Sealed]（此刻即可分享），并把 messageId 交给 [uploadScheduler]
 *
 * **[upload]**（幂等，可被上传引擎反复调）：逐项重新签名 → PUT 同一份 `.cca`
 * （`If-None-Match: *`，412 算成功），成功一项即先落库 `sealed` 再删该项 `.cca`。
 *
 * **不变量：分享出去之后永不重新加密。** `share_text` 非空的消息，每项的
 * `blob_secret`/`blob_id`/`.cca` 都冻结；`.cca` 丢了只能报 [MediaFailure.FILE_MISSING]。
 * [retry]：没分享过（加密/封帧阶段失败）→ 重走 seal（允许重新加密缺 `.cca` 的项）；
 * 已分享 → 只交给 [uploadScheduler]，分享文本原样返回。
 * [forward] 从 `.bin` 重新走一遍 [seal]（新 secret、新消息）。
 *
 * 并发/失败纪律（同一组件 iOS 复审裁决，Android 一并落实）：
 * - 同一条消息同时只处理一次——[retry]/[send] 都经 [claim] 认领 [busy]；[send] 在任何 DB
 *   写入之前就认领，[recoverInterrupted] 不会把刚插入、还没来得及上传的条目误判成
 *   「被中断」。认领不到（已经在跑）就直接回 [Result.InProgress]，不重复处理。
 * - 任何未被下面显式分支处理的异常（含 DAO 写失败）不能被 `runCatching` 吞掉当成什么都
 *   没发生——先把这条消息里还没 `sealed` 的条目标 `failed`（不留转圈），再原样抛出；
 *   标记本身再失败就把它挂成 suppressed，不能顶替掉真正的原始异常。
 */
class MediaSender(
    private val dao: ChatMessageDao,
    private val mediaDao: MediaItemDao,
    private val files: MediaFiles,
    private val crypto: MediaCrypto,
    private val transport: MediaTransport,
    private val sealFrame: suspend (peerUsername: String, refs: List<MediaRef>) -> String,
    private val inTransaction: InTransaction,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 把一条已分享的消息交给上传引擎（生产 = WorkManager 唯一任务，测试 = 记录型假实现）。 */
    private val uploadScheduler: (messageId: String) -> Unit,
    /**
     * 用户手动重试一条已分享的消息：「现在就试」——不排在剩下的退避后面（生产 = `UploadEngine.enqueueNow`）。
     */
    private val uploadNow: suspend (messageId: String) -> Unit,
    /** First line of the share text, in the system language at the moment of sealing. */
    private val shareHeaders: ShareHeaders,
) {
    sealed interface Result {
        val messageId: String

        /** [summary] = the one-line preview for the seal card (`[Photo]`, `[3 photos]`, ...). */
        data class Sealed(override val messageId: String, val shareText: String, val summary: UiText) : Result
        data class Failed(override val messageId: String, val failure: MediaFailure) : Result

        /** 同一条消息已经有一个 [send]/[retry] 在跑：这次调用被忽略，什么都没做。 */
        data class InProgress(override val messageId: String) : Result

        /**
         * [retry] 了一条**已分享**的消息：只把还没上传的项重新交给上传引擎（`.cca` 还在的项），
         * 分享文本不变——界面不能因此再弹封缄卡或分享面板（spec §1.3）。
         */
        data class Rescheduled(override val messageId: String) : Result
    }

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** 正在加密/上传/封帧的消息 id；没分享过、又不在集合里的 `encrypting/uploading` 条目就是封缄被中断的。 */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** 旧名：等同 [seal]。 */
    suspend fun send(peerUsername: String, media: List<PreparedMedia>): Result = seal(peerUsername, media)

    /**
     * 压缩后的明文 → 加密写 `.cca` → 封帧 → 落 `share_text`，**不发任何网络请求**。
     * [Result.Sealed] 时各项停在 `uploading`，并已交给 [uploadScheduler]。
     */
    suspend fun seal(peerUsername: String, media: List<PreparedMedia>): Result = withContext(io) {
        require(media.size in 1..MediaConstants.MAX_ITEMS) { "a media message carries 1..9 items" }
        val kind = media.first().kind
        require(media.all { it.kind == kind }) { "one kind per message" }
        require(kind == MediaConstants.KIND_IMAGE || media.size == 1) { "only images are batched" }

        val id = newId()
        // 认领要在任何 DB 写入之前：这样从条目/消息行第一次出现开始，recoverInterrupted
        // 就已经把它当成「正在跑」，不会把刚插入、还没来得及上传的条目误判成
        // 「被中断」而翻成 failed。
        claim(id)
        runClaimed(id) {
            try {
                media.forEachIndexed { i, p -> files.moveIn(p.file, files.bin(id, i)) }
            } catch (e: IOException) {
                files.deleteMessage(id)
                val failure = if (e is StorageFullException) MediaFailure.STORAGE_FULL else MediaFailure.NOT_READY
                return@runClaimed Result.Failed(id, failure)
            }
            // 消息行与它的媒体条目要么都在、要么都不在，两条写放进同一个事务。
            inTransaction {
                mediaDao.insertAll(
                    media.mapIndexed { i, p ->
                        MediaItem(
                            messageId = id,
                            index = i,
                            kind = p.kind,
                            durMs = p.durMs.coerceIn(0, MediaConstants.MAX_VOICE_MS),
                            width = p.width,
                            height = p.height,
                            byteLen = 0L,
                            blobSecret = ByteArray(0),
                            blobId = "",
                            state = MediaItem.STATE_ENCRYPTING,
                            localPath = files.bin(id, i).absolutePath,
                        )
                    },
                )
                dao.insert(
                    ChatMessage(
                        id = id,
                        peerUsername = peerUsername,
                        direction = ChatMessage.DIRECTION_OUT,
                        body = "",
                        timestamp = now(),
                        kind = ChatMessage.kindForMedia(kind),
                    ),
                )
            }
            sealClaimed(peerUsername, id)
        }.also(::handOff)
    }

    /**
     * 未分享（加密/封帧阶段失败）→ 重走 seal：已有 `.cca` 的项复用，缺的重新加密；成功后交给 [uploadScheduler]。
     * 已分享 → 绝不重新加密、不再封帧（不多占棘轮步）、不动分享文本，返回 [Result.Rescheduled]：
     * 还没上传、`.cca` 还在、没有永久失败原因的项回到 `uploading`（6 小时窗口重新起算），交给 [uploadNow]；
     * `.cca` 已不在的项标「文件丢失」，不交（不先闪一下「上传中」）；已记永久失败原因的项是终态，不碰（spec §1.3）。
     */
    suspend fun retry(messageId: String): Result = withContext(io) {
        // 先认领再看分享文本：否则另一个 retry 恰好在「读分享文本」与「认领」之间封完帧，
        // 这里会对一条已分享的消息再封一次帧（多占一步棘轮、换掉 share_text）。
        if (!claim(messageId)) return@withContext Result.InProgress(messageId)
        var schedule = false
        val result = runClaimed(messageId) {
            val msg = dao.getById(messageId) ?: return@runClaimed Result.Failed(messageId, MediaFailure.DELETED)
            if (msg.shareText.isNullOrEmpty()) {
                sealClaimed(msg.peerUsername, messageId).also { schedule = it is Result.Sealed }
            } else {
                val items = mediaDao.forMessage(messageId)
                if (items.isEmpty()) return@runClaimed Result.Failed(messageId, MediaFailure.NOT_READY)
                val since = now()
                for (item in items) {
                    if (item.state == MediaItem.STATE_SEALED) continue
                    // 永久失败（太大 / 被拒 / 文件丢失）是终态（spec §1.3）：手动重试也不再碰它。
                    if (item.state == MediaItem.STATE_FAILED && item.uploadFailure != null) continue
                    if (item.blobId.isNotEmpty() && files.cca(messageId, item.index).exists()) {
                        mediaDao.restartUpload(messageId, item.index, since)
                        schedule = true
                    } else {
                        mediaDao.failPermanently(messageId, item.index, MediaFailure.FILE_MISSING.name)
                    }
                }
                Result.Rescheduled(messageId)
            }
        }
        // 与 handOff 同理：释放认领之后再交给上传引擎。
        when {
            !schedule -> Unit
            result is Result.Rescheduled -> uploadNow(messageId)
            else -> uploadScheduler(messageId)
        }
        result
    }

    /**
     * 幂等上传这条消息里所有未 `sealed` 的项。每项每次都重新签名（URL 只活 300 s），PUT 同一份
     * `.cca`；成功一项即先落库 `sealed` 再删它的 `.cca`。**永不加密。**
     * 同一条消息正在 seal/upload 时先等它放手再做（届时已上传的项会被跳过，不会重复 PUT）。
     */
    suspend fun upload(messageId: String): UploadOutcome = withContext(io) {
        while (!claim(messageId)) _busy.first { messageId !in it }
        runClaimed(messageId) { uploadClaimed(messageId) }
    }

    /**
     * 上传引擎最终放弃时调：把还没上传的项标 `failed`（红 `!`）；消息已删则什么都不做。
     * 不认领、可与上传并发：一条原子 UPDATE，永远不会把已 `sealed` 的项降级。
     */
    suspend fun markUploadFailed(messageId: String) = withContext(io) {
        markUnsealedFailed(messageId)
    }

    /**
     * 这条消息从何时起等着上传（分享文本落库 / 最近一次手动重试）；消息已删 → null。
     * 上传引擎按它算「等了多久」（6 小时上限）。v4 之前的老行没有这一列 → 回落到消息时间。
     */
    suspend fun uploadSince(messageId: String): Long? = withContext(io) {
        val msg = dao.getById(messageId) ?: return@withContext null
        mediaDao.uploadSince(messageId) ?: msg.timestamp
    }

    /** 这一项的 `.cca`（待上传的密文）还在不在——「对方还看不到」与「文件丢失」靠它分。 */
    fun ccaExists(messageId: String, index: Int): Boolean = files.cca(messageId, index).exists()

    /** 这条消息已经有分享文本（= 可能已经分享出去了，密文冻结）。 */
    suspend fun isShared(messageId: String): Boolean = withContext(io) {
        !dao.getById(messageId)?.shareText.isNullOrEmpty()
    }

    /** 封好了就交给上传引擎——必须在释放 [busy] 之后，否则引擎马上来认领会扑空。 */
    private fun handOff(result: Result) {
        if (result is Result.Sealed) uploadScheduler(result.messageId)
    }

    /**
     * [indices] = `null` 转发整条消息的全部条目；否则只转发这些下标的条目（去重、升序），
     * 空列表、或有任何下标不在该消息里，都抛 [IllegalArgumentException]（调用方 bug，不是运行时失败）。
     */
    suspend fun forward(messageId: String, toPeer: String, indices: List<Int>?): Result = withContext(io) {
        val all = mediaDao.forMessage(messageId)
        val items = if (indices == null) {
            all
        } else {
            val wanted = indices.distinct().sorted()
            require(wanted.isNotEmpty()) { "forward indices empty" }
            val known = all.map { it.index }.toSet()
            require(wanted.all { it in known }) { "forward indices out of range" }
            all.filter { it.index in wanted }.sortedBy { it.index }
        }
        val bins = items.map { files.bin(messageId, it.index) }
        if (items.isEmpty() || bins.any { !it.exists() }) {
            return@withContext Result.Failed(messageId, MediaFailure.NOT_READY)
        }
        val staged = mutableListOf<PreparedMedia>()
        try {
            items.zip(bins).forEach { (item, bin) ->
                staged += PreparedMedia(item.kind, files.stageCopy(bin), item.durMs, item.width, item.height)
            }
            send(toPeer, staged)
        } catch (e: IOException) {
            val failure = if (e is StorageFullException) MediaFailure.STORAGE_FULL else MediaFailure.NOT_READY
            Result.Failed(messageId, failure)
        } finally {
            // 无论成功还是半途而废，没被 send() 挪走的暂存文件都不能留在磁盘上。
            staged.forEach { it.file.delete() }
        }
    }

    /**
     * 进线程时调：只收拾**封缄阶段**被中断的条目——不在 [busy] 里、`encrypting`，或 `uploading` 但消息还没有
     * 分享文本（加密完、封帧前被杀）——改成 `failed`（红 `!`，点了走 [retry] 重走 seal）。
     * 已分享消息的 `uploading` 项归上传引擎（WorkManager 跨进程续传）管，这里**绝不**碰它们。
     */
    suspend fun recoverInterrupted(peerUsername: String) = withContext(io) {
        mediaDao.failInterruptedSeals(peerUsername, busy = _busy.value.toList())
    }

    /**
     * 自愈（App 启动 / 回前台）要重新交给上传引擎的消息：已分享、还有 `uploading` 项或可重试的 `failed` 项
     * （`upload_failure` 为空）的发出消息。永久失败的项是终态，不在内。
     */
    suspend fun pendingUploadIds(): List<String> = withContext(io) {
        mediaDao.sharedOutgoingWithUnuploadedItems()
    }

    /** 认领不到（已经在 [busy] 里）就返回 false，调用方不得再动这条消息。 */
    private fun claim(messageId: String): Boolean {
        var claimed = false
        _busy.update { current ->
            if (messageId in current) {
                current
            } else {
                claimed = true
                current + messageId
            }
        }
        return claimed
    }

    /** 调用方已经认领了 [messageId]：负责跑 [block]，兜底标 failed，结束时释放 [busy]。 */
    private suspend fun <T> runClaimed(messageId: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 任何没被下面显式分支处理的异常（典型：DAO 写失败）：不能悄悄吞掉当成没事
            // 发生——先把这条消息里还没 sealed 的条目标 failed（不留转圈），再原样抛出，
            // 让调用方知道出了真问题。标记本身再失败就挂成 suppressed，不顶替原始异常。
            try {
                markUnsealedFailed(messageId)
            } catch (marking: Exception) {
                e.addSuppressed(marking)
            }
            throw e
        } finally {
            _busy.update { it - messageId }
        }
    }

    /**
     * 加密全部未上传项 → 封帧 → 落分享文本。零网络。
     * 途中消息被删（[MediaFailure.DELETED]）：删除路径已经删过目录，但这里之后写的 `.cca` 会把目录重建出来
     * （[MediaFiles.write] 会 mkdirs）——结束时再删一次，不留一份没有任何行能触发清理的孤儿密文（M7）。
     */
    private suspend fun sealClaimed(peerUsername: String, messageId: String): Result {
        val result = sealClaimedInner(peerUsername, messageId)
        if (result is Result.Failed && result.failure == MediaFailure.DELETED) {
            // 尽力而为：删不干净也只能如此——消息已不在，报 DELETED 才是对的，不能因此让 seal 抛出。
            try {
                files.deleteMessage(messageId)
            } catch (_: IOException) {
            }
        }
        return result
    }

    private suspend fun sealClaimedInner(peerUsername: String, messageId: String): Result {
        for (item in mediaDao.forMessage(messageId)) {
            // 旧版流程里「先上传后封帧」留下的已上传项：secret 还在库里，直接进帧。
            if (item.state == MediaItem.STATE_SEALED) continue
            val encrypted = try {
                ensureEncrypted(item)
            } catch (e: MediaFailureException) {
                return fail(messageId, e.failure)
            } catch (e: IOException) {
                // `.bin`/`.cca` 读写失败（含：消息在加密途中被删，目录已不在）
                return fail(messageId, MediaFailure.NOT_READY)
            }
            // 复用已有 `.cca` 的项（上次失败标了 failed）也回到「等待上传」。
            if (encrypted.state != MediaItem.STATE_UPLOADING) {
                mediaDao.updateState(messageId, item.index, MediaItem.STATE_UPLOADING)
            }
        }
        // 加密途中用户可能「删除」了这条消息：行不在就安静结束，不复活它。
        if (dao.getById(messageId) == null) return Result.Failed(messageId, MediaFailure.DELETED)
        val items = mediaDao.forMessage(messageId)
        if (items.isEmpty() || items.any { it.blobId.isEmpty() }) {
            return fail(messageId, MediaFailure.NOT_READY)
        }
        val refs = items.map {
            MediaRef(
                kind = it.kind.toUByte(),
                durMs = it.durMs.coerceIn(0, MediaConstants.MAX_VOICE_MS).toUShort(),
                width = it.width.toUShort(),
                height = it.height.toUShort(),
                byteLen = it.byteLen.toUInt(),
                blobSecret = it.blobSecret,
            )
        }
        val wire = try {
            sealFrame(peerUsername, refs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没封成帧就没有分享文本：标 failed（红 `!`），retry 会重走 seal。
            return fail(messageId, MediaFailure.SESSION_LOST)
        }
        val first = items.first()
        val share = MediaShareText.compose(shareHeaders.media(first.kind, items.size, first.blobId), wire)
        dao.updateShareText(messageId, share)
        // 此刻起「等着上传」：上传引擎的 6 小时窗口从这里算（不是消息行创建时——那可能是几小时前一次失败的封缄）。
        mediaDao.startUploadWindow(messageId, now())
        // 封帧途中被删：updateShareText 什么都没写，不能报 Sealed（那会弹分享面板、交给上传引擎）。
        if (dao.getById(messageId) == null) return Result.Failed(messageId, MediaFailure.DELETED)
        val summary = MessagePreview.ofKind(ChatMessage.kindForMedia(first.kind), items.size)
        return Result.Sealed(messageId, share, summary)
    }

    /** 把这条消息里所有没 `sealed` 的条目标 `failed`；消息已被删掉时什么都不做。 */
    private suspend fun markUnsealedFailed(messageId: String) {
        if (dao.getById(messageId) == null) return
        mediaDao.failUnsealed(messageId)
    }

    /**
     * 一条失败 → 整条消息里所有没 `sealed` 的条目都标 `failed`（多图时别的气泡不会一直转圈）。
     * 消息已被删掉时什么都不写，返回 [MediaFailure.DELETED]。
     */
    private suspend fun fail(messageId: String, failure: MediaFailure): Result {
        if (dao.getById(messageId) == null) return Result.Failed(messageId, MediaFailure.DELETED)
        markUnsealedFailed(messageId)
        return Result.Failed(messageId, failure)
    }

    /**
     * 逐项独立（接收端「各项独立，先到的先显示」）：某一项 `.cca` 丢了、413 或被拒（其余 4xx），只把**这一项**标
     * `failed`、记下第一个永久失败，其余项照传；全部走完再报 `Permanent(第一个)`。
     * 可重试失败（网络/5xx/429/读文件偶发错）立刻返回——后面的项反正也要等下一次尝试。
     */
    private suspend fun uploadClaimed(messageId: String): UploadOutcome {
        val msg = dao.getById(messageId) ?: return UploadOutcome.Permanent(MediaFailure.DELETED)
        // 没有分享文本就没有要兑现的承诺：交回 retry（重走 seal），这里不传。
        if (msg.shareText.isNullOrEmpty()) {
            markUnsealedFailed(messageId)
            return UploadOutcome.Permanent(MediaFailure.NOT_READY)
        }
        val unsealed = mediaDao.forMessage(messageId).filter { it.state != MediaItem.STATE_SEALED }
        // 已永久失败（记了原因）的项是终态，不再试（spec §1.3）。
        val (given, pending) = unsealed.partition { it.state == MediaItem.STATE_FAILED && it.uploadFailure != null }
        // 重试（之前标了 failed）时先整体回到「上传中」，多图时每个气泡都转圈。
        pending.filter { it.state != MediaItem.STATE_UPLOADING }
            .forEach { mediaDao.updateState(messageId, it.index, MediaItem.STATE_UPLOADING) }
        var firstPermanent: MediaFailure? = given.firstOrNull()?.let { item ->
            MediaFailure.entries.firstOrNull { it.name == item.uploadFailure } ?: MediaFailure.REJECTED
        }
        for (item in pending) {
            val cca = files.cca(messageId, item.index)
            val failure = if (item.blobId.isEmpty() || !cca.exists()) {
                MediaFailure.FILE_MISSING
            } else {
                try {
                    uploadItem(item, cca)
                    null
                } catch (e: MediaFailureException) {
                    if (dao.getById(messageId) == null) return UploadOutcome.Permanent(MediaFailure.DELETED)
                    if (e.failure !in PERMANENT_UPLOAD_FAILURES) return UploadOutcome.Retryable(e.failure)
                    e.failure
                } catch (e: IOException) {
                    // 读 `.cca` 失败：消息被删（目录已不在）/ 文件刚好没了 / 文件还在但偶发 I/O 错。
                    if (dao.getById(messageId) == null) return UploadOutcome.Permanent(MediaFailure.DELETED)
                    if (cca.exists()) return UploadOutcome.Retryable(MediaFailure.READ_FAILED)
                    MediaFailure.FILE_MISSING
                }
            }
            if (failure != null) {
                if (dao.getById(messageId) == null) return UploadOutcome.Permanent(MediaFailure.DELETED)
                // 持有认领、这一项还没 sealed：直接标它（连同原因，自愈据此跳过），不牵连同一条消息里的其它项。
                mediaDao.failPermanently(messageId, item.index, failure.name)
                if (firstPermanent == null) firstPermanent = failure
            }
        }
        // 上传途中用户可能「删除」了这条消息：什么都不写。
        if (dao.getById(messageId) == null) return UploadOutcome.Permanent(MediaFailure.DELETED)
        return firstPermanent?.let { UploadOutcome.Permanent(it) } ?: UploadOutcome.Done
    }

    /**
     * 已有 `.cca` 就直接用（重试路径）；没有才加密——正常情况下每个条目只加密一次。
     * 已分享的消息**永不**走到加密：缺 `.cca` 直接报 [MediaFailure.FILE_MISSING]。
     */
    private suspend fun ensureEncrypted(item: MediaItem): MediaItem {
        val cca = files.cca(item.messageId, item.index)
        if (item.blobId.isNotEmpty() && cca.exists()) return item
        if (isShared(item.messageId)) throw MediaFailureException(MediaFailure.FILE_MISSING)
        // 有 blob_id/secret 但 `.cca` 不在了（比如缓存被清）：不能复用旧 secret（nonce 安全），
        // 从明文 `.bin` 重新加密，落一份全新的 secret/blob_id。
        val plain = files.bin(item.messageId, item.index).readBytes()
        val sealed = try {
            crypto.encrypt(plain, item.kind)
        } catch (e: uniffi.chencang.ChencangException) {
            throw MediaFailureException(MediaFailure.TOO_LARGE, e)
        }
        val byteLen = sealed.blob.size.toLong()
        // 先落库新 secret/blob_id/byte_len，再写 `.cca`：崩在中间最多留一条「有 secret
        // 没 .cca」的记录，下次会安全地用一份新 secret 重新加密（ensureEncrypted 的判定
        // 走的正是这条分支）。反过来（先写文件再落库）会让新密文配着旧 blob_id/secret
        // 卡住——旧 secret 解不开新密文，对端永远读不出来（复审 Important）。
        mediaDao.updateSealedBlob(item.messageId, item.index, sealed.blobSecret, sealed.blobId, byteLen, MediaItem.STATE_UPLOADING)
        try {
            files.write(cca, sealed.blob)
        } catch (e: StorageFullException) {
            throw MediaFailureException(MediaFailure.STORAGE_FULL, e)
        }
        return item.copy(
            blobSecret = sealed.blobSecret,
            blobId = sealed.blobId,
            byteLen = byteLen,
            state = MediaItem.STATE_UPLOADING,
        )
    }

    private suspend fun uploadItem(item: MediaItem, cca: File) {
        val key = MediaConstants.progressKey(item.messageId, item.index)
        val bytes = cca.readBytes()
        try {
            val url = transport.requestUploadUrl(item.blobId, bytes.size, item.kind)
            transport.upload(url, bytes) { p -> _progress.update { it + (key to p.toFloat()) } }
        } finally {
            _progress.update { it - key }
        }
        // 先落库 sealed，再删 `.cca`：崩在两步之间最多留一个孤儿密文文件，绝不会出现
        // 状态还没落但文件已经没了（那样重试就找不到密文可传了）。
        mediaDao.updateState(item.messageId, item.index, MediaItem.STATE_SEALED)
        cca.delete()
    }

    private companion object {
        /** 中转明确拒收、重试也不会变的失败；其余 [MediaFailureException] 都可重试。 */
        val PERMANENT_UPLOAD_FAILURES = setOf(MediaFailure.TOO_LARGE, MediaFailure.REJECTED)
    }
}
