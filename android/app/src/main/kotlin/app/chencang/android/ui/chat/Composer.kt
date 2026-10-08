package app.chencang.android.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.clipboard.AppClipboard
import app.chencang.android.share.ShareCompletionReceiver
import app.chencang.android.share.excludingOwnShareTarget
import app.chencang.android.ui.asString
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.AppPrefs
import app.chencang.shared.R
import app.chencang.shared.media.HoldToTalk
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.MediaLimits
import app.chencang.shared.media.VoiceRecorder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入栏(spec §3.1):左 🎤/⌨ 切换 ｜ 文字框或「按住 说话」｜ 右 ➕(有草稿时换成「加密」键)。
 * 文字框每次变化交给 [ConversationViewModel.onDraftChanged]（粘贴进来的加密消息 / 配对码不进草稿）。
 * ➕ 面板:相册 / 拍照 / 录像(裁决 R8)。相机权限在首次拍摄时申请；麦克风权限在切到语音模式时就申请
 * （handoff spec §5），按住时还没有权限再兜底申请一次。
 */
@Composable
internal fun ChatComposer(viewModel: ConversationViewModel, machine: HoldToTalk, recorder: VoiceRecorder) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val card by viewModel.card.collectAsStateWithLifecycle()
    val sealAction by viewModel.sealAction.collectAsStateWithLifecycle()
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    // 卡片收起时 card 已经是 null：留住最后一张，淡出动画才有内容可画。
    val lastCard = remember { mutableStateOf<ConversationViewModel.SealCard?>(null) }
    SideEffect { if (card != null) lastCard.value = card }
    val phase by machine.phase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var askMicSettings by remember { mutableStateOf(false) }
    var pendingCamera by remember { mutableStateOf<CameraAction?>(null) }
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) askMicSettings = true
    }
    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaConstants.MAX_ITEMS),
    ) { uris -> viewModel.sendPicked(uris) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) {
            viewModel.sendPicked(listOf(uri))
        } else if (uri != null) {
            // 用户取消了拍照:FileProvider.delete 会把 cacheDir/capture/ 下那个空/半成品文件
            // 真删掉,不用等到下一次 discardCaptures() 才收尾。
            context.contentResolver.delete(uri, null, null)
        }
    }
    val captureVideo = rememberLauncherForActivityResult(CaptureVideoLimited()) { ok ->
        val uri = captureUri
        if (ok && uri != null) {
            viewModel.sendCapturedVideo(uri)
        } else if (uri != null) {
            context.contentResolver.delete(uri, null, null)
        }
    }

    fun launchCamera(action: CameraAction) {
        val uri = CaptureFiles.newUri(context, if (action == CameraAction.PHOTO) "jpg" else "mp4")
        captureUri = uri
        if (action == CameraAction.PHOTO) takePicture.launch(uri) else captureVideo.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingCamera
        pendingCamera = null
        if (granted && action != null) {
            launchCamera(action)
        } else if (!granted) {
            Toast.makeText(context, R.string.media_camera_needed, Toast.LENGTH_SHORT).show()
        }
    }

    fun onCamera(action: CameraAction) {
        panelOpen = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera(action)
        } else {
            pendingCamera = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val onOutcome: (HoldToTalk.Outcome) -> Unit = { outcome ->
        when (outcome) {
            is HoldToTalk.Outcome.Send -> {
                scope.launch {
                    // finish() 要等录音线程真正收尾(muxer.stop())才有结果,不能被这里的取消打断——
                    // 线程是裸 Thread,不认协程取消,打断了它还是会跑完、产出一个没人收的 .ogg。
                    // 用 NonCancellable 保证等到结果,再用 isActive 判断页面是不是已经离开
                    // (scope 被取消):离开了就把这段孤儿录音删掉,不发送、不弹提示。
                    val voice = withContext(NonCancellable) { recorder.finish() }
                    if (!isActive) {
                        voice?.file?.delete()
                        return@launch
                    }
                    if (voice != null) {
                        viewModel.sendVoice(voice)
                    } else {
                        Toast.makeText(context, MediaLimits.VOICE_FAILED, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            HoldToTalk.Outcome.TooShort -> {
                recorder.cancel()
                Toast.makeText(context, MediaLimits.VOICE_TOO_SHORT, Toast.LENGTH_SHORT).show()
            }
            HoldToTalk.Outcome.Cancelled -> recorder.cancel()
        }
    }

    // 60 s 自动发送 + 50 s 起的倒计时靠这个节拍推动状态机。
    LaunchedEffect(phase is HoldToTalk.Phase.Recording) {
        while (machine.phase.value is HoldToTalk.Phase.Recording) {
            // 有意复用 Motion.Quick(140 ms)作节拍:足够细地刷新倒计时(spec §3.2),不另加 token。
            delay(Moyu.Motion.Quick.toLong())
            machine.tick(SystemClock.uptimeMillis())?.let(onOutcome)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (machine.phase.value is HoldToTalk.Phase.Recording) {
                machine.release(SystemClock.uptimeMillis())
                recorder.cancel()
            }
        }
    }

    Column {
        AnimatedVisibility(
            visible = card != null,
            enter = fadeIn(tween(Moyu.Motion.Standard)) + scaleIn(initialScale = 0.92f, animationSpec = tween(Moyu.Motion.Standard)),
            exit = fadeOut(tween(Moyu.Motion.Quick)),
        ) {
            (card ?: lastCard.value)?.let { c ->
                SealCard(
                    card = c,
                    // 联系人还没读出来时用「联系人」，不闪出原始指纹。
                    peerName = contact?.displayName ?: stringResource(R.string.common_contact_fallback),
                    copyFirst = sealAction == AppPrefs.SealAction.COPY,
                    // 只排队弹面板；标「已分享」等系统回调（选定目标 App），不在点击时标。
                    onShare = { viewModel.shareCard() },
                    onCopy = { viewModel.copyCard { AppClipboard.write(context, "encrypted message", it) } },
                    onDismiss = { viewModel.dismissSealed() },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(Moyu.Space.S),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S),
        ) {
            IconButton(
                onClick = {
                    voiceMode = !voiceMode
                    panelOpen = false
                    // Ask for the microphone as soon as the user switches to voice, not at the first press.
                    if (voiceMode && !hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.testTag(ConversationTestTags.VOICE_TOGGLE),
            ) {
                Icon(
                    if (voiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                    contentDescription = stringResource(if (voiceMode) R.string.composer_to_keyboard else R.string.composer_to_voice),
                    tint = moyuColors.textSecondary,
                )
            }
            if (voiceMode) {
                HoldToTalkBar(
                    machine = machine,
                    recorder = recorder,
                    recording = phase is HoldToTalk.Phase.Recording,
                    onRecorderBusy = { Toast.makeText(context, MediaLimits.VOICE_FAILED, Toast.LENGTH_SHORT).show() },
                    hasMic = ::hasMic,
                    requestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onOutcome = onOutcome,
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::onDraftChanged,
                    modifier = Modifier.weight(1f).testTag(ConversationTestTags.DRAFT_FIELD),
                    placeholder = { Text(stringResource(R.string.composer_placeholder), color = moyuColors.textTertiary) },
                    shape = RoundedCornerShape(Moyu.Radius.Input),
                )
            }
            if (!voiceMode && draft.isNotBlank()) {
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        }
                        viewModel.seal()
                    },
                    modifier = Modifier.testTag(ConversationTestTags.SEAL_BUTTON),
                ) { Text(stringResource(R.string.common_encrypt)) }
            } else {
                IconButton(
                    onClick = { panelOpen = !panelOpen },
                    modifier = Modifier.testTag(ConversationTestTags.PLUS),
                ) { Icon(Icons.Default.AddCircleOutline, contentDescription = stringResource(R.string.composer_more), tint = moyuColors.textSecondary) }
            }
        }
        if (panelOpen) {
            PlusPanel(
                onAlbum = {
                    panelOpen = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onPhoto = { onCamera(CameraAction.PHOTO) },
                onVideo = { onCamera(CameraAction.VIDEO) },
            )
        }
    }

    if (askMicSettings) {
        AlertDialog(
            onDismissRequest = { askMicSettings = false },
            title = { Text(stringResource(R.string.media_mic_needed_title)) },
            text = { Text(stringResource(R.string.media_mic_needed_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askMicSettings = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text(stringResource(R.string.media_go_to_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { askMicSettings = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun HoldToTalkBar(
    machine: HoldToTalk,
    recorder: VoiceRecorder,
    recording: Boolean,
    hasMic: () -> Boolean,
    requestMic: () -> Unit,
    onRecorderBusy: () -> Unit,
    onOutcome: (HoldToTalk.Outcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentHasMic by rememberUpdatedState(hasMic)
    val currentRequestMic by rememberUpdatedState(requestMic)
    val currentOnRecorderBusy by rememberUpdatedState(onRecorderBusy)
    val currentOnOutcome by rememberUpdatedState(onOutcome)
    Box(
        modifier = modifier
            // 有意复用 Size.PlayBadge(44dp,最小触控高度)作「按住 说话」条高度(spec §3.1),不另加 token。
            .height(Moyu.Size.PlayBadge)
            .clip(RoundedCornerShape(Moyu.Radius.Input))
            .background(if (recording) moyuColors.surfaceSunken else moyuColors.surfaceRaised)
            // 有意复用 Radius.BubbleTail / 4(1dp)作发丝边框宽度,与现有密文卡一致(spec §3.1),不另加 token。
            .border(Moyu.Radius.BubbleTail / 4, moyuColors.borderHairline, RoundedCornerShape(Moyu.Radius.Input))
            .testTag(ConversationTestTags.HOLD_TO_TALK)
            .pointerInput(machine, recorder) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!currentHasMic()) {
                        currentRequestMic()
                        return@awaitEachGesture
                    }
                    // 先确认录音器真的开了,再让状态机进入「录音中」;上一段还没收尾就这次不录。
                    if (!recorder.start()) {
                        currentOnRecorderBusy()
                        return@awaitEachGesture
                    }
                    machine.press(SystemClock.uptimeMillis())
                    var cancelledBySystem = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            // 这个指针从事件里彻底消失了(手势被别处抢走/系统回收,不是正常抬手也
                            // 不是我们自己 consume 出来的取消信号)——同样不能落到下面的正常松手
                            // 分支,不然会把这段可能没说完的录音当成用户主动松手发出去。
                            cancelledBySystem = true
                            break
                        }
                        // 系统取消这次触摸(息屏/下拉通知栏/来电/窗口失焦等)时,Compose 会在抬手前
                        // 就把这个 change 标成已消费(pressed=false 且 isConsumed=true,不是我们
                        // 自己调用 consume() 造成的)。这必须当成取消,而不是正常抬手——否则会把
                        // 系统打断当成用户主动松手,把半截(可能是切到后台后的静音)录音发出去。
                        if (!change.pressed && change.isConsumed) {
                            cancelledBySystem = true
                            break
                        }
                        change.consume()
                        machine.move(change.position.y - down.position.y, SystemClock.uptimeMillis())
                        if (!change.pressed) break
                    }
                    if (cancelledBySystem) {
                        // release() 结果不用管,只为把状态机推回 Idle(隐藏浮层);但只有它真的还在
                        // Recording(返回非 null)才能顺手 cancel 录音器——如果 60 s 已经由 tick()
                        // 那条自动发送路径抢先跑完(release 返回 null,phase 已经是 Idle),这里再
                        // cancel() 会把 finish() 正在 mux 的文件删掉,把一段正常录音变成 VOICE_FAILED。
                        val outcome = machine.release(SystemClock.uptimeMillis())
                        if (outcome != null) recorder.cancel()
                    } else {
                        machine.release(SystemClock.uptimeMillis())?.let(currentOnOutcome)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (recording) R.string.composer_release_to_send else R.string.composer_hold_to_talk),
            style = MaterialTheme.typography.titleSmall,
            color = moyuColors.textPrimary,
        )
    }
}

@Composable
private fun PlusPanel(onAlbum: () -> Unit, onPhoto: () -> Unit, onVideo: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(moyuColors.surfaceRaised).padding(Moyu.Space.L),
        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.Xl),
    ) {
        PanelItem(Icons.Default.PhotoLibrary, stringResource(R.string.composer_panel_album), ConversationTestTags.PANEL_ALBUM, onAlbum)
        PanelItem(Icons.Default.PhotoCamera, stringResource(R.string.composer_panel_photo), ConversationTestTags.PANEL_PHOTO, onPhoto)
        PanelItem(Icons.Default.Videocam, stringResource(R.string.composer_panel_video), ConversationTestTags.PANEL_VIDEO, onVideo)
    }
}

@Composable
private fun PanelItem(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                // 有意复用 Size.PlayBadge + Space.L(60dp)作 ➕ 面板图标块(spec §3.3),不另加 token。
                .size(Moyu.Size.PlayBadge + Moyu.Space.L)
                .clip(RoundedCornerShape(Moyu.Radius.Card))
                .background(moyuColors.surfaceBase),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = moyuColors.textSecondary) }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = moyuColors.textSecondary,
            modifier = Modifier.padding(top = Moyu.Space.Xs),
        )
    }
}

/**
 * 加密卡：第一行内容摘要（文字首行 / 媒体类型与数量）；第二行说清下一步——「发给 〈名字〉 · 去聊天软件里粘贴给 TA」，
 * 分享面板回来没选目标时换成可点的「还没发 · 点这里再发」（点了 = [onShare]）。
 * 主按钮按用户上次的动作（[copyFirst] = 上次点的是「复制」）。
 */
@Composable
internal fun SealCard(
    card: ConversationViewModel.SealCard,
    peerName: String,
    copyFirst: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val notSent = card.phase == ConversationViewModel.SealCard.Phase.NOT_SENT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Moyu.Space.S)
            .clip(RoundedCornerShape(Moyu.Radius.Card))
            .background(moyuColors.surfaceSunken)
            // 原样沿用现有密文卡:Radius.BubbleTail / 4(1dp)作边框宽度(spec §3.5),不另加 token。
            .border(Moyu.Radius.BubbleTail / 4, moyuColors.accentPrimary.copy(alpha = 0.3f), RoundedCornerShape(Moyu.Radius.Card))
            .padding(Moyu.Space.M)
            .testTag(ConversationTestTags.SEAL_CARD),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = moyuColors.accentPrimary, modifier = Modifier.size(Moyu.Space.L))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.summary.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = moyuColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (notSent) {
                    Text(
                        stringResource(R.string.status_not_sent_tap_again),
                        style = MaterialTheme.typography.labelSmall,
                        color = moyuColors.statusWarn,
                        modifier = Modifier.clickable(onClick = onShare).testTag(ConversationTestTags.SEAL_CAPTION),
                    )
                } else {
                    Text(
                        stringResource(R.string.card_send_to, peerName),
                        style = MaterialTheme.typography.labelSmall,
                        color = moyuColors.textTertiary,
                        modifier = Modifier.testTag(ConversationTestTags.SEAL_CAPTION),
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = moyuColors.textTertiary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Moyu.Space.S),
            horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S),
        ) {
            val share: @Composable RowScope.(Boolean) -> Unit = { primary ->
                SealButton(stringResource(R.string.common_share), primary, ConversationTestTags.SHARE_BUTTON, onShare)
            }
            val copy: @Composable RowScope.(Boolean) -> Unit = { primary ->
                SealButton(stringResource(R.string.common_copy), primary, ConversationTestTags.COPY_BUTTON, onCopy)
            }
            if (copyFirst) {
                copy(true)
                share(false)
            } else {
                share(true)
                copy(false)
            }
        }
    }
}

@Composable
private fun RowScope.SealButton(label: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    val modifier = Modifier.weight(1f).testTag(tag)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/**
 * 弹系统分享面板，并把「选定目标 App」回调接到 [ShareCompletionReceiver]（只带 messageId/peer）。
 * 没有任何 App 能接时抛 [android.content.ActivityNotFoundException]，由调用方提示。
 */
internal fun launchShareSheet(context: Context, req: ConversationViewModel.ShareRequest) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, req.text)
    }
    val callback = ShareCompletionReceiver.callback(context, req.messageId, req.peer)
    context.startActivity(Intent.createChooser(sendIntent, null, callback).excludingOwnShareTarget(context))
    AppClipboard.markConsumedOnNextFocus() // after launch succeeded: a throwing launch must not leave the flag set
}
