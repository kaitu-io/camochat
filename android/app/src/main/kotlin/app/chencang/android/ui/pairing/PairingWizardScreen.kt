package app.chencang.android.ui.pairing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.produceState
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.clipboard.AppClipboard
import app.chencang.android.share.ApkShare
import app.chencang.android.share.PairingShareReceiver
import app.chencang.android.ui.share.InviteInstallSheet
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.chencang.android.share.PairingCardShare
import app.chencang.android.share.excludingOwnShareTarget
import app.chencang.android.ui.components.EmojiSealGrid
import app.chencang.android.ui.components.SealBreathIcon
import app.chencang.android.ui.components.sealStampIn
import app.chencang.android.ui.me.NamePrompt
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.pairing.PairingLink
import app.chencang.shared.pairing.PairingShareText

object PairingWizardTestTags {
    const val BACK_BUTTON = "pairing-wizard-back"
    const val QR_IMAGE = "pairing-wizard-qr"
    const val COPY_BUTTON = "pairing-wizard-copy"
    const val NOT_INSTALLED = "pairing-wizard-not-installed"
    const val SHARE_BUTTON = "pairing-wizard-share"
    const val SHOW_NEXT = "pairing-wizard-show-next"
    const val SHOW_RECEIVED_PASTE = "pairing-wizard-show-received-paste"
    const val SHOW_RECEIVED_SCAN = "pairing-wizard-show-received-scan"
    const val RECEIVE_INPUT = "pairing-wizard-receive-input"
    const val RECEIVE_PASTE = "pairing-wizard-receive-paste"
    const val RECEIVE_SCAN = "pairing-wizard-receive-scan"
    const val RECEIVE_NEXT = "pairing-wizard-receive-next"
    const val RECEIVE_ERROR = "pairing-wizard-receive-error"
    const val RECEIVE_WAITING = "pairing-wizard-receive-waiting"
    const val WORKING = "pairing-wizard-working"
    const val CONFIRM_MATCH = "pairing-wizard-confirm-match"
    const val CONFIRM_LATER = "pairing-wizard-confirm-later"
    const val CONFIRM_MISMATCH = "pairing-wizard-confirm-mismatch"
    const val NAME_FIELD = "pairing-wizard-name-field"
    const val FAILED_MESSAGE = "pairing-wizard-failed-message"
    const val FAILED_RETRY = "pairing-wizard-failed-retry"
    const val FAILED_BACK = "pairing-wizard-failed-back"
    const val RECEIVE_HINT = "pairing-receive-hint"
    const val RECEIVE_OPEN_CONTACT = "pairing-receive-open-contact"
    const val RECEIVE_DELETE_INVITE = "pairing-receive-delete-invite"
    const val NOTE = "pairing-note"
    const val RESEND = "pairing-resend"
    const val DELETE = "pairing-delete"
}

/** Delete-pairing-code confirmation (shared by the wizard's top bar and the Contacts tab's pending row). */
@Composable
internal fun DeleteInviteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_delete_title)) },
        text = { Text(stringResource(R.string.pairing_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete), color = moyuColors.statusDanger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Role-parameterised three-step add-contact wizard UI (send code / enter their code / verify), driven
 * entirely by [PairingWizardViewModel.ui]. Renders one of five bodies per [WizardStage]:
 * [WizardStage.Working], [WizardStage.Show] (QR + pairing code, share is the primary action),
 * [WizardStage.Receive] (paste/scan their code), [WizardStage.Confirm] (8-emoji safety code), and
 * [WizardStage.Failed].
 *
 * Navigation out of the wizard is driven by the caller collecting [PairingWizardViewModel.openThread].
 */
@Composable
fun PairingWizardScreen(
    viewModel: PairingWizardViewModel,
    onScanRequest: () -> Unit,
    onBack: () -> Unit,
    onOpenContact: (fingerprintHex: String) -> Unit,
    onClipboardPasted: () -> Unit,
    shareSite: String,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val inviteId by viewModel.inviteId.collectAsStateWithLifecycle()
    val canResendInvite by viewModel.canResendInvite.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val shareTarget by viewModel.shareTarget.collectAsStateWithLifecycle()
    val otherAwaitingInvites by viewModel.otherAwaitingInvites.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewModel.wizardStopped()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.wizardRefocused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(context.getString(it)) }
    }
    ActionNoticeEffect(
        notices = viewModel.actionNotices,
        host = snackbarHostState,
        onAction = viewModel::deleteInvite,
    )
    // 互发邀请提示只属于出示幕：离开出示幕就收掉，免得挡住后面的提示。
    val onShowStage = ui.stage is WizardStage.Show
    LaunchedEffect(onShowStage) {
        if (!onShowStage) snackbarHostState.currentSnackbarData?.dismiss()
    }

    PairingWizardContent(
        shareSite = shareSite,
        myName = viewModel.myName(),
        snackbarHostState = snackbarHostState,
        ui = ui,
        inviteId = inviteId,
        canResendInvite = canResendInvite,
        note = note,
        shareTarget = shareTarget,
        otherAwaitingInvites = otherAwaitingInvites,
        onScanRequest = onScanRequest,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onNoteChange = viewModel::setNote,
        onCopied = viewModel::copied,
        onShareLaunched = viewModel::shareSheetLaunched,
        onAdvance = viewModel::advanceFromShow,
        onResend = viewModel::backToShow,
        onInputChanged = viewModel::onInputChanged,
        onPaste = viewModel::pasteFromClipboard,
        onSubmit = viewModel::submitWire,
        onConfirmMatch = viewModel::confirmMatch,
        onVerifyLater = viewModel::verifyLater,
        onNameContinue = viewModel::submitName,
        onNameSkip = viewModel::skipName,
        onRejectMismatch = viewModel::rejectMismatch,
        onRetry = viewModel::retry,
        onDeleteInvite = viewModel::deleteInvite,
        onClipboardPasted = onClipboardPasted,
    )
}

/**
 * Shows each action notice (互发邀请) as a snackbar with its action and a dismiss button, so it can be
 * declined and never blocks later snackbars; [onAction] gets the notice's invite when the action is tapped.
 */
@Composable
internal fun ActionNoticeEffect(
    notices: Flow<ReceiveNotice>,
    host: SnackbarHostState,
    onAction: (pairingId: String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(notices) {
        notices.collect { notice ->
            val result = host.showSnackbar(
                message = context.getString(notice.textRes),
                actionLabel = context.getString(R.string.pairing_mutual_invite_delete),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) notice.deleteInviteId?.let(onAction)
        }
    }
}

/** The wizard's pure rendering layer (no ViewModel): state in, actions out, for composition tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingWizardContent(
    shareSite: String,
    myName: String = "",
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    ui: WizardUi,
    inviteId: String?,
    canResendInvite: Boolean,
    note: String,
    shareTarget: Pair<String, String>?,
    otherAwaitingInvites: Int,
    onScanRequest: () -> Unit,
    onBack: () -> Unit,
    onOpenContact: (fingerprintHex: String) -> Unit,
    onNoteChange: (String) -> Unit,
    onCopied: () -> Unit,
    onShareLaunched: () -> Unit,
    onAdvance: () -> Unit,
    onResend: () -> Unit,
    onInputChanged: (String) -> Unit,
    onPaste: (String?) -> Unit,
    onSubmit: (String) -> Unit,
    onConfirmMatch: (name: String) -> Unit,
    onVerifyLater: (name: String) -> Unit,
    onNameContinue: (String) -> Unit,
    onNameSkip: () -> Unit,
    onRejectMismatch: () -> Unit,
    onRetry: () -> Unit,
    onDeleteInvite: (pairingId: String) -> Unit,
    /** A wizard "Paste" button read the clipboard: the shared paste bar marks it handled (spec 4.2). */
    onClipboardPasted: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = moyuColors.surfaceBase,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_add_contact), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = moyuColors.surfaceBase),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(PairingWizardTestTags.BACK_BUTTON)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = moyuColors.accentPrimary,
                        )
                    }
                },
                actions = {
                    // Only "my pairing code" can be deleted; a reply or the verify step has no such entry.
                    if (inviteId != null) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.testTag(PairingWizardTestTags.DELETE),
                        ) { Text(stringResource(R.string.common_delete), color = moyuColors.statusDanger) }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .consumeWindowInsets(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Moyu.Space.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WizardStepper(ui.stepTitles, ui.stepIndex)
            AnimatedContent(
                targetState = ui.stage,
                contentKey = { it::class },
                transitionSpec = {
                    fadeIn(tween(Moyu.Motion.Standard, delayMillis = Moyu.Motion.Quick / 2)) togetherWith
                        fadeOut(tween(Moyu.Motion.Quick))
                },
                modifier = Modifier.fillMaxWidth(),
                label = "wizard-stage",
            ) { stage ->
                when (stage) {
                    WizardStage.Working -> WorkingAct()
                    WizardStage.AskName -> NamePrompt(onContinue = onNameContinue, onSkip = onNameSkip)
                    is WizardStage.Show -> ShowAct(
                        stage = stage,
                        shareSite = shareSite,
                        myName = myName,
                        note = note,
                        showNote = !stage.isResponse && inviteId != null && otherAwaitingInvites > 0,
                        shareTarget = shareTarget,
                        onNoteChange = onNoteChange,
                        onCopied = onCopied,
                        onShareLaunched = onShareLaunched,
                        onNext = onAdvance,
                        onPaste = onPaste,
                        onClipboardPasted = onClipboardPasted,
                        onScanRequest = onScanRequest,
                    )
                    is WizardStage.Receive -> ReceiveAct(
                        stage = stage,
                        canResend = inviteId != null && canResendInvite,
                        onResend = onResend,
                        onOpenContact = onOpenContact,
                        onDeleteInvite = onDeleteInvite,
                        onScanRequest = onScanRequest,
                        onInputChanged = onInputChanged,
                        onPaste = onPaste,
                        onClipboardPasted = onClipboardPasted,
                        onSubmit = onSubmit,
                    )
                    is WizardStage.Confirm -> ConfirmAct(
                        stage = stage,
                        onConfirmMatch = onConfirmMatch,
                        onVerifyLater = onVerifyLater,
                        onRejectMismatch = onRejectMismatch,
                    )
                    is WizardStage.Failed -> FailedAct(stage = stage, onRetry = onRetry, onBack = onBack)
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteInviteDialog(
            onConfirm = { confirmDelete = false; inviteId?.let(onDeleteInvite) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Dots + titles stepper: current accent filled, past accent outlined, upcoming textTertiary filled.
 *  Titles may wrap (English is longer). */
@Composable
private fun WizardStepper(titles: List<Int>, currentIndex: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Moyu.Space.Xl),
        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S),
    ) {
        titles.forEachIndexed { index, title ->
            val isPast = index < currentIndex
            val color = if (index <= currentIndex) moyuColors.accentPrimary else moyuColors.textTertiary
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(Moyu.Space.S)
                        .then(
                            if (isPast) {
                                Modifier.border(Moyu.Radius.BubbleTail / 4, color, CircleShape)
                            } else {
                                Modifier.background(color, CircleShape)
                            },
                        ),
                )
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Moyu.Space.Xs),
                )
            }
        }
    }
}

@Composable
private fun WorkingAct() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Moyu.Space.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SealBreathIcon(64.dp)
        Spacer(Modifier.height(Moyu.Space.L))
        Text(
            stringResource(R.string.pairing_working),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textSecondary,
            modifier = Modifier.testTag(PairingWizardTestTags.WORKING),
        )
    }
}

/** Send step: share (primary) / copy, QR for face to face, and a quiet "They scanned it · Next". */
@Composable
private fun ShowAct(
    stage: WizardStage.Show,
    shareSite: String,
    myName: String,
    note: String,
    showNote: Boolean,
    shareTarget: Pair<String, String>?,
    onNoteChange: (String) -> Unit,
    onCopied: () -> Unit,
    onShareLaunched: () -> Unit,
    onNext: () -> Unit,
    onPaste: (String?) -> Unit,
    onClipboardPasted: () -> Unit,
    onScanRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installSheet by remember { mutableStateOf(false) }
    // 卡片图就是面对面扫的二维码；预览在后台线程渲染，期间占位保持尺寸。分享时另行渲染同一张。
    val card by produceState<Bitmap?>(null, stage.wire, stage.isResponse, myName, shareSite) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                PairingCard.render(
                    context,
                    if (stage.isResponse) PairingCard.Kind.RESPONSE else PairingCard.Kind.INVITE,
                    myName,
                    PairingLink.make(shareSite, stage.wire),
                )
            }.getOrNull()
        }
    }
    // 复制的是文字版：带链接的说明，不带乱码。
    val shareText = pairingShareText(context, shareSite, stage.wire, isResponse = stage.isResponse)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(if (!stage.isResponse) R.string.pairing_show_hint_invite else R.string.pairing_show_hint_response),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.L))
        Button(
            onClick = {
                shareTarget?.let { (kind, id) ->
                    scope.launch {
                        launchPairingShare(context, shareSite, stage.wire, stage.isResponse, myName, kind, id)
                        onShareLaunched()
                    }
                }
            },
            enabled = shareTarget != null,
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.SHARE_BUTTON),
        ) { Text(stringResource(R.string.pairing_share_to_peer), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(Moyu.Space.S))
        OutlinedButton(
            onClick = { AppClipboard.write(context, "pairing code", shareText); onCopied() },
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.COPY_BUTTON),
        ) { Text(stringResource(R.string.common_copy), textAlign = TextAlign.Center) }
        TextButton(
            onClick = { installSheet = true },
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.NOT_INSTALLED),
        ) { Text(stringResource(R.string.invite_not_installed), textAlign = TextAlign.Center) }
        if (installSheet) {
            InviteInstallSheet(
                site = shareSite,
                onShareLink = { ApkShare.shareText(context, it) },
                onShareApk = { asZip -> scope.launch { ApkShare.share(context, asZip) } },
                onDismiss = { installSheet = false },
            )
        }
        if (!stage.isResponse) {
            // 对方可能已经先把邀请发来了：直接在这里收，不必先离开出示幕。读剪贴板只发生在点「粘贴」时。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.add_contact_received_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = moyuColors.textSecondary,
                )
                TextButton(
                    onClick = {
                        val text = clipboardText(context)
                        onClipboardPasted()
                        onPaste(text)
                    },
                    modifier = Modifier.testTag(PairingWizardTestTags.SHOW_RECEIVED_PASTE),
                ) { Text(stringResource(R.string.pairing_paste)) }
                TextButton(
                    onClick = onScanRequest,
                    modifier = Modifier.testTag(PairingWizardTestTags.SHOW_RECEIVED_SCAN),
                ) { Text(stringResource(R.string.pairing_scan)) }
            }
        }
        if (showNote) {
            Spacer(Modifier.height(Moyu.Space.L))
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.pairing_note_placeholder)) },
                supportingText = { Text(stringResource(R.string.pairing_note_hint)) },
                shape = RoundedCornerShape(Moyu.Radius.Input),
                modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.NOTE),
            )
        }
        Spacer(Modifier.height(Moyu.Space.L))
        val cardModifier = Modifier
            .width(Moyu.Size.ShareCardPreview)
            .aspectRatio(Moyu.Size.ShareCardWidth.value / Moyu.Size.ShareCardHeight.value)
            .clip(RoundedCornerShape(Moyu.Radius.Card))
            .testTag(PairingWizardTestTags.QR_IMAGE)
        card?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.share_card_cd),
                modifier = cardModifier,
            )
        } ?: Box(cardModifier)
        Spacer(Modifier.height(Moyu.Space.L))
        TextButton(onClick = onNext, modifier = Modifier.testTag(PairingWizardTestTags.SHOW_NEXT)) {
            Text(
                stringResource(if (stage.isResponse) R.string.pairing_sent_next else R.string.pairing_next_after_scan),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Enter step: "Paste" (primary) or scan their code; a recognized text submits by itself. Never reads
 *  the clipboard on entry. */
@Composable
private fun ReceiveAct(
    stage: WizardStage.Receive,
    canResend: Boolean,
    onResend: () -> Unit,
    onOpenContact: (String) -> Unit,
    onDeleteInvite: (pairingId: String) -> Unit,
    onScanRequest: () -> Unit,
    onInputChanged: (String) -> Unit,
    onPaste: (String?) -> Unit,
    onClipboardPasted: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var wireInput by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (stage.waitingForPeer) {
            Text(
                stringResource(R.string.pairing_waiting_for_peer),
                style = MaterialTheme.typography.bodyMedium,
                color = moyuColors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(PairingWizardTestTags.RECEIVE_WAITING),
            )
            Spacer(Modifier.height(Moyu.Space.M))
        }
        Text(
            stringResource(R.string.pairing_enter_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.L))
        Button(
            onClick = {
                val text = clipboardText(context)
                onClipboardPasted()
                if (text != null) wireInput = text
                onPaste(text)
            },
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.RECEIVE_PASTE),
        ) { Text(stringResource(R.string.pairing_paste), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(Moyu.Space.S))
        OutlinedButton(
            onClick = onScanRequest,
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.RECEIVE_SCAN),
        ) { Text(stringResource(R.string.pairing_scan_qr), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(Moyu.Space.L))
        OutlinedTextField(
            value = wireInput,
            onValueChange = { wireInput = it; onInputChanged(it) },
            placeholder = { Text("🔒…") },
            shape = RoundedCornerShape(Moyu.Radius.Input),
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.RECEIVE_INPUT),
        )
        val notice = stage.notice
        if (notice != null) {
            Spacer(Modifier.height(Moyu.Space.M))
            Text(
                stringResource(notice.textRes),
                style = MaterialTheme.typography.bodySmall,
                // A classification hint is not a failure: secondary colour; only a real handshake failure is danger.
                color = if (notice.isHint) moyuColors.textSecondary else moyuColors.statusDanger,
                modifier = Modifier.testTag(
                    if (notice.isHint) PairingWizardTestTags.RECEIVE_HINT else PairingWizardTestTags.RECEIVE_ERROR,
                ),
            )
            notice.contactFingerprintHex?.let { fp ->
                TextButton(
                    onClick = { onOpenContact(fp) },
                    modifier = Modifier.testTag(PairingWizardTestTags.RECEIVE_OPEN_CONTACT),
                ) { Text(stringResource(R.string.pairing_open_contact)) }
            }
            notice.deleteInviteId?.let { id ->
                TextButton(
                    onClick = { onDeleteInvite(id) },
                    modifier = Modifier.testTag(PairingWizardTestTags.RECEIVE_DELETE_INVITE),
                ) { Text(stringResource(R.string.pairing_mutual_invite_delete)) }
            }
        }
        Spacer(Modifier.height(Moyu.Space.S))
        // For a code typed or edited by hand that is not recognized yet.
        TextButton(
            onClick = { onSubmit(wireInput.trim()) },
            enabled = wireInput.isNotBlank(),
            modifier = Modifier.testTag(PairingWizardTestTags.RECEIVE_NEXT),
        ) { Text(stringResource(R.string.common_next)) }
        if (canResend) {
            TextButton(onClick = onResend, modifier = Modifier.testTag(PairingWizardTestTags.RESEND)) {
                Text(stringResource(R.string.pairing_resend), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Verify step: what to do with the 8 symbols, the symbols, an optional name, then match / later / mismatch. */
@Composable
private fun ConfirmAct(
    stage: WizardStage.Confirm,
    onConfirmMatch: (name: String) -> Unit,
    onVerifyLater: (name: String) -> Unit,
    onRejectMismatch: () -> Unit,
) {
    val view = LocalView.current
    // Starts hidden so `sealStampIn`'s flip-driven curve actually plays on entry.
    var stampVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { stampVisible = true }
    var showMismatchDialog by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.verify_explain_compare),
            fontSize = Moyu.FontSize.Title,
            fontWeight = FontWeight.SemiBold,
            color = moyuColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.Xs))
        Text(
            stringResource(R.string.verify_explain_meaning),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.L))
        Box(modifier = Modifier.sealStampIn(visible = stampVisible)) {
            EmojiSealGrid(stage.emoji)
        }
        Spacer(Modifier.height(Moyu.Space.Xl))
        NamePairedField(name = name, placeholderName = stage.placeholderName, onNameChange = { name = it })
        Spacer(Modifier.height(Moyu.Space.Xl))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                onConfirmMatch(name)
            },
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.CONFIRM_MATCH),
        ) { Text(stringResource(R.string.verify_confirm_done), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(Moyu.Space.S))
        OutlinedButton(
            onClick = { onVerifyLater(name) },
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.CONFIRM_LATER),
        ) { Text(stringResource(R.string.verify_later), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(Moyu.Space.S))
        TextButton(
            onClick = { showMismatchDialog = true },
            modifier = Modifier.testTag(PairingWizardTestTags.CONFIRM_MISMATCH),
        ) { Text(stringResource(R.string.verify_mismatch), color = moyuColors.statusDanger) }
    }

    if (showMismatchDialog) {
        AlertDialog(
            onDismissRequest = { showMismatchDialog = false },
            title = { Text(stringResource(R.string.verify_mismatch_title)) },
            text = { Text(stringResource(R.string.verify_mismatch_body)) },
            confirmButton = {
                TextButton(onClick = { showMismatchDialog = false; onRejectMismatch() }) {
                    Text(stringResource(R.string.verify_mismatch_confirm), color = moyuColors.statusDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMismatchDialog = false }) { Text(stringResource(R.string.verify_mismatch_dismiss)) }
            },
        )
    }
}

/**
 * The receiver-annotation name field: a LOCAL name only this user sees — the wire never carries a
 * self-asserted name. Purely controlled; the name is applied by "They match" / "Verify later".
 * Leaving it blank keeps the placeholder name.
 */
@Composable
internal fun NamePairedField(name: String, placeholderName: String, onNameChange: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.verify_name_label),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.S))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            placeholder = { Text(placeholderName) },
            shape = RoundedCornerShape(Moyu.Radius.Input),
            modifier = Modifier.fillMaxWidth().testTag(PairingWizardTestTags.NAME_FIELD),
        )
    }
}

/** Failure step: explanation + retry / back. */
@Composable
private fun FailedAct(stage: WizardStage.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(stage.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.statusDanger,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(PairingWizardTestTags.FAILED_MESSAGE),
        )
        Spacer(Modifier.height(Moyu.Space.Xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S)) {
            // Retrying a "record is gone" failure would land on the same page, so only "Back" then.
            if (stage.retryable) {
                Button(onClick = onRetry, modifier = Modifier.testTag(PairingWizardTestTags.FAILED_RETRY)) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag(PairingWizardTestTags.FAILED_BACK)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

/**
 * The localized text version of a pairing code (what "Copy" puts on the clipboard): the header, which
 * ends with the pairing link (`PairingLink.make(shareSite, wire)`) for invite and reply alike.
 * Both headers start with a lock line.
 */
internal fun pairingShareText(context: Context, shareSite: String, wire: String, isResponse: Boolean): String {
    val link = PairingLink.make(shareSite, wire)
    val header = context.getString(
        if (isResponse) R.string.pairing_share_header_response else R.string.pairing_share_header_invite,
        link,
    )
    return PairingShareText.compose(header)
}

/**
 * Opens the system share sheet with the pairing card PNG (no chooser title). Choosing a target fires
 * [PairingShareReceiver], which marks the [kind] / [id] shared and lets an open wizard move on.
 * Renders and writes the PNG off the main thread; if that fails the text version is shared instead.
 */
suspend fun launchPairingShare(
    context: Context,
    shareSite: String,
    wire: String,
    isResponse: Boolean,
    name: String,
    kind: String,
    id: String,
) {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val card = PairingCard.render(
                context,
                if (isResponse) PairingCard.Kind.RESPONSE else PairingCard.Kind.INVITE,
                name,
                PairingLink.make(shareSite, wire),
            )
            PairingCardShare.prepare(context, card)
        }.getOrNull()
    }
    val send = if (uri != null) {
        PairingCardShare.intent(context, uri)
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pairingShareText(context, shareSite, wire, isResponse))
        }
    }
    context.startActivity(
        Intent.createChooser(send, null, PairingShareReceiver.callback(context, kind, id))
            .excludingOwnShareTarget(context),
    )
    AppClipboard.markConsumedOnNextFocus() // after launch succeeded: a throwing launch must not leave the flag set
}
