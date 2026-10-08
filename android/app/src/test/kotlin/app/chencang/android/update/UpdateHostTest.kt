package app.chencang.android.update

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import app.chencang.design.MoyuTheme
import app.chencang.shared.config.LatestApk
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val apk = LatestApk(9, "9.9.9", "00", 200, emptyList())

    private class FakeController(initial: UpdateUiState, var token: Boolean) : UpdateController {
        override val state = MutableStateFlow(initial)
        override val supportsManualCheck = true
        var installs = 0
        override fun onForeground() = Unit
        override suspend fun checkNow() = ManualCheckResult.UP_TO_DATE
        override fun snooze() = Unit
        override fun dismissOptional() = Unit
        override fun startDownload() = Unit
        override fun cancelDownload() = Unit
        override fun install(activity: Activity) { installs++ }
        override fun needsInstallPermission(context: Context) = false
        override fun consumeInstallRequest(): Boolean = token.also { token = false }
    }

    private fun host(c: FakeController) {
        compose.setContent { MoyuTheme { UpdateHost(c) } }
        compose.waitForIdle()
    }

    private fun bounceLifecycle() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun readyAutoInstallsOnceWhenRequested() {
        val c = FakeController(UpdateUiState.ReadyToInstall(UpdateDecision.Optional(apk), File("x.apk")), token = true)
        host(c)
        assertThat(c.installs).isEqualTo(1)
    }

    @Test
    fun readyWithoutRequestDoesNotAutoInstall() {
        val c = FakeController(UpdateUiState.ReadyToInstall(UpdateDecision.Optional(apk), File("x.apk")), token = false)
        host(c)
        assertThat(c.installs).isEqualTo(0)
    }

    @Test
    fun optionalReadyDoesNotRelaunchOnResume() {
        val c = FakeController(UpdateUiState.ReadyToInstall(UpdateDecision.Optional(apk), File("x.apk")), token = false)
        host(c)
        bounceLifecycle()
        bounceLifecycle()
        assertThat(c.installs).isEqualTo(0)
    }

    @Test
    fun forcedReadyRelaunchesOnResume() {
        val c = FakeController(UpdateUiState.ReadyToInstall(UpdateDecision.Forced(apk), File("x.apk")), token = false)
        host(c)
        bounceLifecycle()
        assertThat(c.installs).isEqualTo(1)
    }
}
