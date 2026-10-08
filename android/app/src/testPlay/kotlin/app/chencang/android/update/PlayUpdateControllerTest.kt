package app.chencang.android.update

import app.chencang.shared.config.RefreshResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayUpdateControllerTest {
    @Test fun onForegroundTriggersConfigRefresh() {
        val forces = mutableListOf<Boolean>()
        val c = PlayUpdateController(
            refresh = { force -> forces += force; RefreshResult.UNCHANGED },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        )
        c.onForeground()
        assertThat(forces).containsExactly(false)
        // Play never shows an in-app update.
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
        assertThat(c.supportsManualCheck).isFalse()
    }
}
