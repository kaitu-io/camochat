package app.chencang.android.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.chencang.android.ui.asString
import app.chencang.design.Moyu
import app.chencang.design.MoyuLight
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.Handoff
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.MessagePreview
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.OutgoingMediaStatus

private val LOADING_STATES = setOf(
    MediaItem.STATE_ENCRYPTING,
    MediaItem.STATE_UPLOADING,
    MediaItem.STATE_PENDING,
    MediaItem.STATE_DOWNLOADING,
)

internal fun bubbleShape(isOut: Boolean) = if (isOut) {
    RoundedCornerShape(Moyu.Radius.Bubble, Moyu.Radius.Bubble, Moyu.Radius.BubbleTail, Moyu.Radius.Bubble)
} else {
    RoundedCornerShape(Moyu.Radius.Bubble, Moyu.Radius.Bubble, Moyu.Radius.Bubble, Moyu.Radius.BubbleTail)
}

/**
 * 线程里的一行:文字/占位气泡,或一个媒体条目的气泡。长按菜单:
 * - 自己的文字:分享 / 复制加密消息 / 复制原文 / 删除
 * - 对方的文字:复制(明文) / 删除
 * - 媒体(两个方向):复制加密消息(有分享文本时) / 转发 / 转发全部 N 张 / 删除
 * - 升级占位:复制加密消息 / 删除
 * 失败显示红 `!`(点击重试 / 重新下载);对方未听的语音带红点。发出的状态行「还没发」「已复制」可点,把卡片叫回来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    row: ThreadRow,
    progress: Float?,
    busy: Boolean,
    playing: Boolean,
    /** 收到的项「等待对方上传」且 30 分钟轮询窗口已过（气泡改「还没收到文件 · 点击重试」）。 */
    awaitingWindowExpired: Boolean,
    canForward: Boolean,
    onTapMedia: (ThreadRow) -> Unit,
    onRetry: (ThreadRow) -> Unit,
    /** 「复制加密消息」。 */
    onCopy: (ThreadRow) -> Unit,
    /** 文字气泡的明文：自己的「复制原文」、对方的「复制」。 */
    onCopyOriginal: (ThreadRow) -> Unit,
    /** 自己发出、存了 wire 的文字气泡长按「分享」（与加密卡同一条队列，完成才标已分享）。 */
    onShare: (ThreadRow) -> Unit,
    /** 点状态行「已加密 · 还没发」/「已复制 · 去粘贴」：把这条消息放回加密卡。 */
    onReopen: (ThreadRow) -> Unit,
    /** 同一条消息在线程里展开成几行（相册 N 张 = N），用于「转发全部 N 张」的文案。 */
    albumCount: Int,
    /** 相册里每一张都已在本机（[ThreadRows.canForwardAll]）才出「转发全部 N 张」。 */
    canForwardAll: Boolean,
    /** `indices == null` = 转发整条消息，否则只转发这些下标。 */
    onForward: (ThreadRow, List<Int>?) -> Unit,
    onDelete: (ThreadRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val msg = row.message
    val item = row.item
    val isOut = msg.direction == ChatMessage.DIRECTION_OUT
    var menuOpen by remember { mutableStateOf(false) }
    // 发出的：整条消息失败（对方还看不到 / 永久失败 / 加密失败）才出红「!」，只在这条消息的最后一行（row.outStatus），
    // 与状态行同一判定（MediaLayout.outStatusLine：只有加密重跑时的「发送失败」不出）。
    val showRetry = item != null && if (isOut) {
        row.outStatus?.let { MediaLayout.outStatusLine(it, Handoff.of(msg.status), busy = busy).failed } == true
    } else {
        !busy && item.state == MediaItem.STATE_FAILED
    }
    val hasFile = ThreadRows.hasLocalFile(row)
    val tag = (if (isOut) ConversationTestTags.BUBBLE_OUT_PREFIX else ConversationTestTags.BUBBLE_IN_PREFIX) + row.key

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Moyu.Space.M, vertical = Moyu.Space.Xs),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isOut && showRetry) RetryBadge(row, onRetry)
        Column(horizontalAlignment = if (isOut) Alignment.End else Alignment.Start) {
            Box {
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { if (item != null) onTapMedia(row) },
                            onLongClick = { menuOpen = true },
                        )
                        .testTag(tag),
                ) {
                    when {
                        item == null -> TextBubble(msg, isOut)
                        item.kind == MediaConstants.KIND_VOICE ->
                            VoiceBubble(item, isOut, playing, progress, awaitingWindowExpired)
                        item.kind == MediaConstants.KIND_IMAGE -> ImageBubble(item, progress, awaitingWindowExpired)
                        else -> VideoBubble(item, progress, awaitingWindowExpired)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val isText = item == null && msg.kind == ChatMessage.KIND_TEXT
                    if (isText && isOut) {
                        // 自己发出的文字:卡片被顶掉/关掉后,从这里再交出(两行文本,与卡片一致)。
                        if (ConversationViewModel.canReshare(msg)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = {
                                menuOpen = false
                                onShare(row)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.thread_copy_encrypted)) }, onClick = {
                                menuOpen = false
                                onCopy(row)
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.thread_copy_original)) }, onClick = {
                            menuOpen = false
                            onCopyOriginal(row)
                        })
                    } else if (isText) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_copy)) }, onClick = {
                            menuOpen = false
                            onCopyOriginal(row)
                        })
                    } else if (!msg.shareText.isNullOrEmpty()) {
                        // 媒体与升级占位:复制它的加密消息(媒体 = 两行,收到的 = 原样)。
                        DropdownMenuItem(text = { Text(stringResource(R.string.thread_copy_encrypted)) }, onClick = {
                            menuOpen = false
                            onCopy(row)
                        })
                    }
                    if (item != null && hasFile && canForward) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_forward)) }, onClick = {
                            menuOpen = false
                            onForward(row, listOf(item.index))
                        })
                        if (canForwardAll) {
                            DropdownMenuItem(text = { Text(pluralStringResource(R.plurals.media_forward_all, albumCount, albumCount)) }, onClick = {
                                menuOpen = false
                                onForward(row, null)
                            })
                        }
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = moyuColors.statusDanger) }, onClick = {
                        menuOpen = false
                        onDelete(row)
                    })
                }
            }
            if (isOut) OutStatus(msg, item, row.outStatus, busy, onFailureTap = { onRetry(row) }, onReopen = { onReopen(row) })
        }
        if (!isOut && showRetry) RetryBadge(row, onRetry)
        if (item != null && !isOut && item.kind == MediaConstants.KIND_VOICE &&
            item.state == MediaItem.STATE_READY && !item.played
        ) {
            UnreadDot()
        }
    }
}

@Composable
private fun TextBubble(msg: ChatMessage, isOut: Boolean) {
    val unsupported = msg.kind == ChatMessage.KIND_UNSUPPORTED
    Box(
        modifier = Modifier
            .widthIn(max = Moyu.Size.BubbleMax)
            .clip(bubbleShape(isOut))
            .background(if (isOut) moyuColors.bubbleSelf else moyuColors.bubblePeer)
            .padding(horizontal = Moyu.Space.M, vertical = Moyu.Space.S),
    ) {
        Text(
            if (unsupported) MessagePreview.of(msg).asString() else msg.body,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = if (unsupported) FontStyle.Italic else FontStyle.Normal,
            ),
            color = if (unsupported) moyuColors.textSecondary else moyuColors.textPrimary,
        )
    }
}

@Composable
private fun VoiceBubble(item: MediaItem, isOut: Boolean, playing: Boolean, progress: Float?, awaitingWindowExpired: Boolean) {
    // 收到的语音「等待对方上传」：转圈（窗口过后不转）+ 状态文案顶替时长（先分享、后上传 spec §2）。
    val awaiting = !isOut && item.state == MediaItem.STATE_AWAITING
    val awaitingText = if (awaiting) MediaLayout.incomingStateRes(item.state, awaitingWindowExpired)?.let { stringResource(it) } else null
    Row(
        modifier = Modifier
            // 最小宽度按时长；「已过期」+ 时长放不下时撑宽，不折行（iPhone 15 UAT：短语音字竖排）
            .widthIn(min = MediaLayout.voiceWidth(item.durMs), max = Moyu.Size.BubbleMax)
            .clip(bubbleShape(isOut))
            .background(if (isOut) moyuColors.bubbleSelf else moyuColors.bubblePeer)
            .padding(horizontal = Moyu.Space.M, vertical = Moyu.Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
    ) {
        when {
            item.state == MediaItem.STATE_EXPIRED ->
                Text(stringResource(R.string.media_failure_expired), style = MaterialTheme.typography.labelMedium, color = moyuColors.textTertiary, maxLines = 1, softWrap = false)
            item.state == MediaItem.STATE_CORRUPT ->
                Text(stringResource(R.string.media_failure_corrupt), style = MaterialTheme.typography.labelMedium, color = moyuColors.textTertiary, maxLines = 1, softWrap = false)
            !isOut && item.state in LOADING_STATES -> Ring(progress, Moyu.Space.L, moyuColors.accentPrimary)
            // 有意复用：转圈与占位文案同一档次要文字色
            awaiting && MediaLayout.showsAwaitingSpinner(item.state, awaitingWindowExpired) ->
                Ring(null, Moyu.Space.L, moyuColors.textTertiary)
            else -> Icon(
                if (playing) Icons.Default.GraphicEq else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(if (playing) R.string.media_pause else R.string.media_play),
                tint = moyuColors.textPrimary,
            )
        }
        Spacer(Modifier.width(Moyu.Space.S))
        if (awaitingText != null) {
            Text(awaitingText, style = MaterialTheme.typography.labelMedium, color = moyuColors.textTertiary, maxLines = 1, softWrap = false)
        } else {
            Text(MediaLayout.voiceLabel(item.durMs), style = MaterialTheme.typography.bodyMedium, color = moyuColors.textPrimary, maxLines = 1, softWrap = false)
        }
        if (isOut && item.state == MediaItem.STATE_UPLOADING) {
            // 发出的语音上传中：语音条上一个小进度环（spec 2026-09-30 §1.3），语音本身照样能点着听。
            Spacer(Modifier.width(Moyu.Space.S))
            // 有意复用 Space.M 作小进度环直径（收到的语音用 Space.L），不另加 token。
            Ring(progress, Moyu.Space.M, moyuColors.accentPrimary)
        }
    }
}

@Composable
private fun ImageBubble(item: MediaItem, progress: Float?, awaitingWindowExpired: Boolean) {
    val size = MediaLayout.thumbSize(item.width, item.height)
    val maxPx = with(LocalDensity.current) { Moyu.Size.MediaThumbMax.roundToPx() }
    val thumb = rememberThumbnail(item.localPath, maxPx, video = false, stateKey = item.state)
    Box(
        modifier = Modifier
            .size(size.width, size.height)
            .clip(RoundedCornerShape(Moyu.Radius.Bubble))
            .background(moyuColors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(it, contentDescription = stringResource(R.string.media_image_cd), modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
        MediaStateOverlay(item, progress, awaitingWindowExpired)
    }
}

@Composable
private fun VideoBubble(item: MediaItem, progress: Float?, awaitingWindowExpired: Boolean) {
    val size = MediaLayout.thumbSize(item.width, item.height)
    val maxPx = with(LocalDensity.current) { Moyu.Size.MediaThumbMax.roundToPx() }
    val cover = rememberThumbnail(item.localPath, maxPx, video = true, stateKey = item.state)
    val waitingForTap = item.state == MediaItem.STATE_PENDING || item.state == MediaItem.STATE_FAILED
    val showOverlay = (item.state in LOADING_STATES && !waitingForTap) ||
        item.state == MediaItem.STATE_EXPIRED || item.state == MediaItem.STATE_CORRUPT ||
        item.state == MediaItem.STATE_AWAITING
    Box(
        modifier = Modifier
            .size(size.width, size.height)
            .clip(RoundedCornerShape(Moyu.Radius.Bubble))
            .background(moyuColors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        cover?.let {
            Image(it, contentDescription = stringResource(R.string.media_video_cover_cd), modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
        if (showOverlay) {
            MediaStateOverlay(item, progress, awaitingWindowExpired)
        } else {
            Box(
                modifier = Modifier.size(Moyu.Size.PlayBadge).clip(CircleShape).background(moyuColors.mediaScrim),
                contentAlignment = Alignment.Center,
            ) {
                // 有意复用 MoyuLight.accentOnPrimary：mediaScrim 两条轨都是黑色，前景需恒为浅色
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.media_play), tint = MoyuLight.accentOnPrimary)
            }
        }
        Text(
            MediaLayout.videoLabel(item.durMs),
            style = MaterialTheme.typography.labelSmall,
            // 有意复用 MoyuLight.accentOnPrimary：mediaScrim 两条轨都是黑色，前景需恒为浅色
            color = MoyuLight.accentOnPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Moyu.Space.Xs)
                .clip(RoundedCornerShape(Moyu.Radius.BubbleTail))
                .background(moyuColors.mediaScrim)
                .padding(horizontal = Moyu.Space.Xs),
        )
    }
}

@Composable
private fun BoxScope.MediaStateOverlay(item: MediaItem, progress: Float?, awaitingWindowExpired: Boolean) {
    when (item.state) {
        in LOADING_STATES -> Box(
            modifier = Modifier.matchParentSize().background(moyuColors.mediaScrim),
            contentAlignment = Alignment.Center,
            // 有意复用 MoyuLight.accentOnPrimary：mediaScrim 两条轨都是黑色，前景需恒为浅色
        ) { Ring(progress, Moyu.Size.ProgressRing, MoyuLight.accentOnPrimary) }
        MediaItem.STATE_EXPIRED, MediaItem.STATE_CORRUPT, MediaItem.STATE_AWAITING ->
            Placeholder(
                stringResource(requireNotNull(MediaLayout.incomingStateRes(item.state, awaitingWindowExpired))),
                spinning = MediaLayout.showsAwaitingSpinner(item.state, awaitingWindowExpired),
            )
        else -> Unit
    }
}

/** 占位 + 状态文案（已过期 / 文件已损坏 / 等待对方上传 / 还没收到文件 · 点击重试）；等待对方上传时文案上方转圈。 */
@Composable
private fun BoxScope.Placeholder(text: String, spinning: Boolean = false) {
    Box(
        modifier = Modifier.matchParentSize().background(moyuColors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Moyu.Space.Xs),
            modifier = Modifier.padding(Moyu.Space.Xs),
        ) {
            // 有意复用：转圈与占位文案同一档次要文字色
            if (spinning) Ring(null, Moyu.Space.L, moyuColors.textTertiary)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = moyuColors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Ring(progress: Float?, size: Dp, color: Color) {
    if (progress != null) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(size),
            color = color,
            strokeWidth = Moyu.Size.ProgressStroke,
        )
    } else {
        CircularProgressIndicator(modifier = Modifier.size(size), color = color, strokeWidth = Moyu.Size.ProgressStroke)
    }
}

/**
 * 发出消息气泡下的状态行，只说用户做过的事（[Handoff]）。媒体：按整条消息的 [status]（只在最后一行有，其余行不出），
 * 全部上传完才显示 [Handoff]；失败的状态行用错误色、可点（与红「!」同一个 [onFailureTap]：对方还看不到 → 重新上传；
 * 永久失败 → 只提示原因）。「还没发」「已复制」可点 → [onReopen]（把卡片叫回来）。
 */
@Composable
private fun OutStatus(
    msg: ChatMessage,
    item: MediaItem?,
    status: OutgoingMediaStatus?,
    busy: Boolean,
    onFailureTap: () -> Unit,
    onReopen: () -> Unit,
) {
    if (item != null && status == null) return
    val handoff = Handoff.of(msg.status)
    // 媒体：加密重跑时「发送失败」显示「加密中」、不可点（MediaLayout.outStatusLine）。
    val line = status?.let { MediaLayout.outStatusLine(it, handoff, busy = busy) }
    val failed = line?.failed == true
    val reopenable = line?.reopenable ?: (handoff != Handoff.SHARED && ConversationViewModel.canReshare(msg))
    val base = Modifier.padding(top = Moyu.Space.Xs / 2, end = Moyu.Space.Xs)
    Text(
        stringResource(line?.textRes ?: handoff.statusRes),
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) moyuColors.statusDanger else moyuColors.accentPrimary,
        modifier = when {
            failed -> base.clickable(onClick = onFailureTap).testTag(ConversationTestTags.OUT_STATUS_PREFIX + msg.id)
            reopenable -> base.clickable(onClick = onReopen).testTag(ConversationTestTags.OUT_STATUS_PREFIX + msg.id)
            else -> base
        },
    )
}

@Composable
private fun RetryBadge(row: ThreadRow, onRetry: (ThreadRow) -> Unit) {
    IconButton(onClick = { onRetry(row) }, modifier = Modifier.testTag(ConversationTestTags.RETRY_PREFIX + row.key)) {
        Icon(Icons.Default.Error, contentDescription = stringResource(R.string.common_retry), tint = moyuColors.statusDanger)
    }
}

@Composable
private fun UnreadDot() {
    Box(
        modifier = Modifier
            .padding(start = Moyu.Space.Xs)
            .size(Moyu.Space.S)
            .clip(CircleShape)
            .background(moyuColors.statusDanger),
    )
}
