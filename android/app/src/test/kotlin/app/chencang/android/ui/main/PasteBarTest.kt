package app.chencang.android.ui.main

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import app.chencang.android.clipboard.AppClipboard
import app.chencang.design.MoyuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PasteBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `no judgement while unfocused, judged once focus arrives`() {
        val probe = FakeProbe().apply { text = true; stamp = 9L }
        var focused by mutableStateOf(false)
        var state: PasteBarState? = null
        compose.setContent {
            MoyuTheme {
                state = rememberPasteBarState(probe = probe, focused = focused)
                PasteBarHost(state!!, onPaste = {})
            }
        }
        compose.waitForIdle()
        assertFalse(state!!.visible)
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertDoesNotExist()
        focused = true
        compose.waitForIdle()
        assertTrue(state!!.visible)
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertIsDisplayed()
    }

    @Test
    fun `pending focus mark is consumed before judging`() {
        val probe = FakeProbe().apply { text = true; stamp = 9L; pendingMarks = 1 }
        val state = PasteBarState(probe)
        state.onFocusGained()
        assertFalse(state.visible)
        assertEquals(9L, probe.consumed)
    }

    @Test
    fun `first install with text and no consumed record shows the bar`() {
        val state = PasteBarState(FakeProbe().apply { text = true; stamp = 3L })
        state.onFocusGained()
        assertTrue(state.visible)
    }

    @Test
    fun `host click paths consume`() {
        val probe = FakeProbe().apply { text = true; stamp = 4L }
        val state = PasteBarState(probe).also { it.onFocusGained() }
        var pasted = 0
        compose.setContent { MoyuTheme { PasteBarHost(state, onPaste = { pasted++ }) } }
        compose.onNodeWithTag(MainTestTags.PASTE_BAR_PASTE).performClick()
        assertEquals(1, pasted)
        assertEquals(4L, probe.consumed)
        assertFalse(state.visible)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Share sheet launched (mark set) while resumed and focused, focus lost; [leave] moves the
     *  lifecycle while away; then focus comes back. Returns the state. */
    private fun shareSheetRoundTrip(probe: FakeProbe, leave: (LifecycleRegistry) -> Unit): PasteBarState {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var focused by mutableStateOf(true)
        var state: PasteBarState? = null
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MoyuTheme { state = rememberPasteBarState(probe = probe, focused = focused) }
            }
        }
        compose.waitForIdle()
        probe.pendingMarks = 1 // AppClipboard.markConsumedOnNextFocus() at chooser launch
        focused = false // chooser on top
        compose.waitForIdle()
        compose.runOnIdle { leave(owner.registry) }
        probe.text = true; probe.stamp = 11L // something was copied meanwhile
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        focused = true
        compose.waitForIdle()
        return state!!
    }

    @Test
    fun `pause only (Copy chosen in the chooser) then focus consumes the copy`() {
        val probe = FakeProbe()
        val state = shareSheetRoundTrip(probe) { it.currentState = Lifecycle.State.STARTED }
        assertFalse(state.visible)
        assertEquals(11L, probe.consumed)
    }

    @Test
    fun `stopped (left for the chat app) then focus does not consume the peer's reply`() {
        val probe = FakeProbe()
        val state = shareSheetRoundTrip(probe) { it.currentState = Lifecycle.State.CREATED }
        assertTrue(state.visible)
        assertEquals(null, probe.consumed)
    }

    @Test
    fun `an app clipboard write hides a visible bar at once`() {
        val probe = FakeProbe().apply { text = true; stamp = 5L }
        var state: PasteBarState? = null
        compose.setContent { MoyuTheme { state = rememberPasteBarState(probe = probe, focused = true) } }
        compose.waitForIdle()
        assertTrue(state!!.visible)
        compose.runOnIdle { AppClipboard.write(ApplicationProvider.getApplicationContext(), "l", "mine") }
        compose.waitForIdle()
        assertFalse(state!!.visible)
    }
}
