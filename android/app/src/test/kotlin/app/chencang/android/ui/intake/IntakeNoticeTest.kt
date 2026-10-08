package app.chencang.android.ui.intake

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IntakeNoticeTest {
    @get:Rule val compose = createComposeRule()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()

    private fun show(failure: IntakeFailure) {
        compose.setContent {
            MoyuTheme {
                IntakeNoticeDialog(
                    failure = failure,
                    onAddContact = { calls += "add" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }
    }

    @Test
    fun `no contacts offers add contact`() {
        show(IntakeFailure.NO_CONTACTS)
        compose.onNodeWithText(ctx.getString(R.string.intake_error_no_contacts)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.pairing_add_contact)).performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test
    fun `no contacts ok only dismisses`() {
        show(IntakeFailure.NO_CONTACTS)
        compose.onNodeWithText(ctx.getString(R.string.common_ok)).performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `cannot decrypt only has ok`() {
        show(IntakeFailure.CANNOT_DECRYPT)
        compose.onNodeWithText(ctx.getString(R.string.intake_error_cannot_decrypt)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.common_ok)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.pairing_add_contact)).assertDoesNotExist()
        // 「打开 App」只在 App 外出现；App 内不给。
        compose.onNodeWithText(ctx.getString(R.string.intake_action_open_app)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.common_ok)).performClick()
        assertEquals(listOf("dismiss"), calls)
    }
}
