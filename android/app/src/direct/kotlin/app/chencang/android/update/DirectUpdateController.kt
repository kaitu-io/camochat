package app.chencang.android.update

import android.app.Activity
import android.content.Context
import app.chencang.shared.config.AppConfig
import app.chencang.shared.config.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Direct channel: two-level update decision (optional / forced) plus in-app APK download. */
class DirectUpdateController(
    private val currentVersionCode: Int,
    private val config: StateFlow<AppConfig>,
    private val refresh: suspend (force: Boolean) -> RefreshResult,
    private val prefs: UpdatePrefs,
    private val downloader: ApkDownloader,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) : UpdateController {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    override val state: StateFlow<UpdateUiState> = _state
    override val supportsManualCheck = true

    private var job: Job? = null

    /** Set when a requested download finishes; consumed once by the host to open the installer. */
    private val installRequested = AtomicBoolean(false)

    /** Cancel flag of the download that is current; a stale job never writes state. */
    @Volatile private var active: AtomicBoolean? = null

    init {
        scope.launch {
            val latest = config.value.android?.latest?.versionCode
            downloader.cleanup(latest?.takeIf { currentVersionCode < it })
        }
    }

    private fun decide(ignoreSnooze: Boolean) =
        decideUpdate(currentVersionCode, config.value.android, prefs.snooze(), now(), ignoreSnooze)

    private fun apply(decision: UpdateDecision) {
        _state.update { cur ->
            if (cur is UpdateUiState.Downloading || cur is UpdateUiState.ReadyToInstall) cur
            else if (decision is UpdateDecision.None) UpdateUiState.Idle
            else UpdateUiState.Prompt(decision)
        }
    }

    override fun onForeground() {
        // Cached decision first: a forced user is blocked at once, not after the (up to 15 s) refresh.
        apply(decide(ignoreSnooze = false))
        scope.launch {
            refresh(false)
            apply(decide(ignoreSnooze = false))
        }
    }

    override suspend fun checkNow(): ManualCheckResult {
        val r = refresh(true)
        val decision = decide(ignoreSnooze = true)
        if (decision is UpdateDecision.None) return if (r == RefreshResult.FAILED) ManualCheckResult.FAILED else ManualCheckResult.UP_TO_DATE
        apply(decision)
        return ManualCheckResult.UPDATE_AVAILABLE
    }

    /** Decision of a state the optional dialog can be closed from (the downloaded .apk is kept). */
    private fun dismissableOptional(s: UpdateUiState): UpdateDecision.Optional? = when (s) {
        is UpdateUiState.Prompt -> s.decision
        is UpdateUiState.Failed -> s.decision
        is UpdateUiState.ReadyToInstall -> s.decision
        else -> null
    } as? UpdateDecision.Optional

    override fun snooze() {
        val d = dismissableOptional(_state.value) ?: return
        prefs.setSnooze(Snooze(d.apk.versionCode, now()))
        installRequested.set(false)
        _state.value = UpdateUiState.Idle
    }

    override fun dismissOptional() {
        dismissableOptional(_state.value) ?: return
        installRequested.set(false)
        _state.value = UpdateUiState.Idle
    }

    override fun startDownload() {
        val before = _state.value
        val decision = when (before) {
            is UpdateUiState.Prompt -> before.decision
            is UpdateUiState.Failed -> before.decision
            else -> return
        }
        val apk = decision.apkOrNull() ?: return
        if (!_state.compareAndSet(before, UpdateUiState.Downloading(decision, 0, apk.size))) return
        // Forced retry: a corrected config (new checksum / mirrors) may have been published meanwhile.
        val refreshFirst = before is UpdateUiState.Failed && decision is UpdateDecision.Forced
        val flag = AtomicBoolean(false)
        active = flag
        job?.cancel()
        job = scope.launch {
            fun current() = active === flag
            var target = decision
            var targetApk = apk
            try {
                if (refreshFirst) {
                    refresh(true)
                    val fresh = decide(ignoreSnooze = true)
                    val freshApk = fresh.apkOrNull()
                    if (freshApk == null) {
                        if (current() && !flag.get()) _state.value = UpdateUiState.Idle
                        return@launch
                    }
                    target = fresh
                    targetApk = freshApk
                    if (current() && !flag.get()) _state.value = UpdateUiState.Downloading(target, 0, targetApk.size)
                }
                val file = downloader.download(
                    targetApk,
                    onProgress = { done, total ->
                        if (current() && !flag.get()) _state.value = UpdateUiState.Downloading(target, done, total)
                    },
                    isCancelled = { flag.get() || !current() },
                )
                if (current()) {
                    installRequested.set(true)
                    _state.value = UpdateUiState.ReadyToInstall(target, file)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current() && !flag.get()) {
                    val reason = (e as? ApkDownloadException)?.failure ?: DownloadFailure.NETWORK
                    _state.value = UpdateUiState.Failed(target, reason)
                }
            }
        }
    }

    override fun cancelDownload() {
        val s = _state.value as? UpdateUiState.Downloading ?: return
        active?.set(true)
        active = null
        job?.cancel()
        _state.value = UpdateUiState.Prompt(s.decision)
    }

    override fun install(activity: Activity) {
        val s = _state.value as? UpdateUiState.ReadyToInstall ?: return
        if (ApkInstaller.canInstall(activity)) {
            activity.startActivity(ApkInstaller.installIntent(activity, s.file))
        } else {
            activity.startActivity(ApkInstaller.permissionIntent(activity))
        }
    }

    override fun needsInstallPermission(context: Context): Boolean = !ApkInstaller.canInstall(context)

    override fun consumeInstallRequest(): Boolean = installRequested.getAndSet(false)
}

private fun UpdateDecision.apkOrNull() = when (this) {
    is UpdateDecision.Optional -> apk
    is UpdateDecision.Forced -> apk
    UpdateDecision.None -> null
}
