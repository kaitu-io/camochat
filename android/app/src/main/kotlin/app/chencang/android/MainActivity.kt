package app.chencang.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.chencang.android.ui.splash.SplashOverlay
import app.chencang.android.ui.splash.SplashVariant
import app.chencang.android.media.UploadScheduler
import app.chencang.android.navigation.CcNavGraph
import app.chencang.android.navigation.LaunchRequest
import app.chencang.android.navigation.launchRequestFrom
import app.chencang.android.navigation.startDestination
import app.chencang.design.MoyuTheme
import app.chencang.design.moyuColors
import app.chencang.android.update.UpdateController
import app.chencang.android.update.UpdateControllers
import app.chencang.android.update.UpdateHost
import app.chencang.shared.CcServiceLocator
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.chencang.android.update.forcedBlocking

class MainActivity : ComponentActivity() {

    private lateinit var updates: UpdateController

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val splashVariant = splashVariantFor(savedInstanceState)
        // 开屏层第一帧与系统启动页一模一样：系统那层立即撤掉，不再叠一段默认的退场动画。
        if (splashVariant != null) systemSplash.setOnExitAnimationListener { it.remove() }
        val locator = CcServiceLocator.from(this)
        updates = updateController(locator)

        // Intake entries hand a request over in extras (consumed on read).
        // Without an identity the app runs onboarding first, then the request.
        val launch = launchRequestFrom(intent)
        val start = startDestination(locator.identityStore.hasIdentity())

        // 开屏层是深色底：播放期间系统栏按深色背景配色（浅色图标、导航栏不加浅色蒙层），播完换回跟随主题。
        if (splashVariant != null) {
            val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        } else {
            enableEdgeToEdge()
        }
        setContent {
            MoyuTheme {
                Surface(color = moyuColors.surfaceBase) {
                    Box {
                        val updateState by updates.state.collectAsState()
                        val forced = updateState.forcedBlocking()
                        // While the forced page is up, TalkBack must not reach the app below it.
                        Box(if (forced) Modifier.clearAndSetSemantics {} else Modifier) {
                            CcNavGraph(
                                locator,
                                startDestination = start,
                                launch = launch,
                                updates = updates,
                            )
                        }
                        UpdateHost(updates)
                        var splash by remember { mutableStateOf(splashVariant) }
                        splash?.let {
                            SplashOverlay(it, onFinished = {
                                splash = null
                                enableEdgeToEdge()
                            })
                        }
                    }
                }
            }
        }
    }

    /** 回到前台即自愈（spec 2026-09-30 §1.2）：已分享但没传完的消息重新交给上传引擎，「对方还看不到」多半自己好。 */
    override fun onStart() {
        super.onStart()
        UploadScheduler.healInBackground(this)
        updates.onForeground()
    }

    /**
     * 只在从桌面冷启动时播开屏：分享 / 划词 / 链接进来的不播，回到前台、旋转重建、
     * 进程被杀后恢复（savedInstanceState 非空）都不播；同一进程里只播一次。
     * 装好后第一次播完整版（开始播就记下），之后都播短版。
     */
    private fun splashVariantFor(savedInstanceState: Bundle?): SplashVariant? {
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (savedInstanceState != null || !fromLauncher || splashPlayed) return null
        splashPlayed = true
        val prefs = getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FULL_SPLASH_SEEN, false)) return SplashVariant.SHORT
        prefs.edit().putBoolean(KEY_FULL_SPLASH_SEEN, true).apply()
        return SplashVariant.FULL
    }

    private companion object {
        const val SPLASH_PREFS = "splash"
        const val KEY_FULL_SPLASH_SEEN = "full_seen"

        /** 进程级：Activity 被返回键关掉、再从桌面点开时进程还在，那不算冷启动。 */
        var splashPlayed = false

        // One controller per process: download state must survive Activity recreation.
        @Volatile private var shared: UpdateController? = null

        fun MainActivity.updateController(locator: CcServiceLocator): UpdateController =
            shared ?: synchronized(MainActivity::class.java) {
                shared ?: UpdateControllers.create(applicationContext, locator).also { shared = it }
            }
    }
}
