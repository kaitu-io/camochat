package app.chencang.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
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
        super.onCreate(savedInstanceState)
        val locator = CcServiceLocator.from(this)
        updates = updateController(locator)

        // Intake entries hand a request over in extras (consumed on read).
        // Without an identity the app runs onboarding first, then the request.
        val launch = launchRequestFrom(intent)
        val start = startDestination(locator.identityStore.hasIdentity())

        enableEdgeToEdge()
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

    private companion object {
        // One controller per process: download state must survive Activity recreation.
        @Volatile private var shared: UpdateController? = null

        fun MainActivity.updateController(locator: CcServiceLocator): UpdateController =
            shared ?: synchronized(MainActivity::class.java) {
                shared ?: UpdateControllers.create(applicationContext, locator).also { shared = it }
            }
    }
}
