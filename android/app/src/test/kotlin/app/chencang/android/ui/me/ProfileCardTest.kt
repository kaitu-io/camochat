package app.chencang.android.ui.me

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.R
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.chencang.design.MoyuTheme
import app.chencang.shared.profile.AvatarSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProfileCardTest {
    @get:Rule val compose = createComposeRule()

    private val avatar = AvatarSpec("M", 2)
    private fun str(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun card(loaded: Boolean, name: String, onName: () -> Unit = {}, onAvatar: () -> Unit = {}) =
        compose.setContent {
            MoyuTheme { ProfileCard(loaded = loaded, name = name, avatar = avatar, onEditName = onName, onEditAvatar = onAvatar) }
        }

    @Test
    fun `shows no placeholder before profile is loaded`() {
        card(loaded = false, name = "")
        compose.onAllNodesWithText(str(R.string.me_name_unset), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `shows the unset placeholder when loaded and empty`() {
        card(loaded = true, name = "")
        compose.onNodeWithText(str(R.string.me_name_unset), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `shows name and the shown-when-adding subtitle when set`() {
        card(loaded = true, name = "阿青")
        compose.onNodeWithText("阿青", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.me_name_subtitle), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `avatar and name are two separate click targets`() {
        var nameClicks = 0
        var avatarClicks = 0
        card(loaded = true, name = "阿青", onName = { nameClicks++ }, onAvatar = { avatarClicks++ })
        compose.onNodeWithTag(MeTestTags.AVATAR).performClick()
        compose.onNodeWithTag(MeTestTags.NAME).performClick()
        assertEquals(1, nameClicks)
        assertEquals(1, avatarClicks)
    }

    @Test
    fun `profile card exposes its test tags`() {
        card(loaded = true, name = "阿青")
        compose.onNodeWithTag(MeTestTags.PROFILE).assertExists()
        compose.onNodeWithTag(MeTestTags.AVATAR).assertExists()
        compose.onNodeWithTag(MeTestTags.NAME).assertExists()
    }

    @Test
    fun `avatar and name are not clickable before the profile is loaded`() {
        var clicks = 0
        card(loaded = false, name = "", onName = { clicks++ }, onAvatar = { clicks++ })
        compose.onNodeWithTag(MeTestTags.AVATAR).performClick()
        compose.onNodeWithTag(MeTestTags.NAME).performClick()
        assertEquals(0, clicks)
    }
}
