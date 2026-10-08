package app.chencang.android.update

import android.content.Context
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.config.RefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Play channel: the store handles app updates, so no in-app updater — but the signed config
 * (relay hosts, share site) still has to be refreshed when the app comes to the foreground.
 */
class PlayUpdateController(
    private val refresh: suspend (force: Boolean) -> RefreshResult,
    private val scope: CoroutineScope,
) : UpdateController by NoUpdateController() {
    override fun onForeground() {
        scope.launch { refresh(false) }
    }
}

object UpdateControllers {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, locator: CcServiceLocator): UpdateController {
        val repo = locator.configRepository
        return PlayUpdateController(
            refresh = { force -> repo.refresh(force) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
