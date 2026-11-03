package app.chencang.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.chencang.android.ui.me.MeContent
import app.chencang.design.MoyuTheme
import app.chencang.shared.model.Contact
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BlockedContactsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun c(fp: String) = Contact(fp, fp, "name-$fp", 0L, blocked = true)

    @Test
    fun `empty list says so`() {
        compose.setContent { MoyuTheme { BlockedContactsScreen(emptyList(), {}, {}, {}) } }
        compose.onNodeWithTag(BlockedContactsTestTags.EMPTY).assertExists()
    }

    @Test
    fun `row opens the contact and its button unblocks`() {
        val opened = mutableListOf<String>()
        val unblocked = mutableListOf<String>()
        compose.setContent {
            MoyuTheme {
                BlockedContactsScreen(listOf(c("aa"), c("bb")), { opened += it }, { unblocked += it }, {})
            }
        }
        compose.onNodeWithTag(BlockedContactsTestTags.row("aa")).performClick()
        compose.onNodeWithTag(BlockedContactsTestTags.unblock("bb")).performClick()
        assertThat(opened).containsExactly("aa")
        assertThat(unblocked).containsExactly("bb")
    }

    @Test
    fun `settings privacy group has the blocked contacts row`() {
        var opened = 0
        compose.setContent {
            MoyuTheme {
                MeContent(
                    contentPadding = PaddingValues(),
                    profile = {},
                    settings = {
                        SettingsSections(
                            summaryPrivacy = false,
                            onSummaryPrivacyChange = {},
                            onDeleteAccount = {},
                            versionName = "1.0.3",
                            onOpenSource = {},
                            onOpenBlockedContacts = { opened++ },
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag(SettingsTestTags.BLOCKED_CONTACTS).performScrollTo().performClick()
        assertThat(opened).isEqualTo(1)
    }
}
