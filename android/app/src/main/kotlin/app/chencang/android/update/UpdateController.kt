package app.chencang.android.update

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class DownloadFailure { NETWORK, CHECKSUM, STORAGE }

enum class ManualCheckResult { UP_TO_DATE, UPDATE_AVAILABLE, FAILED }

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Prompt(val decision: UpdateDecision) : UpdateUiState
    data class Downloading(val decision: UpdateDecision, val done: Long, val total: Long) : UpdateUiState
    data class ReadyToInstall(val decision: UpdateDecision, val file: File) : UpdateUiState
    data class Failed(val decision: UpdateDecision, val reason: DownloadFailure) : UpdateUiState
}

interface UpdateController {
    val state: StateFlow<UpdateUiState>
    val supportsManualCheck: Boolean
    fun onForeground()
    suspend fun checkNow(): ManualCheckResult
    fun snooze()
    fun dismissOptional()
    fun startDownload()
    fun cancelDownload()
    fun install(activity: Activity)

    /** True when [install] would first have to send the user to the unknown-sources permission page. */
    fun needsInstallPermission(context: Context): Boolean

    /**
     * One-shot: true exactly once after a download the user asked for has finished, so the host
     * opens the installer once (not again on Activity recreation or recomposition).
     */
    fun consumeInstallRequest(): Boolean
}

/** Play channel: store handles updates; nothing to do here. */
class NoUpdateController : UpdateController {
    override val state: StateFlow<UpdateUiState> = MutableStateFlow(UpdateUiState.Idle)
    override val supportsManualCheck = false
    override fun onForeground() = Unit
    override suspend fun checkNow() = ManualCheckResult.UP_TO_DATE
    override fun snooze() = Unit
    override fun dismissOptional() = Unit
    override fun startDownload() = Unit
    override fun cancelDownload() = Unit
    override fun install(activity: Activity) = Unit
    override fun needsInstallPermission(context: Context) = false
    override fun consumeInstallRequest() = false
}
