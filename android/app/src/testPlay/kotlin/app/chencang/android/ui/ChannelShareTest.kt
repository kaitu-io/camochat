package app.chencang.android.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import app.chencang.android.ChannelFeatures
import app.chencang.android.ui.about.AboutScreen
import app.chencang.android.ui.about.AboutTestTags
import app.chencang.android.ui.me.MeContent
import app.chencang.android.ui.settings.SettingsSections
import app.chencang.android.ui.settings.SettingsTestTags
import app.chencang.android.ui.share.InviteInstallSheet
import app.chencang.android.ui.share.InviteInstallTestTags
import app.chencang.design.MoyuTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Play flavor: APK / zip self-share is hidden everywhere; only the download link remains. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChannelShareTest {
    @get:Rule val compose = createComposeRule()

    @Test fun flag() {
        assertThat(ChannelFeatures.canShareApk).isEqualTo(false)
    }

    @Test fun settingsShareAppRow() {
        compose.setContent {
            MoyuTheme {
                MeContent(
                    contentPadding = PaddingValues(),
                    profile = {},
                    settings = {
                        SettingsSections(
                            summaryPrivacy = false, onSummaryPrivacyChange = {}, onDeleteAccount = {},
                            versionName = "1", onOpenSource = {},
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag(SettingsTestTags.ABOUT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.SHARE_APP).assertDoesNotExist()
    }

    @Test fun aboutShareButton() {
        compose.setContent {
            MoyuTheme { AboutScreen(site = "https://s.test/", versionName = "1", onShareApp = {}, onOpenSource = {}, onOpenSite = {}, onBack = {}) }
        }
        compose.onNodeWithTag(AboutTestTags.SOURCE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AboutTestTags.SHARE_APP).assertDoesNotExist()
    }

    @Test fun inviteSheetApkOptions() {
        compose.setContent {
            MoyuTheme { InviteInstallSheet(site = "https://s.test/", onShareLink = {}, onShareApk = {}, onDismiss = {}) }
        }
        compose.onNodeWithTag(InviteInstallTestTags.SEND_LINK).assertIsDisplayed()
        compose.onNodeWithTag(InviteInstallTestTags.SEND_APK).assertDoesNotExist()
        compose.onNodeWithTag(InviteInstallTestTags.SEND_ZIP).assertDoesNotExist()
    }
}
