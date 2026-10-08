package app.chencang.android.ui.me

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.TextFieldValue
import app.chencang.design.Moyu
import app.chencang.design.MoyuTheme
import app.chencang.shared.profile.AvatarEditorModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AvatarEditorContentTest {
    @get:Rule val compose = createComposeRule()

    private fun content(color: Int?, picked: MutableList<Int?> = mutableListOf(), done: () -> Unit = {}) =
        compose.setContent {
            MoyuTheme {
                AvatarEditorContent(
                    model = AvatarEditorModel(storedGlyph = "山", storedColor = color, myName = "", myFingerprintHex = null, defaultGlyph = "M"),
                    glyphField = TextFieldValue("山"),
                    onGlyphField = {},
                    onPickColor = { picked += it },
                    onDone = done,
                )
            }
        }

    @Test
    fun `all eight swatches, auto, glyph field and done exist`() {
        content(color = null)
        repeat(8) { compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_PREFIX + it).assertExists() }
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_AUTO).assertExists()
        compose.onNodeWithTag(MeTestTags.AVATAR_GLYPH).assertExists()
        compose.onNodeWithTag(MeTestTags.AVATAR_DONE).assertExists()
    }

    @Test
    fun `only the stored color is selected`() {
        content(color = 3)
        repeat(8) {
            val n = compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_PREFIX + it)
            if (it == 3) n.assertIsSelected() else n.assertIsNotSelected()
        }
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_AUTO).assertIsNotSelected()
    }

    @Test
    fun `auto is selected when no color is stored`() {
        content(color = null)
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_AUTO).assertIsSelected()
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_PREFIX + 0).assertIsNotSelected()
    }

    @Test
    fun `clicks report the right index and null for auto`() {
        val picked = mutableListOf<Int?>()
        content(color = null, picked = picked)
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_PREFIX + 5).performClick()
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_PREFIX + 0).performClick()
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_AUTO).performClick()
        assertEquals(listOf<Int?>(5, 0, null), picked)
    }

    @Test
    fun `touch targets are at least 48dp`() {
        content(color = null)
        repeat(8) {
            compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_PREFIX + it)
                .assertWidthIsAtLeast(Moyu.Size.TouchMin).assertHeightIsAtLeast(Moyu.Size.TouchMin)
        }
        compose.onNodeWithTag(MeTestTags.AVATAR_COLOR_AUTO).assertHeightIsAtLeast(Moyu.Size.TouchMin)
    }

    @Test
    fun `done fires the callback`() {
        var done = 0
        content(color = null, done = { done++ })
        // 面板可滚动：测试视口小，先滚到「完成」。
        compose.onNodeWithTag(MeTestTags.AVATAR_DONE).performScrollTo().performClick()
        assertEquals(1, done)
    }
}
