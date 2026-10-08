package app.chencang.android.ui.chat

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.chencang.android.ui.main.FakeProbe
import app.chencang.android.ui.main.MainTestTags
import app.chencang.android.ui.main.PasteBarState
import app.chencang.design.MoyuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ThreadBottomTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `paste bar sits above the composer and paste fires`() {
        val probe = FakeProbe().apply { text = true; stamp = 7L }
        val state = PasteBarState(probe).also { it.onFocusGained() }
        var pasted = 0
        compose.setContent {
            MoyuTheme {
                ThreadBottom(state, { pasted++ }) { Text("composer", Modifier.testTag("composer")) }
            }
        }
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertIsDisplayed()
        val barBottom = compose.onNodeWithTag(MainTestTags.PASTE_BAR).fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.top
        assertTrue(barBottom <= composerTop)
        compose.onNodeWithTag(MainTestTags.PASTE_BAR_PASTE).performClick()
        assertEquals(1, pasted)
    }

    @Test
    fun `no bar when not visible`() {
        compose.setContent {
            MoyuTheme { ThreadBottom(PasteBarState(FakeProbe()), {}) { Text("composer") } }
        }
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertDoesNotExist()
    }
}
