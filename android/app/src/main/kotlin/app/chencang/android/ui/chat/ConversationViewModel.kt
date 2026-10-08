package app.chencang.android.ui.chat

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chencang.shared.AppPrefs
import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.media.AwaitingPoller
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.MediaDownloader
import app.chencang.shared.media.MediaFailure
import app.chencang.shared.media.MediaLimits
import app.chencang.shared.media.MediaRejected
import app.chencang.shared.media.MediaSender
import app.chencang.shared.media.OutgoingMediaStatus
import app.chencang.shared.media.PreparedMedia
import app.chencang.shared.chat.Handoff
import app.chencang.shared.chat.MessagePreview
import app.chencang.shared.i18n.UiText
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeHandler
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.intake.IntakeOutcome
import app.chencang.shared.model.Contact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One conversation thread with [peerUsername]. 文字：[seal] 加密落库并给出加密卡（零网络），
 * 用户上次选的是「分享」时立刻排队弹分享面板。媒体：预处理 → [MediaSender] 加密封帧（上传另交上传引擎）
 * → 通过 [shareRequests] 自动弹分享面板（R1 两行）；[keepMediaFresh] 在线程可见期间恢复中断条目并自动下载语音与图片。
 *
 * 状态只说用户做过的事（[Handoff]）：点「复制」→ `copied`；分享面板报告**选定了目标 App**（系统经
 * `ShareCompletionReceiver` 回调，直接落库）→ `sent`（已分享）。本 VM 从不在点「分享」时标。
 * 界面靠 [watchHandOffs] 观察「非 sent → sent」收卡、提示「已分享」。分享面板一次只弹一个：
 * [shareRequests] 串行出队，回到前台（[onResumed]）才弹下一个。
 *
 * 输入框的每次变化走 [onDraftChanged]：粘贴进来的加密消息 / 配对码不进草稿，按 [IntakeHandler] 分拣
 * 并通过 [navigation] 交给界面跳转。
 */
class ConversationViewModel(
    private val repo: ChatRepository,
    private val peerUsername: String,
    contacts: Flow<List<Contact>>,
    private val sender: MediaSender,
    private val downloader: MediaDownloader,
    private val preparer: MediaPreparer,
    private val prefs: AppPrefs,
    private val intake: IntakeHandler,
    /**
     * 「等待对方上传」轮询窗口用的时钟（测试注入虚拟时间）。单调时钟、与主线程 `delay` 同一基准：
     * 墙钟会被自动校时 / 用户改时间拨动，往回拨会把 30 分钟窗口拉长（UAT O4）。
     */
    pollClock: () -> Long = SystemClock::uptimeMillis,
    /** 粘贴进来的加密消息在这里解密落库（测试注入测试调度器）。 */
    private val intakeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = repo.observeThread(peerUsername)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<ThreadRow>> =
        combine(repo.observeThread(peerUsername), repo.observeThreadMedia(peerUsername)) { m, i ->
            ThreadRows.build(m, i, sender::ccaExists)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** progress key → 0..1（上传与下载合并）。 */
    val progress: StateFlow<Map<String, Float>> =
        combine(sender.progress, downloader.progress) { a, b -> a + b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 正在处理的消息 id（发送）与 progress key（下载）；不在里面的 failed 才显示红 `!`。 */
    val busy: StateFlow<Set<String>> =
        combine(sender.busy, downloader.busy) { a, b -> a + b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The paired [Contact] for [peerUsername] — drives the thread's top-bar name/avatar/trust badge. */
    val contact: StateFlow<Contact?> = contacts
        .map { list -> list.firstOrNull { it.username == peerUsername } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 对话页顶部横幅（至多一条，见 [ThreadBanners.pick]）；关闭记录按「联系人 + 类型」持久化在 [AppPrefs]。 */
    // 直接取线程流（冷流，首个值就是真实历史），不用以空表起步的 [messages]：否则已有消息的老线程会先闪一下错误横幅。
    val banner: StateFlow<ThreadBanner?> =
        combine(contact, repo.observeThread(peerUsername), prefs.threadBannerDismissed) { c, msgs, dismissed ->
            ThreadBanners.pick(
                contact = c,
                hasIncoming = msgs.any { it.direction == ChatMessage.DIRECTION_IN },
                hasOutgoing = msgs.any { it.direction == ChatMessage.DIRECTION_OUT },
                dismissed = dismissed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissBanner(banner: ThreadBanner) {
        val c = contact.value ?: return
        prefs.dismissThreadBanner(ThreadBanners.dismissKey(c, banner))
    }

    /** 转发的候选联系人（排除当前线程）。 */
    val otherContacts: StateFlow<List<Contact>> = contacts
        .map { list -> list.filter { it.username != peerUsername } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 输入框里粘贴的内容要带用户去别处：别的会话（加密消息）或添加联系人（配对码）。 */
    sealed interface ThreadNav {
        data class OpenThread(val peerUsername: String) : ThreadNav
        data class OpenWizard(val wire: String) : ThreadNav
    }

    private val navEvents = Channel<ThreadNav>(Channel.UNLIMITED)
    val navigation: Flow<ThreadNav> = navEvents.receiveAsFlow()

    /**
     * 一次性的用户提示（Toast）：加密失败、选择时被拦、429、存储不足、转发成功等。
     * 屏幕显示后调 [consumeSendError] 清掉。
     */
    val sendError = MutableStateFlow<UiText?>(null)

    /**
     * 输入区上方的加密卡：文字与媒体共用，是「本线程最近一条待交出的消息」，新加密的顶掉旧的
     * （旧的仍在列表里，可从气泡长按或点状态行再交出）。[summary] 只在本机卡片上显示，不进分享文本、日志或提示。
     * 卡片第二行（发给谁 / 还没发）由界面按 [phase] 与联系人名拼。
     */
    data class SealCard(
        val messageId: String,
        val peer: String,
        /** 交出去的文本（分享与复制同一份）：文字 = 首行 + wire，媒体 = R1 两行。 */
        val shareText: String,
        val summary: UiText,
        val phase: Phase,
    ) {
        /** READY = 刚加密还没动；SHARING = 面板已弹出/在排队；NOT_SENT = 面板回来了但没选目标 App。 */
        enum class Phase { READY, SHARING, NOT_SENT }

        companion object {
            const val SUMMARY_LIMIT = 24

            /** 首个非空行、去首尾空白，超过 [SUMMARY_LIMIT] 截断加「…」。 */
            fun textSummary(text: String): String {
                val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                return if (line.length > SUMMARY_LIMIT) line.take(SUMMARY_LIMIT) + "…" else line
            }
        }
    }

    /** 一次分享面板请求。[peer] 是消息所在会话——转发时是目标会话，不一定是本线程。 */
    data class ShareRequest(val messageId: String, val peer: String, val text: String)

    private val _card = MutableStateFlow<SealCard?>(null)
    val card: StateFlow<SealCard?> = _card.asStateFlow()

    /** 卡片上谁是主按钮：用户上次选的动作（持久化）。 */
    val sealAction: StateFlow<AppPrefs.SealAction> = prefs.sealAction

    private val launches = Channel<ShareRequest>(Channel.UNLIMITED)

    /**
     * 要弹的分享面板，一次一个：界面收到就 `createChooser(..., ShareCompletionReceiver 回调)`。
     * 只弹面板、不标已送出；弹不了（不在前台）调 [shareDeferred]，没有 App 可接调 [shareUnavailable]。
     */
    val shareRequests: Flow<ShareRequest> = launches.receiveAsFlow()

    private val shareQueue = ArrayDeque<ShareRequest>()
    /** 已交给界面、面板可能正在台上的那一次。 */
    private var presenting: ShareRequest? = null
    /** 看门狗放开的那次：之后若还是来了 pause→resume，照样按它核对「未发出」。 */
    private var releasedByWatchdog: ShareRequest? = null
    private var watchdog: Job? = null
    /** 界面在前台（ON_RESUME 之后、ON_PAUSE 之前）才弹面板——后台 startActivity 会被系统静默拦掉。 */
    private var foreground = true
    val voicePlayer = VoicePlayer()

    /**
     * 收到的媒体「等待对方上传」的轮询（先分享、后上传 spec §2）：只在本线程可见（[onThreadVisible] 之后、
     * [onThreadHidden] 之前）时跑。每次再试走 [MediaDownloader.download]（超 24 h 直接判已过期、不发请求）；
     * 请求跑在 viewModelScope 上，离开线程不打断在途的下载。
     */
    private val poller = AwaitingPoller(
        clock = pollClock,
        scope = viewModelScope,
        tryFetch = { messageId, index -> safeguard { downloader.download(messageId, index) } },
        isAwaiting = { messageId, index -> AwaitingPoller.keepsPolling(repo.mediaItem(messageId, index)?.state) },
    )

    /** 窗口已过的等待项（气泡改「还没收到文件 · 点击重试」）。 */
    val awaitingExpired: StateFlow<Set<AwaitingPoller.Key>> = poller.expired

    /** 线程屏在前台（ON_RESUME 之后、ON_PAUSE 之前）。 */
    private var threadVisible = false

    /**
     * ON_RESUME（进线程 / 回前台）：可见的等待项（含轮询那次已切到下载中的）立即再试一次并重开 30 分钟窗口。
     */
    fun onThreadVisible() {
        threadVisible = true
        viewModelScope.launch {
            val targets = try {
                AwaitingPoller.targets(repo.observeThread(peerUsername).first(), repo.observeThreadMedia(peerUsername).first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "reading awaiting items failed: ${e.javaClass.simpleName}")
                return@launch
            }
            // 查库这一下之间又离开了前台：不开轮询。
            if (threadVisible) poller.onVisible(targets)
        }
    }

    /** ON_PAUSE（切后台 / 面板盖上来 / 离开线程）：停止全部轮询；在途的那次请求照常跑完。 */
    fun onThreadHidden() {
        threadVisible = false
        poller.onHidden()
    }

    /** 点「等待对方上传」/「还没收到文件 · 点击重试」的气泡：这一项立即再试并重开 30 分钟窗口。 */
    fun retryAwaiting(messageId: String, index: Int) {
        poller.onVisible(listOf(messageId to index))
    }

    /**
     * 线程在屏幕上时由界面 `LaunchedEffect` 调用（离开即取消）：先把超 24 h 的收件条目标过期，
     * 再把封缄/下载被中断的条目恢复成可重试状态（已分享消息的上传归 WorkManager，不碰），然后持续盯着新到的 `pending` 语音/图片并自动下载（spec §3.6），
     * 并把刚进入「等待对方上传」的项交给轮询（[trackNewlyAwaiting]）。
     * 不放进 `init`：那样收集会一直挂在 viewModelScope 上，比数据库活得还久。
     */
    suspend fun keepMediaFresh() {
        refreshMediaStates()
        coroutineScope {
            launch { trackNewlyAwaiting() }
            repo.observeThreadMedia(peerUsername)
                .map { items ->
                    items.filter {
                        it.state == MediaItem.STATE_PENDING &&
                            (it.kind == MediaConstants.KIND_VOICE || it.kind == MediaConstants.KIND_IMAGE)
                    }.map { MediaConstants.progressKey(it.messageId, it.index) }.toSet()
                }
                .distinctUntilChanged()
                .collect { pending ->
                    if (pending.isNotEmpty()) launch { safeguard { downloader.autoDownload(peerUsername) } }
                }
        }
    }

    /**
     * 刚进入「等待对方上传」的收件项（自动下载 / 点视频 / 点重试后得 403）从现在起按间隔轮询，不立即再试
     * （[AwaitingPoller.track]：已在轮询、窗口已过、线程不可见时都不动）。由 [keepMediaFresh] 一并收集。
     */
    private suspend fun trackNewlyAwaiting() {
        combine(repo.observeThread(peerUsername), repo.observeThreadMedia(peerUsername)) { messages, items ->
            val incoming = messages.filter { it.direction == ChatMessage.DIRECTION_IN }.map { it.id }.toSet()
            items.filter { it.messageId in incoming && it.state == MediaItem.STATE_AWAITING }
                .map { it.messageId to it.index }
        }
            .distinctUntilChanged()
            .collect { awaiting -> if (awaiting.isNotEmpty()) poller.track(awaiting) }
    }

    /**
     * 回到前台（ON_RESUME）时由界面调用：只重跑「超期 → 已过期」与「中断条目恢复」这一遍，
     * **不**重启 [keepMediaFresh] 的收集器——重启会取消正在跑的自动下载（终审 I2）。
     * 线程开着超过 24 h 后台再切回来，过期的媒体要立刻显示「已过期」（UAT F2）。
     */
    suspend fun refreshMediaStates() {
        safeguard { downloader.expireStale(peerUsername) } // 超 24 h 的先标「已过期」（包括没点开的视频），不发请求
        safeguard { sender.recoverInterrupted(peerUsername) }
        safeguard { downloader.recoverInterrupted(peerUsername) }
    }

    /**
     * 一步失败（意外 DAO 异常）不该带崩调用方的 `LaunchedEffect` 协程作用域——记日志、继续下一步；
     * 取消（协程被取消/线程离开屏幕）不算「失败」，照常向上抛，不能被吞掉。
     */
    private suspend fun safeguard(step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "media state refresh failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 加密当前草稿：落库 + 出卡片；用户上次选的是「分享」就立刻排队弹分享面板（同 [shareCard]），
     * 是「复制」就只出卡片。空草稿 no-op。失败走 [sendError]，草稿保留。
     */
    fun seal() {
        val text = _draft.value
        if (text.isBlank()) return
        launchGuarded("seal", SEAL_FAILED) {
            val sealed = repo.sendTextSealed(peerUsername, text)
            _draft.value = ""
            val card = SealCard(
                messageId = sealed.message.id,
                peer = peerUsername,
                shareText = repo.textShareText(sealed.wire),
                summary = UiText.Raw(SealCard.textSummary(text)),
                phase = SealCard.Phase.READY,
            )
            _card.value = card
            if (prefs.sealAction.value == AppPrefs.SealAction.SHARE) {
                request(ShareRequest(card.messageId, card.peer, card.shareText))
            }
        }
    }

    /**
     * 输入框内容变化。新文本认得出（加密消息 / 配对码）而旧草稿认不出 = 用户刚粘贴进来：不写进草稿，
     * 加密消息解密落库后去它的会话（本会话的就留在这里，消息自己会出现），配对码去添加联系人。
     * 其余情况照常写草稿（包括只打了一个 🔒）。
     */
    fun onDraftChanged(text: String) {
        val kind = intake.classify(text)
        if (!kind.recognized || intake.classify(_draft.value).recognized) {
            _draft.value = text
            return
        }
        when (kind) {
            is IntakeKind.PairingInvite -> navEvents.trySend(ThreadNav.OpenWizard(kind.wire))
            is IntakeKind.PairingResponse -> navEvents.trySend(ThreadNav.OpenWizard(kind.wire))
            is IntakeKind.Message -> handlePastedMessage(text)
            IntakeKind.Incomplete, IntakeKind.NotOurs, IntakeKind.LinkOnly -> Unit // recognized 已排除
        }
    }

    private fun handlePastedMessage(text: String) {
        viewModelScope.launch {
            val outcome = try {
                withContext(intakeDispatcher) { intake.handle(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pasted message intake failed: ${e.javaClass.simpleName}")
                IntakeOutcome.Failed(IntakeFailure.SAVE_FAILED)
            }
            when (outcome) {
                is IntakeOutcome.OpenThread -> openThreadUnlessHere(outcome.peerUsername)
                is IntakeOutcome.AlreadyInThread -> openThreadUnlessHere(outcome.peerUsername)
                is IntakeOutcome.Pairing -> navEvents.trySend(ThreadNav.OpenWizard(outcome.wire))
                is IntakeOutcome.Failed -> sendError.value = UiText.Res(outcome.failure.messageRes)
            }
        }
    }

    private fun openThreadUnlessHere(peer: String) {
        if (peer != peerUsername) navEvents.trySend(ThreadNav.OpenThread(peer))
    }

    /** 相册多选 / 拍照：图片合成一条（≤ 9 张），每个视频各一条。 */
    fun sendPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchGuarded("send picked media", SEND_FAILED) {
            try {
                val (videos, images) = uris.partition { preparer.mimeOf(it)?.startsWith("video/") == true }
                if (images.isNotEmpty()) {
                    prepareAndSend(MediaLimits.IMAGE_UNREADABLE) {
                        images.take(MediaConstants.MAX_ITEMS).map { preparer.image(it) }
                    }
                }
                for (v in videos) prepareAndSend(MediaLimits.VIDEO_UNREADABLE) { listOf(preparer.video(v)) }
            } finally {
                preparer.discardCaptures()
            }
        }
    }

    fun sendCapturedVideo(uri: Uri) {
        launchGuarded("send captured video", SEND_FAILED) {
            try {
                prepareAndSend(MediaLimits.VIDEO_UNREADABLE) { listOf(preparer.video(uri)) }
            } finally {
                preparer.discardCaptures()
            }
        }
    }

    /**
     * 预处理 + 发送，任何失败都变成一句提示而不是崩溃：选择时被拦 → 拦下的原因；
     * 其它异常（读不出 Uri、解码器崩、IO）→ [unreadable]。
     */
    private suspend fun prepareAndSend(@StringRes unreadable: Int, prepare: suspend () -> List<PreparedMedia>) {
        try {
            deliver(sender.send(peerUsername, prepare()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaRejected) {
            sendError.value = UiText.Res(e.messageRes)
        } catch (e: Exception) {
            sendError.value = UiText.Res(unreadable)
        }
    }

    fun sendVoice(prepared: PreparedMedia): Job =
        launchGuarded("send voice", SEND_FAILED) { deliver(sender.send(peerUsername, listOf(prepared))) }

    /**
     * 红 `!`：没分享过 → 重走 seal（弹卡、弹面板）；已分享（「对方还看不到」）→ 只交给上传引擎「现在就试」，
     * **不**进 [deliver]：不弹卡、不弹分享面板、分享文本不变（spec §1.3）。
     */
    fun retry(messageId: String): Job =
        launchGuarded("retry send", SEND_FAILED) { retryNow(messageId) }

    private suspend fun retryNow(messageId: String) {
        when (val result = sender.retry(messageId)) {
            is MediaSender.Result.Rescheduled -> Unit
            else -> deliver(result)
        }
    }

    /**
     * 点发出媒体的红「!」或状态行：永久失败（太大 / 被拒 / 文件丢失）只提示状态行同一句话（终态，不重传、不重新加密）；
     * 其余走 [retry]。
     */
    fun onFailureTapped(row: ThreadRow): Job =
        launchGuarded("retry send", SEND_FAILED) {
            val status = row.outStatus
            if (status is OutgoingMediaStatus.PermanentlyFailed) {
                sendError.value = UiText.Res(status.reason.messageRes)
            } else {
                retryNow(row.message.id)
            }
        }

    /** 点击未下载的视频 / 下载失败的条目。 */
    fun download(messageId: String, index: Int): Job =
        launchGuarded("download", DOWNLOAD_FAILED) {
            val failure = downloader.download(messageId, index)
            if (failure == MediaFailure.STORAGE_FULL) sendError.value = UiText.Res(failure.messageRes)
        }

    /**
     * 转发给另一个联系人：成功后只排队分享面板（完成时标**目标会话**那条）+ 提示，**不**动本线程的卡片——
     * 那张卡可能是还没交出去的文字，顶掉会让人把 B 的密文贴进 A 的聊天（终审 M6）。
     * 转发出去的消息在对方线程里是「已加密 · 还没发」，可在那边再分享。
     * [indices] `null` = 整条消息的全部条目；否则只转发这些下标（相册里点某一张）。
     */
    fun forward(messageId: String, indices: List<Int>?, toPeer: String, toName: String): Job =
        launchGuarded("forward", SEND_FAILED) {
            when (val result = sender.forward(messageId, toPeer, indices)) {
                is MediaSender.Result.Sealed -> {
                    request(ShareRequest(result.messageId, toPeer, result.shareText))
                    sendError.value = UiText.Res(R.string.media_forwarded_to, listOf(toName))
                }
                else -> deliver(result)
            }
        }

    /**
     * 长按「删除」：仅本机。正在播放的语音若属于这条消息，先停掉——不然行没了、播放还在继续。
     * 加密卡正是这条就收起、它排队的面板撤掉（同 [dismissSealed]）——不然卡上「复制」「分享」
     * 还能把已删消息的分享文本交出去（UAT B1）。
     */
    fun delete(messageId: String): Job {
        voicePlayer.playing.value?.let { key -> if (key.startsWith("$messageId:")) voicePlayer.stop() }
        dropQueued(messageId)
        if (_card.value?.messageId == messageId) _card.value = null
        return launchGuarded("delete", DELETE_FAILED) { repo.deleteMessage(messageId) }
    }

    fun toggleVoice(row: ThreadRow) {
        val item = row.item ?: return
        val path = item.localPath ?: return
        voicePlayer.toggle(row.key, path)
        if (row.message.direction == ChatMessage.DIRECTION_IN && !item.played) {
            // 标记「已听」失败只影响红点，不值得打扰用户：只记日志。
            launchGuarded("mark played", userMessage = null) { repo.markPlayed(item.messageId, item.index) }
        }
    }

    // ---- 分享热桥 ----

    /** 卡片「分享」（或卡片上「还没发 · 点这里再发」）：只排队弹面板，不标记。 */
    fun shareCard() {
        val current = _card.value ?: return
        prefs.setSealAction(AppPrefs.SealAction.SHARE)
        request(ShareRequest(current.messageId, current.peer, current.shareText))
    }

    /**
     * 卡片「复制」：[write] 把文本写进剪贴板（界面给）；写成了才收卡、撤掉它排队的面板、标已复制，
     * 并返回写进去的文本。写失败 → 提示、什么都不标，返回 null。
     */
    fun copyCard(write: (String) -> Boolean = { true }): String? {
        val current = _card.value ?: return null
        if (!write(current.shareText)) {
            sendError.value = COPY_FAILED
            return null
        }
        prefs.setSealAction(AppPrefs.SealAction.COPY)
        handOffByCopy(current.messageId, current.peer)
        return current.shareText
    }

    /** 收起卡片（不标记）；它还在排队的面板一并撤掉。 */
    fun dismissSealed() {
        _card.value?.let { dropQueued(it.messageId) }
        _card.value = null
    }

    /** 自己发出的文字气泡长按「分享」：与卡片同一条队列、同一套完成才标的规则。无 wire 的老消息返回 false。 */
    fun shareMessage(message: ChatMessage): Boolean {
        val text = handOffText(message) ?: return false
        prefs.setSealAction(AppPrefs.SealAction.SHARE)
        request(ShareRequest(message.id, message.peerUsername, text))
        return true
    }

    /**
     * 长按「复制加密消息」：返回要写进剪贴板的文本。自己发出的文字 = 固定首行 + wire，
     * 其它 = 行上存的 [ChatMessage.shareText]（媒体 R1 两行 / 收到的 wire）。都提示「已复制，去聊天软件里粘贴」；
     * 自己发出的顺带标已复制（已分享过的不降级）。
     */
    fun copyMessage(message: ChatMessage, write: (String) -> Boolean = { true }): String? {
        val text = handOffText(message) ?: message.shareText ?: return null
        if (!write(text)) {
            sendError.value = COPY_FAILED
            return null
        }
        if (message.direction != ChatMessage.DIRECTION_OUT) {
            sendError.value = COPIED_TOAST // 收到的消息：只是复制，不改任何状态
            return text
        }
        prefs.setSealAction(AppPrefs.SealAction.COPY) // 自己发出的（文字与媒体）都算用户选了「复制」
        handOffByCopy(message.id, message.peerUsername)
        return text
    }

    /** 长按「复制原文」/ 对方气泡「复制」：把明文写进剪贴板，提示「已复制」，不改状态、不动卡片。 */
    fun copyOriginal(message: ChatMessage, write: (String) -> Boolean): String? {
        if (message.kind != ChatMessage.KIND_TEXT) return null
        if (!write(message.body)) {
            sendError.value = COPY_FAILED
            return null
        }
        sendError.value = UiText.Res(R.string.common_copied)
        return message.body
    }

    /**
     * 点状态行「已加密 · 还没发」/「已复制 · 去粘贴」：把这条自己发出、还没分享过的消息重新放回卡片
     * （文字 = 首行 + wire，媒体 = 行上的分享文本），不自动弹面板。已分享 / 收到的 / 别的会话的不理。
     */
    fun reopenCard(messageId: String): Job = launchGuarded("reopen card", userMessage = null) {
        val message = repo.message(messageId) ?: return@launchGuarded
        if (message.direction != ChatMessage.DIRECTION_OUT || message.peerUsername != peerUsername) return@launchGuarded
        if (Handoff.of(message.status) == Handoff.SHARED) return@launchGuarded
        val stored = message.shareText?.takeIf { it.isNotEmpty() } ?: return@launchGuarded
        val (shareText, summary) = if (message.kind == ChatMessage.KIND_TEXT) {
            repo.textShareText(stored) to UiText.Raw(SealCard.textSummary(message.body))
        } else {
            val items = repo.observeThreadMedia(peerUsername).first().count { it.messageId == messageId }
            stored to MessagePreview.of(message, items.coerceAtLeast(1))
        }
        _card.value = SealCard(messageId, peerUsername, shareText, summary, SealCard.Phase.READY)
    }

    /**
     * 每次 ON_RESUME（含观察者刚挂上时补发的那次）。若之前确实离开过前台（[onPaused]），之前弹出的
     * 面板算是收起了——没选目标 App 的卡片转「未发出」；再弹队列里的下一个。没离开过前台（比如从
     * 验证页导航回来）则不动正在弹的那个：它可能还在通道里等界面去弹，不能当成已收起，否则会叠两个面板。
     */
    fun onResumed() {
        if (!foreground) {
            (presenting ?: releasedByWatchdog)?.let { done ->
                presenting = null
                settleAfterGrace(done.messageId)
            }
            releasedByWatchdog = null
        }
        foreground = true
        presentNext() // 下一个立刻弹，不等宽限
    }

    /**
     * 面板回来后先不急着说「还没发」：选定目标 App 的系统回调要经广播 + Room 写 + 失效通知才到，
     * 用户秒回时可能还没到。等 [NOT_SENT_GRACE_MS] 后按库里的状态定：仍是 `sealed` → 卡片转「还没发 · 点这里再发」；
     * `copied`（用户已经复制过）或 `sent` → 收卡（[watchHandOffs] 没在收集时也不会留一张过时的卡）。
     * 宽限期内卡片保持 SHARING。这期间用户又点了分享（同一条又在台上）就不动它。
     */
    private fun settleAfterGrace(messageId: String) {
        launchGuarded("settle share result", userMessage = null) {
            delay(NOT_SENT_GRACE_MS)
            if (presenting?.messageId == messageId) return@launchGuarded
            when (repo.message(messageId)?.status) {
                ChatMessage.STATUS_SEALED -> markNotSent(messageId)
                ChatMessage.STATUS_COPIED, ChatMessage.STATUS_SENT -> {
                    if (_card.value?.messageId == messageId) _card.value = null
                    dropQueued(messageId)
                }
                else -> Unit // 消息被删了：卡片随它去
            }
        }
    }

    /**
     * 界面 `startActivity` 成功后调。正常情况下面板一盖上来就是 ON_PAUSE（[onPaused] 取消看门狗）；
     * 若 [PAUSE_WATCHDOG_MS] 内都没 ON_PAUSE（OEM 面板怪癖、启动被系统静默拦下），就放开 `presenting`，
     * 免得之后每次点「分享」都被当成「已在台上」吞掉。不主动弹队列里的下一个——万一面板其实在台上，
     * 那样会叠两个；下一次点击或回到前台自然会弹。
     */
    fun shareLaunched(req: ShareRequest) {
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            delay(PAUSE_WATCHDOG_MS)
            if (presenting == req && foreground) {
                presenting = null
                releasedByWatchdog = req
            }
        }
    }

    /** ON_PAUSE：离开前台（含面板盖上来），不再弹新面板。 */
    fun onPaused() {
        foreground = false
        watchdog?.cancel()
        watchdog = null
    }

    /** 界面收到请求时已不在前台、没法弹：放回队首，等下一次 [onResumed]。 */
    fun shareDeferred(req: ShareRequest) {
        if (presenting == req) presenting = null
        foreground = false
        if (shareQueue.none { it.messageId == req.messageId }) shareQueue.addFirst(req)
    }

    /** `startActivity` 抛 `ActivityNotFoundException`：提示，卡片可再试（复制照样可用）。 */
    fun shareUnavailable(req: ShareRequest) {
        if (presenting == req) presenting = null
        markNotSent(req.messageId)
        sendError.value = NO_SHARE_TARGET
        presentNext()
    }

    /**
     * 线程在屏幕上时由界面 `LaunchedEffect` 调用：盯着本线程消息状态。某条自己发出的消息从「非 sent」
     * 变成 `sent`（分享面板选定了目标 App，回调直接落库；复制过的也算）→ 卡片正是它就收起、撤掉它排队的面板，
     * 并提示「已分享」（本线程任一条都提示，R4）。第一次收到的快照只做收卡，不提示。
     */
    suspend fun watchHandOffs() {
        var notSharedBefore: Set<String>? = null
        repo.observeThread(peerUsername).collect { messages ->
            val own = messages.filter { it.direction == ChatMessage.DIRECTION_OUT }
            val sent = own.filter { it.status == ChatMessage.STATUS_SENT }.map { it.id }.toSet()
            _card.value?.let { if (it.messageId in sent) _card.value = null }
            shareQueue.removeAll { it.messageId in sent }
            notSharedBefore?.let { before ->
                if (sent.any { it in before }) sendError.value = SHARED_TOAST
            }
            notSharedBefore = own.filter { it.status != ChatMessage.STATUS_SENT }.map { it.id }.toSet()
        }
    }

    private fun request(req: ShareRequest) {
        _card.value?.let { if (it.messageId == req.messageId) _card.value = it.copy(phase = SealCard.Phase.SHARING) }
        val pending = listOfNotNull(presenting) + shareQueue
        if (pending.any { it.messageId == req.messageId }) return
        shareQueue.addLast(req)
        presentNext()
    }

    private fun presentNext() {
        if (presenting != null || !foreground) return
        val next = shareQueue.removeFirstOrNull() ?: return
        presenting = next
        launches.trySend(next)
    }

    private fun dropQueued(messageId: String) {
        shareQueue.removeAll { it.messageId == messageId }
    }

    private fun markNotSent(messageId: String) {
        _card.value?.let {
            if (it.messageId == messageId && it.phase == SealCard.Phase.SHARING) {
                _card.value = it.copy(phase = SealCard.Phase.NOT_SENT)
            }
        }
    }

    /**
     * 复制 = 用户自己交出：立即收卡、撤排队、提示「已复制，去聊天软件里粘贴」，按 id 标 `copied`
     * （只标本线程里自己发出的；已分享的不降级）。
     */
    private fun handOffByCopy(messageId: String, peer: String) {
        dropQueued(messageId)
        if (_card.value?.messageId == messageId) _card.value = null
        sendError.value = COPIED_TOAST
        launchGuarded("mark copied", HANDOFF_FAILED) { repo.markCopiedIfOwned(messageId, peer) }
    }

    /**
     * 终审 I1：`viewModelScope` 没有异常处理器，DAO/文件层的意外异常（[MediaSender] 刻意不吞、
     * 删除时目录没删干净抛的 IOException）会直接带崩进程。这里统一兜住：取消照常上抛；
     * 其它异常只记一行脱敏日志（固定英文标签 + 异常类名——不带 message/堆栈，那里可能有带
     * blob id 的路径），再通过 [sendError] 给用户一句话（[userMessage] 为 null 时只记日志）。
     */
    private fun launchGuarded(label: String, userMessage: UiText?, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$label failed: ${e.javaClass.simpleName}")
                if (userMessage != null) sendError.value = userMessage
            }
        }

    /** 可从气泡再交出的文字（[canReshare]）→ 首行 + wire（首行跟随当前系统语言）。其它返回 null。 */
    fun handOffText(message: ChatMessage): String? =
        if (canReshare(message)) repo.textShareText(requireNotNull(message.shareText)) else null

    /** Clears [sendError] once the UI has shown it. */
    fun consumeSendError() {
        sendError.value = null
    }

    override fun onCleared() {
        voicePlayer.release()
    }

    /** `internal`（非 `private`）只为让 [ConversationViewModelTest] 直接喂 [MediaSender.Result.InProgress]——真实并发窗口在 JVM 单测里不可控。 */
    internal fun deliver(result: MediaSender.Result) {
        when (result) {
            is MediaSender.Result.Sealed -> {
                _card.value = SealCard(
                    messageId = result.messageId,
                    peer = peerUsername,
                    shareText = result.shareText,
                    summary = result.summary,
                    phase = SealCard.Phase.READY,
                )
                request(ShareRequest(result.messageId, peerUsername, result.shareText))
            }
            is MediaSender.Result.Failed ->
                if (result.failure in TOAST_FAILURES) sendError.value = UiText.Res(result.failure.messageRes)
            is MediaSender.Result.InProgress -> Unit
            // 已分享消息的重试只交给上传引擎：不弹卡、不弹面板（[retry] 本来也不会把它送到这里）。
            is MediaSender.Result.Rescheduled -> Unit
        }
    }

    companion object {
        private const val TAG = "ConversationViewModel"

        private val COPIED_TOAST: UiText = UiText.Res(R.string.status_copied_toast)
        private val SHARED_TOAST: UiText = UiText.Res(R.string.status_shared_toast)
        val NO_SHARE_TARGET: UiText = UiText.Res(R.string.thread_no_share_target)
        val COPY_FAILED: UiText = UiText.Res(R.string.common_copy_failed)

        /** 面板回来后等系统回调落库的宽限（无合适的 Moyu 时长 token：这是业务等待，不是动效）。 */
        const val NOT_SENT_GRACE_MS = 700L

        /** 启动面板后等 ON_PAUSE 的上限，超时即认为面板没起来。 */
        const val PAUSE_WATCHDOG_MS = 1_000L

        /** 可从气泡再交出的文字：自己发出、文字、存了 wire。 */
        fun canReshare(message: ChatMessage): Boolean =
            message.direction == ChatMessage.DIRECTION_OUT &&
                message.kind == ChatMessage.KIND_TEXT &&
                !message.shareText.isNullOrEmpty()

        private val SEAL_FAILED: UiText = UiText.Res(R.string.media_failure_session_lost)
        private val SEND_FAILED: UiText = UiText.Res(R.string.thread_send_failed)
        private val DOWNLOAD_FAILED: UiText = UiText.Res(R.string.thread_download_failed)
        private val DELETE_FAILED: UiText = UiText.Res(R.string.common_delete_failed)
        private val HANDOFF_FAILED: UiText = UiText.Res(R.string.common_action_failed)

        /** 这些失败要弹字；网络/服务端失败只在气泡上显示红 `!`（spec §5.1）。 */
        private val TOAST_FAILURES = setOf(
            MediaFailure.RATE_LIMITED,
            MediaFailure.TOO_LARGE,
            MediaFailure.STORAGE_FULL,
            MediaFailure.SESSION_LOST,
            MediaFailure.NOT_READY,
        )
    }
}
