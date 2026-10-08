package app.chencang.android.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.chencang.design.MoyuTheme
import app.chencang.shared.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ThreadBannerTest {
    @get:Rule val compose = createComposeRule()

    private fun contact(accepted: Boolean, verified: Boolean = false) = Contact(
        fingerprintHex = "fp", username = "u", displayName = "N", pairedAt = 0L, verified = verified,
        acceptedInviteDigest = if (accepted) "d" else null,
    )

    private fun pick(c: Contact, inc: Boolean, out: Boolean, dismissed: Set<String> = emptySet()) =
        ThreadBanners.pick(c, inc, out, dismissed)

    @Test
    fun `acceptor with nothing received waits, regardless of verification`() {
        assertEquals(ThreadBanner.WAITING_PEER, pick(contact(accepted = true), inc = false, out = true))
        assertEquals(ThreadBanner.WAITING_PEER, pick(contact(accepted = true, verified = true), inc = false, out = false))
    }

    @Test
    fun `acceptor who has heard from the peer falls to verify-later`() {
        assertEquals(ThreadBanner.VERIFY_LATER, pick(contact(accepted = true), inc = true, out = false))
        assertNull(pick(contact(accepted = true, verified = true), inc = true, out = false))
    }

    @Test
    fun `initiator who has sent nothing says hi, before verify-later`() {
        assertEquals(ThreadBanner.SAY_HI, pick(contact(accepted = false), inc = false, out = false))
        assertEquals(ThreadBanner.VERIFY_LATER, pick(contact(accepted = false), inc = false, out = true))
    }

    @Test
    fun `dismissed waiting does not fall through, dismissed say-hi does`() {
        val a = contact(accepted = true)
        assertNull(pick(a, inc = false, out = false, dismissed = setOf("fp|waiting_peer")))
        val i = contact(accepted = false)
        assertEquals(ThreadBanner.VERIFY_LATER, pick(i, inc = false, out = false, dismissed = setOf("fp|say_hi")))
        assertNull(pick(i, inc = false, out = false, dismissed = setOf("fp|say_hi", "fp|verify_later")))
    }

    @Test
    fun `dismissal is per contact`() {
        val other = contact(accepted = true).copy(fingerprintHex = "other")
        assertEquals(ThreadBanner.WAITING_PEER, pick(other, inc = false, out = false, dismissed = setOf("fp|waiting_peer")))
    }

    @Test
    fun `no contact means no banner`() = assertNull(ThreadBanners.pick(null, false, false, emptySet()))

    @Test
    fun `verify-later banner shows the action and fires both callbacks`() {
        var acted = 0
        var dismissed = 0
        compose.setContent { MoyuTheme { ThreadBannerBar(ThreadBanner.VERIFY_LATER, { acted++ }, { dismissed++ }) } }
        compose.onNodeWithTag(ConversationTestTags.BANNER).assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.BANNER_ACTION).performClick()
        compose.onNodeWithTag(ConversationTestTags.BANNER_DISMISS).performClick()
        assertEquals(1, acted)
        assertEquals(1, dismissed)
    }

    @Test
    fun `waiting and say-hi banners have no action`() {
        compose.setContent { MoyuTheme { ThreadBannerBar(ThreadBanner.WAITING_PEER, {}, {}) } }
        compose.onNodeWithTag(ConversationTestTags.BANNER).assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.BANNER_ACTION).assertDoesNotExist()
    }

}
