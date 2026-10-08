package app.chencang.android.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.chencang.android.ui.main.MainTestTags
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingPairingRecord
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConversationListContentTest {
    @get:Rule val compose = createComposeRule()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)
    private val calls = mutableListOf<String>()

    private fun show(
        contacts: List<Contact> = emptyList(),
        latest: List<ChatMessage> = emptyList(),
        privacy: Boolean = false,
        invites: List<PendingPairingRecord> = emptyList(),
        itemCounts: Map<String, Int> = emptyMap(),
        responses: List<PairingResponseRecord> = emptyList(),
    ) {
        val vm = ConversationListViewModel(
            MutableStateFlow(contacts), MutableStateFlow(latest), MutableStateFlow(privacy),
            MutableStateFlow(emptyList()), MutableStateFlow(invites), MutableStateFlow(itemCounts),
            MutableStateFlow(responses),
        ) { _, _ -> true }
        compose.setContent {
            MoyuTheme {
                ConversationListContent(
                    viewModel = vm,
                    contentPadding = PaddingValues(),
                    onOpenThread = { calls += "thread:$it" },
                    onAddContact = { calls += "add" },
                    onGoToContacts = { calls += "contacts" },
                )
            }
        }
    }

    private fun contact(u: String, verified: Boolean = true, digest: String? = null) = Contact(
        fingerprintHex = "fp-$u", username = u, displayName = u, pairedAt = 0L, verified = verified,
        acceptedInviteDigest = digest,
    )

    @Test
    fun `empty state without contacts offers add contact and a paste hint`() {
        show()
        compose.onNodeWithText(str(R.string.conversations_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.conversations_empty_no_contacts)).assertIsDisplayed()
        compose.onNodeWithTag(ConversationListTestTags.EMPTY_ADD_CONTACT).performClick()
        assertEquals(listOf("add"), calls)
        compose.onNodeWithTag(ConversationListTestTags.EMPTY_RECEIVED_HINT).assertTextEquals(str(R.string.conversations_empty_received_hint))
    }

    @Test
    fun `an unshared invite alone still shows empty state A`() {
        show(invites = listOf(PendingPairingRecord("p", "AAAA", 1L, inviteWire = "w")))
        compose.onNodeWithTag(ConversationListTestTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `empty state with only a contact that owes its reply goes to contacts`() {
        show(
            contacts = listOf(contact("alice")),
            responses = listOf(PairingResponseRecord("fp-alice", "r", "d", createdAtMillis = 1L)),
        )
        compose.onNodeWithText(str(R.string.conversations_empty_has_contacts)).assertIsDisplayed()
        compose.onNodeWithTag(ConversationListTestTags.EMPTY_GO_TO_CONTACTS).performClick()
        assertEquals(listOf("contacts"), calls)
    }

    @Test
    fun `unverified row shows the marker and the real preview, and the whole row opens the chat`() {
        show(
            contacts = listOf(contact("alice", verified = false)),
            latest = listOf(ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "hello there", 100L)),
        )
        compose.onNodeWithText(str(R.string.verify_status_unverified)).assertIsDisplayed()
        compose.onNodeWithText("hello there").assertIsDisplayed()
        compose.onNodeWithTag(ConversationListTestTags.ROW_PREFIX + "alice").performClick()
        assertEquals(listOf("thread:alice"), calls)
    }

    @Test
    fun `contacts without messages show a row with the right subtitle that opens the chat`() {
        show(contacts = listOf(contact("alice", digest = "d"), contact("bob")), privacy = true)
        compose.onNodeWithTag(ConversationListTestTags.LIST).assertIsDisplayed()
        // 摘要隐藏不管没消息的行：照样显示说明。
        compose.onNodeWithText(str(R.string.conversations_preview_waiting_peer)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.conversations_preview_no_messages)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.conversations_preview_hidden)).assertDoesNotExist()
        compose.onNodeWithTag(ConversationListTestTags.ROW_PREFIX + "bob").performClick()
        assertEquals(listOf("thread:bob"), calls)
    }

    @Test
    fun `privacy switch hides the preview as encrypted`() {
        show(
            contacts = listOf(contact("alice", verified = false)),
            latest = listOf(ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "hello there", 100L)),
            privacy = true,
        )
        compose.onNodeWithText(str(R.string.conversations_preview_hidden)).assertIsDisplayed()
        compose.onNodeWithText("hello there").assertDoesNotExist()
    }

    @Test
    fun `album preview shows the photo count`() {
        show(
            contacts = listOf(contact("alice")),
            latest = listOf(ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "", 100L, kind = ChatMessage.KIND_IMAGE)),
            itemCounts = mapOf("a" to 3),
        )
        compose.onNodeWithText(ctx.resources.getQuantityString(R.plurals.media_preview_images, 3, 3)).assertIsDisplayed()
    }

    @Test
    fun `pending row shows count and click goes to contacts`() {
        val rec = PendingPairingRecord("p1", "n", System.currentTimeMillis(), lastSharedAtMillis = System.currentTimeMillis())
        show(invites = listOf(rec))
        compose.onNodeWithTag(MainTestTags.CHATS_PENDING_ROW).assertIsDisplayed()
        compose.onNodeWithTag(MainTestTags.CHATS_PENDING_ROW).performClick()
        assertEquals(listOf("contacts"), calls)
    }

    @Test
    fun `pending row absent when nothing is awaiting`() {
        val rec = PendingPairingRecord("p1", "n", System.currentTimeMillis()) // 没分享过、没开过面板
        show(invites = listOf(rec))
        compose.onNodeWithTag(MainTestTags.CHATS_PENDING_ROW).assertDoesNotExist()
    }
}
