package app.chencang.android.ui.contact

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContactContentTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun show(
        resendable: String?,
        onResend: (String) -> Unit = {},
        verified: Boolean = false,
        onConfirm: () -> Unit = {},
    ) = compose.setContent {
        MoyuTheme {
            ContactContent(
                contact = Contact(fingerprintHex = "fp", username = "fp", displayName = "阿青", pairedAt = 0L, verified = verified),
                resendableResponse = resendable,
                onBack = {},
                onMessage = {},
                onResendResponse = onResend,
                onConfirm = onConfirm,
                onRename = {},
                onClearMessages = {},
                onDeleteContact = {},
            )
        }
    }

    @Test
    fun `resend response row exists only when there is a resendable response`() {
        var sent: String? = null
        show("🔒resp", onResend = { sent = it })
        compose.onNodeWithTag(ContactTestTags.RESEND_RESPONSE).assertExists()
        compose.onNodeWithTag(ContactTestTags.RESEND_RESPONSE).performClick()
        assertThat(sent).isEqualTo("🔒resp")
    }

    @Test
    fun `no resend response row without a resendable response`() {
        show(null)
        compose.onNodeWithTag(ContactTestTags.RESEND_RESPONSE).assertDoesNotExist()
    }

    @Test
    fun `unverified shows the confirm button which confirms`() {
        var confirmed = 0
        show(null, onConfirm = { confirmed++ })
        compose.onNodeWithTag(ContactTestTags.CONFIRM).assertTextEquals(str(R.string.verify_confirm_done))
        compose.onNodeWithText(str(R.string.verify_status_unverified)).assertExists()
        compose.onNodeWithTag(ContactTestTags.CONFIRM).performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun `verified shows only the status and no confirm button`() {
        show(null, verified = true)
        compose.onNodeWithTag(ContactTestTags.CONFIRM).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.verify_status_verified)).assertExists()
    }
}
