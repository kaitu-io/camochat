package app.chencang.android.ui.chat

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.clipboard.AppClipboard
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.android.ui.main.PasteBarHost
import app.chencang.android.ui.main.PasteBarState
import app.chencang.android.ui.resolve
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.media.AwaitingPoller
import app.chencang.shared.media.HoldToTalk
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.media.VoiceRecorder
import app.chencang.shared.profile.contactAvatar
import java.io.File
import java.time.ZoneId
import java.util.Locale

object ConversationTestTags {
    const val LIST = "conversation-list"
    const val EMPTY = "conversation-empty"
    const val BANNER = "conversation-banner"
    const val BANNER_ACTION = "conversation-banner-action"
    const val BANNER_DISMISS = "conversation-banner-dismiss"
    const val DRAFT_FIELD = "conversation-draft"
    const val COPY_BUTTON = "conversation-copy"
    const val SHARE_BUTTON = "conversation-share"
    const val SEAL_BUTTON = "conversation-seal"
    const val SEAL_CARD = "conversation-sealcard"
    const val SEAL_CAPTION = "conversation-sealcard-caption"
    const val BUBBLE_OUT_PREFIX = "conversation-bubble-out-"
    const val BUBBLE_IN_PREFIX = "conversation-bubble-in-"
    const val VOICE_TOGGLE = "conversation-voice-toggle"
    const val HOLD_TO_TALK = "conversation-hold-to-talk"
    const val PLUS = "conversation-plus"
    const val PANEL_ALBUM = "conversation-panel-album"
    const val PANEL_PHOTO = "conversation-panel-photo"
    const val PANEL_VIDEO = "conversation-panel-video"
    const val RETRY_PREFIX = "conversation-retry-"
    const val OUT_STATUS_PREFIX = "conversation-out-status-" // + messageId（失败状态行，可点）
}

private val DOWNLOADABLE = setOf(MediaItem.STATE_PENDING, MediaItem.STATE_FAILED)

/**
 * 对话线程:本地历史(明文,仅本机可见) + 底部输入栏。文字发送不走网络;
 * 语音/图片/视频经 MediaSender 加密封帧后即自动弹分享面板(R1 两行),上传在后台另行完成。
 * 收到的媒体按线程行(多图展开)显示,语音/图片进线程自动下载,视频点了才下载。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    peerUsername: String,
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    /** 输入框里粘贴了别的会话的加密消息：解密后去那个会话。 */
    onOpenThread: (String) -> Unit,
    /** 输入框里粘贴了配对码：去添加联系人。 */
    onOpenWizard: (String) -> Unit,
    /** 粘贴条（输入框上方）：与 tab 页共享同一个状态；点「粘贴」由调用方读剪贴板并分流。 */
    pasteBar: PasteBarState,
    onPasteBarPaste: () -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val others by viewModel.otherContacts.collectAsStateWithLifecycle()
    val albums = remember(rows) { rows.filter { it.item != null }.groupBy { it.message.id } }
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val awaitingExpired by viewModel.awaitingExpired.collectAsStateWithLifecycle()
    val playing by viewModel.voicePlayer.playing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    // 有意复用 Size.VoiceOverlay(160dp)的一半作上滑取消的判定距离(spec §3.2),不另加 token。
    val machine = remember { HoldToTalk(cancelThresholdPx = with(density) { (Moyu.Size.VoiceOverlay / 2).toPx() }) }
    val recorder = remember { VoiceRecorder(File(context.cacheDir, MediaFiles.VOICE_DIR)) }
    val holdPhase by machine.phase.collectAsStateWithLifecycle()
    val amplitude by recorder.amplitude.collectAsStateWithLifecycle()
    var viewingImage by remember { mutableStateOf<ViewedImage?>(null) }
    var playingVideo by remember { mutableStateOf<String?>(null) }
    var forwarding by remember { mutableStateOf<ForwardRequest?>(null) }
    /** 用户点了一个还没下载的视频:记下它的行 key,下完(ready)就自动全屏播放。 */
    var awaitingVideo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sendError) {
        val message = sendError ?: return@LaunchedEffect
        Toast.makeText(context, message.resolve(context), Toast.LENGTH_SHORT).show()
        viewModel.consumeSendError()
    }

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    // 这一轮等待里有没有真正观察到 DOWNLOADING——用来分清「重试刚点下去,rows 上还是旧 FAILED」
    // 和「真的下载过、又失败了一次」。awaitingVideo 一换新 key(新的一次等待),这个标记就重置。
    val awaitingVideoSeenDownloading = remember(awaitingVideo) { mutableStateOf(false) }
    LaunchedEffect(rows, awaitingVideo) {
        val key = awaitingVideo ?: return@LaunchedEffect
        val item = rows.firstOrNull { it.key == key }?.item
        when (item?.state) {
            MediaItem.STATE_READY -> {
                playingVideo = item.localPath
                awaitingVideo = null
            }
            MediaItem.STATE_DOWNLOADING -> awaitingVideoSeenDownloading.value = true
            MediaItem.STATE_FAILED ->
                // 触发下载的那一刻(重试一个 FAILED 视频)条目往往还没来得及转成 DOWNLOADING,
                // rows 上看到的还是旧的 FAILED——这时候清掉 awaitingVideo 会让重试后的自动播放
                // 失效,所以只在真正下载过又失败(DOWNLOADING → FAILED)时才放弃等待;不然一次
                // 真失败后台又跑起来的 keepMediaFresh 重试成功,会在用户没点开的情况下自动全屏播放。
                if (awaitingVideoSeenDownloading.value) awaitingVideo = null
            // 条目没了(消息被删)或到了下不动的终态才放弃等待。「等待对方上传」也放弃:之后轮询拿到了
            // 不自动全屏播放(用户早已不在等这一下),点一下再看(先分享、后上传 spec §2)。
            null, MediaItem.STATE_EXPIRED, MediaItem.STATE_CORRUPT, MediaItem.STATE_AWAITING -> awaitingVideo = null
            else -> Unit // PENDING:还没转成 DOWNLOADING,继续等
        }
    }

    // 线程可见期间:恢复中断条目 + 自动下载语音/图片(离开页面即取消)。每屏只有这一个长期收集器,
    // 回到前台不重启它——重启会取消正在跑的自动下载(终审 I2)。
    LaunchedEffect(viewModel) { viewModel.keepMediaFresh() }
    // 每次真正回到前台(ON_RESUME,resumeTick 变化)只重跑「超期 + 中断恢复」这一遍——不然线程开着
    // 超过 24 h 后台再切回来,过期的媒体还显示成可下载(UAT F2)。tick 0 = 首次进入,keepMediaFresh 已跑过。
    val resumeTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(viewModel, resumeTick.intValue) {
        if (resumeTick.intValue > 0) viewModel.refreshMediaStates()
    }

    // 跳去验证页/切后台(ON_STOP)时停掉正在放的语音——不能让声音在别的屏幕上继续响。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        // addObserver 会把当前已到达的状态(含 ON_RESUME)立刻补发一遍——那是「首次进入」,不是「回到前台」,跳过。
        var initialResumeSeen = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.voicePlayer.stop()
                // 分享面板一次一个：面板盖上来 = ON_PAUSE；回来 = ON_RESUME，这时才弹下一个。
                // 「等待对方上传」轮询只在线程可见时跑：ON_RESUME 立即再试并重开窗口，ON_PAUSE 停。
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onPaused()
                    viewModel.onThreadHidden()
                }
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onResumed()
                    viewModel.onThreadVisible()
                    if (initialResumeSeen) resumeTick.intValue++
                    initialResumeSeen = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onThreadHidden()
        }
    }

    // 分享热桥(spec 2026-09-30 §5):VM 一次只放出一个请求。这里只弹面板,不标「已送出」——
    // 选定目标 App 时系统回调 ShareCompletionReceiver 落库,下面的 watchHandOffs 看到状态变化再收卡、提示。
    LaunchedEffect(viewModel) {
        viewModel.shareRequests.collect { req ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                viewModel.shareDeferred(req) // 后台 startActivity 会被系统静默拦掉:等回到前台再弹
            } else {
                try {
                    launchShareSheet(context, req)
                    viewModel.shareLaunched(req)
                } catch (e: ActivityNotFoundException) {
                    viewModel.shareUnavailable(req)
                }
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.watchHandOffs() }
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is ConversationViewModel.ThreadNav.OpenThread -> onOpenThread(nav.peerUsername)
                is ConversationViewModel.ThreadNav.OpenWizard -> onOpenWizard(nav.wire)
            }
        }
    }

    Scaffold(
        containerColor = moyuColors.surfaceBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = moyuColors.surfaceBase),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = moyuColors.accentPrimary)
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S),
                        modifier = Modifier.clickable { contact?.let { onOpenContact(it.fingerprintHex) } },
                    ) {
                        // 联系人还没载入时用 peerUsername 顶上：它就是指纹（配对时 username = 指纹），颜色不会跳。
                        val avatarName = contact?.displayName ?: peerUsername
                        val avatarSeed = contact?.fingerprintHex ?: peerUsername
                        MoyuAvatar(
                            spec = remember(avatarName, avatarSeed) { contactAvatar(avatarName, avatarSeed) },
                            size = Moyu.Size.AvatarInline,
                        )
                        val verified = contact?.verified == true
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Moyu.Space.Xs)) {
                                Text(contact?.displayName ?: peerUsername, style = MaterialTheme.typography.titleMedium, color = moyuColors.textPrimary)
                                Icon(
                                    if (verified) Icons.Default.Verified else Icons.Outlined.Shield,
                                    contentDescription = stringResource(
                                        if (verified) R.string.verify_status_verified else R.string.verify_status_unverified,
                                    ),
                                    tint = if (verified) moyuColors.accentPrimary else moyuColors.statusWarn,
                                    modifier = Modifier.size(Moyu.Space.L),
                                )
                            }
                            // 已核对：只显示名字和图标；未核对：提示点这里去核对。
                            if (!verified) {
                                Text(
                                    stringResource(R.string.thread_unverified_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = moyuColors.textTertiary,
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            banner?.let { b ->
                ThreadBannerBar(
                    banner = b,
                    onAction = { contact?.let { onOpenContact(it.fingerprintHex) } },
                    onDismiss = { viewModel.dismissBanner(b) },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(moyuColors.surfaceSunken)) {
                if (rows.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(Moyu.Space.Xxl).testTag(ConversationTestTags.EMPTY),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.thread_empty_title), style = MaterialTheme.typography.bodyMedium, color = moyuColors.textSecondary)
                        Text(
                            stringResource(R.string.thread_empty_send),
                            style = MaterialTheme.typography.bodySmall,
                            color = moyuColors.textTertiary,
                            modifier = Modifier.padding(top = Moyu.Space.Xs),
                        )
                        Text(
                            stringResource(R.string.thread_empty_receive),
                            style = MaterialTheme.typography.bodySmall,
                            color = moyuColors.textTertiary,
                            modifier = Modifier.padding(top = Moyu.Space.Xs),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag(ConversationTestTags.LIST),
                        contentPadding = PaddingValues(vertical = Moyu.Space.S),
                    ) {
                        itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
                            val prev = rows.getOrNull(i - 1)
                            if (ThreadFormat.showTimeChip(prev?.message, row.message)) {
                                Text(
                                    ThreadFormat.chipText(
                                        row.message.timestamp,
                                        System.currentTimeMillis(),
                                        Locale.getDefault(),
                                        ZoneId.systemDefault(),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = moyuColors.textTertiary,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Moyu.Space.S).wrapContentWidth(),
                                )
                            }
                            MessageRow(
                                row = row,
                                progress = progress[row.key],
                                busy = row.message.id in busy || row.key in busy,
                                playing = playing == row.key,
                                awaitingWindowExpired = row.item?.let {
                                    AwaitingPoller.Key(it.messageId, it.index) in awaitingExpired
                                } == true,
                                canForward = others.isNotEmpty(),
                                albumCount = albums[row.message.id]?.size ?: 1,
                                canForwardAll = ThreadRows.canForwardAll(albums[row.message.id].orEmpty()),
                                onTapMedia = { r ->
                                    handleMediaTap(
                                        r,
                                        viewModel,
                                        openImage = { viewingImage = it },
                                        openVideo = { playingVideo = it },
                                        onVideoDownloadStarted = { awaitingVideo = it },
                                    )
                                },
                                onRetry = { r ->
                                    val item = r.item
                                    if (r.message.direction == ChatMessage.DIRECTION_OUT) {
                                        // 对方还看不到 → 重新上传（不弹面板）；永久失败 → 只提示原因；加密失败 → 重走 seal
                                        viewModel.onFailureTapped(r)
                                    } else if (item != null) {
                                        // 视频的「!」重试跟点未下载视频一个待遇:下完自动全屏播放。
                                        if (item.kind == MediaConstants.KIND_VIDEO) awaitingVideo = r.key
                                        viewModel.download(item.messageId, item.index)
                                    }
                                },
                                onCopy = { r -> viewModel.copyMessage(r.message) { AppClipboard.write(context, "encrypted message", it) } },
                                onCopyOriginal = { r -> viewModel.copyOriginal(r.message) { AppClipboard.write(context, "message text", it) } },
                                onShare = { r -> viewModel.shareMessage(r.message) },
                                onReopen = { r -> viewModel.reopenCard(r.message.id) },
                                onForward = { r, indices -> forwarding = ForwardRequest(r.message.id, indices) },
                                onDelete = { r -> viewModel.delete(r.message.id) },
                                modifier = Modifier.animateItemPlacement(),
                            )
                        }
                    }
                }
                (holdPhase as? HoldToTalk.Phase.Recording)?.let { VoiceOverlay(it, amplitude) }
            }
            ThreadBottom(pasteBar, onPasteBarPaste) { ChatComposer(viewModel, machine, recorder) }
        }
    }

    viewingImage?.let { v ->
        ImageViewer(
            path = v.path,
            onForward = if (others.isNotEmpty()) {
                {
                    viewingImage = null
                    forwarding = ForwardRequest(v.messageId, listOf(v.index))
                }
            } else {
                null
            },
        ) { viewingImage = null }
    }
    playingVideo?.let { path -> VideoPlayerDialog(path) { playingVideo = null } }
    forwarding?.let { req ->
        ForwardPicker(
            contacts = others,
            onPick = { c ->
                forwarding = null
                viewModel.forward(req.messageId, req.indices, c.username, c.displayName)
            },
            onDismiss = { forwarding = null },
        )
    }
}

/** 对话页底部：粘贴条在输入框（[composer]）正上方。从屏幕里拆出来，好脱离 ViewModel 测试。 */
@Composable
internal fun ThreadBottom(pasteBar: PasteBarState, onPasteBarPaste: () -> Unit, composer: @Composable () -> Unit) {
    Column {
        PasteBarHost(pasteBar, onPasteBarPaste)
        composer()
    }
}

/** 正在全屏看的那张图：路径 + 它在原消息里的下标（顶栏「转发」用）。 */
private data class ViewedImage(val path: String, val messageId: String, val index: Int)

/** 待选联系人的转发请求；[indices] `null` = 整条消息。 */
private data class ForwardRequest(val messageId: String, val indices: List<Int>?)

/** 点媒体气泡:有本地明文就打开/播放;没有且可下载就下载(视频只在这里触发下载,下完自动播放)。 */
private fun handleMediaTap(
    row: ThreadRow,
    vm: ConversationViewModel,
    openImage: (ViewedImage) -> Unit,
    openVideo: (String) -> Unit,
    onVideoDownloadStarted: (String) -> Unit,
) {
    val item = row.item ?: return
    val isOut = row.message.direction == ChatMessage.DIRECTION_OUT
    val path = item.localPath?.takeIf { isOut || item.state == MediaItem.STATE_READY }
    if (path == null) {
        if (!isOut && item.state == MediaItem.STATE_AWAITING) {
            // 「等待对方上传」/「还没收到文件 · 点击重试」:立即再试并重开 30 分钟窗口(视频拿到后不自动播放)。
            vm.retryAwaiting(item.messageId, item.index)
            return
        }
        if (!isOut && item.state in DOWNLOADABLE) {
            if (item.kind == MediaConstants.KIND_VIDEO) onVideoDownloadStarted(row.key)
            vm.download(item.messageId, item.index)
        }
        return
    }
    when (item.kind) {
        MediaConstants.KIND_VOICE -> vm.toggleVoice(row)
        MediaConstants.KIND_IMAGE -> openImage(ViewedImage(path, item.messageId, item.index))
        else -> openVideo(path)
    }
}
