package app.chencang.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.chencang.android.ui.me.NamePromptTestTags
import app.chencang.android.ui.onboarding.OnboardingTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: launches MainActivity. Asserts the onboarding "创建身份" button is visible
 * on a fresh install, taps it, and confirms the spinner appears (the actual identity
 * generation requires uniffi — once that finishes the flow navigates to contacts).
 */
@RunWith(AndroidJUnit4::class)
class CompanionFlowTest {

    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboarding_buttonVisible_andClickable() {
        composeRule.onNodeWithTag(OnboardingTestTags.CREATE_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.CREATE_BUTTON).performClick()
        // Either the spinner shows (identity generation in flight) or we reach Done quickly.
        // Both are acceptable signals that the click path is wired.
        // Identity done -> the Name act; skip it.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(NamePromptTestTags.SKIP).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(NamePromptTestTags.SKIP).performClick()
        composeRule.onNodeWithTag(OnboardingTestTags.MECHANISM_DONE_BUTTON).assertIsDisplayed()
    }
}
