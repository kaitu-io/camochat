package app.chencang.android.ui.onboarding

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.R
import app.chencang.design.MoyuTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OnboardingTermsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `tapping the sentence toggles the checkbox and the link opens terms`() {
        var checked by mutableStateOf(false)
        var opened = 0
        compose.setContent {
            MoyuTheme {
                BrandAct(OnboardingState.NeedsIdentity, checked, { checked = it }, { opened++ }, {})
            }
        }
        val label = ApplicationProvider.getApplicationContext<Application>().getString(R.string.onboarding_terms_agree)
        compose.onNodeWithText(label).performClick()
        assertThat(checked).isTrue()
        compose.onNodeWithTag(OnboardingTestTags.TERMS_CHECKBOX).assertIsOn()
        compose.onNodeWithTag(OnboardingTestTags.TERMS_LINK).performClick()
        assertThat(opened).isEqualTo(1)
        assertThat(checked).isTrue()
    }
}
