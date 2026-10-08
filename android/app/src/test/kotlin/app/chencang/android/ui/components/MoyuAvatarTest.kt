package app.chencang.android.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.chencang.design.Moyu
import app.chencang.design.MoyuTheme
import app.chencang.shared.profile.AvatarSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 取色算法的用例（表 P）在 `:shared` 的 `AvatarSpecTest`；这里只管组件照 spec 画。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoyuAvatarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `renders the glyph of the spec as is`() {
        compose.setContent {
            MoyuTheme { MoyuAvatar(spec = AvatarSpec(glyph = "👍🏽", paletteIndex = 3), size = Moyu.Size.AvatarList) }
        }
        compose.onNodeWithText("👍🏽").assertIsDisplayed()
    }

    @Test
    fun `every palette index 0 to 7 renders`() {
        compose.setContent {
            MoyuTheme {
                androidx.compose.foundation.layout.Column {
                    repeat(8) { MoyuAvatar(spec = AvatarSpec(glyph = "$it", paletteIndex = it), size = Moyu.Size.AvatarInline) }
                }
            }
        }
        repeat(8) { compose.onNodeWithText("$it").assertIsDisplayed() }
    }
}
