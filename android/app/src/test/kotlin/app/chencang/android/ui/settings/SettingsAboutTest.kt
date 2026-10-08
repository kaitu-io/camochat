package app.chencang.android.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.chencang.android.ui.me.MeContent
import app.chencang.design.MoyuTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsAboutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `about group shows the version and opens the server source on tap only`() {
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
                            versionName = "9.9.9-test",
                            onOpenSource = { opened++ },
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag(SettingsTestTags.VERSION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("9.9.9-test").assertIsDisplayed()
        assertThat(opened).isEqualTo(0)

        val relayTitle = ApplicationProvider.getApplicationContext<Context>().getString(R.string.settings_media_relay)
        compose.onNodeWithText(relayTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.SERVER_SOURCE).performScrollTo().performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `source link is the published relay source page`() {
        assertThat(AboutLinks.sourceUrl("https://site.test/")).isEqualTo("https://site.test/source")
    }
}
