package app.chencang.android.update

import android.content.Context
import app.chencang.android.BuildConfig
import app.chencang.shared.CcServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

object UpdateControllers {
    fun create(context: Context, locator: CcServiceLocator): UpdateController {
        val app = context.applicationContext
        val repo = locator.configRepository
        return DirectUpdateController(
            currentVersionCode = BuildConfig.VERSION_CODE,
            config = repo.config,
            refresh = { force -> repo.refresh(force) },
            prefs = UpdatePrefs(app.getSharedPreferences(UpdatePrefs.NAME, Context.MODE_PRIVATE)),
            downloader = ApkDownloader(File(app.filesDir, "update")),
            now = System::currentTimeMillis,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
