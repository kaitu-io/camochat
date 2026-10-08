package app.chencang.android

import android.app.Application
import androidx.work.Configuration
import app.chencang.android.media.UploadScheduler
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.LegacyResidueCleaner

/**
 * Application entry. Holds [CcServiceLocator] keyed off the application context.
 *
 * WorkManager 走**按需初始化**（[Configuration.Provider]；manifest 里摘掉了默认的 startup 初始化器）：
 * 默认初始化器在 ContentProvider 阶段、也就是本类 `onCreate` 之前就起 WorkManager，进程被杀后拉起的
 * [app.chencang.android.media.UploadWorker] 可能抢在这里装好上传引擎之前去取 [CcServiceLocator]。
 * 按需初始化时 WorkManager 只在第一次 `WorkManager.getInstance` 才起，那一定在本 `onCreate` 之后。
 */
class CcApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        CcServiceLocator.installUploadEngine(UploadScheduler.engine(this))
        CcServiceLocator.from(this)
        instance = this
        LegacyResidueCleaner.runOnce(this)
        // 自愈（spec 2026-09-30 §1.2）：App 启动时把已分享但没传完的消息重新交给上传引擎。
        // 带界面的冷启动紧接着 MainActivity.onStart 还会再自愈一次：enqueueNow 进程内串行、且不动还没跑过的任务，
        // 第二次是空操作（N1）。这里保留是为了不经主界面的启动（「陈仓解密」、WorkManager 拉起进程）。
        UploadScheduler.healInBackground(this)
    }

    companion object {
        @Volatile
        lateinit var instance: CcApp
            private set

        val locator: CcServiceLocator get() = CcServiceLocator.from(instance)
    }
}
