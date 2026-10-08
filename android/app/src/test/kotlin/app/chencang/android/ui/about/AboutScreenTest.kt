package app.chencang.android.ui.about

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.chencang.design.MoyuTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AboutScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aboutShowsSiteAndVersion() {
        compose.setContent {
            MoyuTheme {
                AboutScreen(
                    site = "https://site.test/",
                    versionName = "9.9.9-test",
                    onShareApp = {},
                    onOpenSource = {},
                    onOpenSite = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("https://site.test/", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("9.9.9-test", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun siteIsTappableAndSourceButtonIsLabelled() {
        var site = 0
        var source = 0
        compose.setContent {
            MoyuTheme {
                AboutScreen(
                    site = "https://site.test/",
                    versionName = "1",
                    onShareApp = {},
                    onOpenSource = { source++ },
                    onOpenSite = { site++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag(AboutTestTags.SITE).performScrollTo().performClick()
        assertThat(site).isEqualTo(1)
        compose.onNodeWithText("View source code").performScrollTo().performClick()
        assertThat(source).isEqualTo(1)
    }

    @Test
    fun privacyPolicyButtonIsLabelledAndTappable() {
        var privacy = 0
        compose.setContent {
            MoyuTheme {
                AboutScreen(
                    site = "https://site.test/",
                    versionName = "1",
                    onShareApp = {},
                    onOpenSource = {},
                    onOpenSite = {},
                    onBack = {},
                    onOpenPrivacy = { privacy++ },
                )
            }
        }
        compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AboutTestTags.PRIVACY_POLICY).performScrollTo().performClick()
        assertThat(privacy).isEqualTo(1)
    }
}
