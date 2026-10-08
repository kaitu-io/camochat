package app.chencang.android.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.config.LatestApk
import java.util.Locale

object UpdateTestTags {
    const val FORCED = "update_forced"
    const val OPTIONAL = "update_optional"
    const val PROGRESS = "update_progress"
}

/** Release notes in the system language (`zh*` -> `zh`, else `en`), falling back to the other one. */
fun pickNotes(notes: Map<String, String>, languageTag: String): String? {
    val (first, second) = if (languageTag.startsWith("zh")) "zh" to "en" else "en" to "zh"
    return notes[first]?.takeIf { it.isNotBlank() } ?: notes[second]?.takeIf { it.isNotBlank() }
}

private fun UpdateDecision.apk(): LatestApk? = when (this) {
    is UpdateDecision.Forced -> apk
    is UpdateDecision.Optional -> apk
    UpdateDecision.None -> null
}

private fun UpdateUiState.decision(): UpdateDecision = when (this) {
    UpdateUiState.Idle -> UpdateDecision.None
    is UpdateUiState.Prompt -> decision
    is UpdateUiState.Downloading -> decision
    is UpdateUiState.ReadyToInstall -> decision
    is UpdateUiState.Failed -> decision
}

/** True while the forced-update page covers the app. */
fun UpdateUiState.forcedBlocking(): Boolean = decision() is UpdateDecision.Forced

@Composable
fun UpdatePrompt(
    state: UpdateUiState,
    notes: (LatestApk) -> String?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val decision = state.decision()
    val apk = decision.apk() ?: return
    val notesText = notes(apk)
    when (decision) {
        is UpdateDecision.Forced -> {
            BackHandler {}
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(moyuColors.surfaceBase)
                    // Modal: swallow every touch so nothing underneath can be reached.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                    .padding(Moyu.Space.L)
                    .testTag(UpdateTestTags.FORCED),
                verticalArrangement = Arrangement.spacedBy(Moyu.Space.M, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.update_forced_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = moyuColors.textPrimary,
                )
                Text(
                    stringResource(R.string.update_forced_body, apk.versionName),
                    color = moyuColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                if (notesText != null) Text(notesText, color = moyuColors.textSecondary, textAlign = TextAlign.Center)
                UpdateStatus(state, onUpdate)
                if (state is UpdateUiState.Prompt || state is UpdateUiState.ReadyToInstall) {
                    Button(
                        onClick = if (state is UpdateUiState.Prompt) onUpdate else onInstall,
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text(stringResource(R.string.update_action_update)) }
                }
            }
        }
        is UpdateDecision.Optional -> {
            // Only an in-flight download is "cancelled"; a finished one is closed like the prompt (Later = snooze, .apk kept).
            val downloading = state is UpdateUiState.Downloading
            AlertDialog(
                modifier = Modifier.testTag(UpdateTestTags.OPTIONAL),
                onDismissRequest = if (downloading) onCancel else onLater,
                containerColor = moyuColors.surfaceRaised,
                title = { Text(stringResource(R.string.update_optional_title, apk.versionName)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Moyu.Space.M)) {
                        if (notesText != null) Text(notesText)
                        UpdateStatus(state, onUpdate)
                    }
                },
                confirmButton = {
                    if (state is UpdateUiState.ReadyToInstall) {
                        TextButton(onClick = onInstall) { Text(stringResource(R.string.update_action_update)) }
                    } else if (state is UpdateUiState.Prompt || state is UpdateUiState.Failed) {
                        TextButton(onClick = onUpdate) {
                            Text(
                                stringResource(
                                    if (state is UpdateUiState.Failed) R.string.update_action_retry
                                    else R.string.update_action_update,
                                ),
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = if (downloading) onCancel else onLater) {
                        Text(stringResource(if (downloading) R.string.update_action_cancel else R.string.update_action_later))
                    }
                },
            )
        }
        UpdateDecision.None -> Unit
    }
}

/** Progress or failure line; Retry for the forced page is shown here, the optional dialog has its own button. */
@Composable
private fun UpdateStatus(state: UpdateUiState, onRetry: () -> Unit) {
    when (state) {
        is UpdateUiState.Downloading -> {
            val percent = if (state.total > 0) (state.done * 100 / state.total).toInt().coerceIn(0, 100) else 0
            DownloadProgress(percent)
        }
        is UpdateUiState.ReadyToInstall -> DownloadProgress(100)
        is UpdateUiState.Failed -> {
            Text(
                stringResource(
                    when (state.reason) {
                        DownloadFailure.NETWORK -> R.string.update_failed_network
                        DownloadFailure.CHECKSUM -> R.string.update_failed_checksum
                        DownloadFailure.STORAGE -> R.string.update_failed_storage
                    },
                ),
                color = moyuColors.statusDanger,
            )
            if (state.decision is UpdateDecision.Forced) {
                Button(onClick = onRetry) { Text(stringResource(R.string.update_action_retry)) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun DownloadProgress(percent: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(UpdateTestTags.PROGRESS),
        verticalArrangement = Arrangement.spacedBy(Moyu.Space.S),
    ) {
        Text(stringResource(R.string.update_downloading, percent.toString()), color = moyuColors.textSecondary)
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = moyuColors.accentPrimary,
            trackColor = moyuColors.borderHairline,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Re-open the installer when the Activity comes back to the foreground? Only for a forced update
 * (the user cannot use the app anyway); an optional one waits for the user's tap on Update.
 */
fun reinstallOnResume(state: UpdateUiState, permissionHintShowing: Boolean): Boolean =
    state is UpdateUiState.ReadyToInstall && state.decision is UpdateDecision.Forced && !permissionHintShowing

/**
 * Wires an [UpdateController] to [UpdatePrompt]: Later = snooze, install permission hint dialog,
 * a one-shot auto-install when a requested download finishes, and (forced only) a retry of the
 * install when the user returns from the installer / permission page.
 */
@Composable
fun UpdateHost(controller: UpdateController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var permissionHint by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun tryInstall() {
        val activity = context.findActivity() ?: return
        if (controller.needsInstallPermission(context)) permissionHint = true else controller.install(activity)
    }

    // Download finished after the user's tap: open the installer once. The token lives in the
    // controller, so Activity recreation does not re-fire it.
    val readyFile = (state as? UpdateUiState.ReadyToInstall)?.file
    LaunchedEffect(readyFile) {
        if (readyFile != null && controller.consumeInstallRequest()) tryInstall()
    }

    // Forced only: back from the installer / permission page without updating -> prompt again.
    LaunchedEffect(resumes) {
        if (resumes > 1 && reinstallOnResume(state, permissionHint)) tryInstall()
    }

    UpdatePrompt(
        state = state,
        notes = { pickNotes(it.notes, Locale.getDefault().language) },
        onUpdate = controller::startDownload,
        onLater = controller::snooze,
        onCancel = controller::cancelDownload,
        onInstall = ::tryInstall,
    )

    if (permissionHint) {
        val forced = state.decision() is UpdateDecision.Forced
        AlertDialog(
            onDismissRequest = { if (!forced) permissionHint = false },
            properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced),
            containerColor = moyuColors.surfaceRaised,
            text = { Text(stringResource(R.string.update_permission_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    permissionHint = false
                    context.findActivity()?.let(controller::install)
                }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}
