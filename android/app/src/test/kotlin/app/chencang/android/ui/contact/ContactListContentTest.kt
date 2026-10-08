package app.chencang.android.ui.contact

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.centerY
import androidx.compose.ui.test.width
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingPairingRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContactListContentTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    private val deleteTitle get() = str(R.string.pairing_delete_title)

    @Test
    fun `row keeps its tag and reads as one sentence with the verified state`() {
        val contacts = MutableStateFlow(
            listOf(
                Contact(fingerprintHex = "fpa", username = "fpa", displayName = "阿青", pairedAt = 0L, verified = true),
                Contact(fingerprintHex = "fpb", username = "fpb", displayName = "老周", pairedAt = 0L),
            ),
        )
        var opened: String? = null
        compose.setContent {
            MoyuTheme {
                ContactListContent(
                    viewModel = ContactListViewModel(contacts),
                    contentPadding = PaddingValues(),
                    onOpenContact = { opened = it },
                    onAddContact = {},
                    onOpenPending = {},
                    onResendPending = {},
                    onDeleteInvite = { true },
                )
            }
        }
        compose.onNodeWithTag(ContactListTestTags.ROW_PREFIX + "fpa").assertContentDescriptionEquals("阿青, ${str(R.string.verify_status_verified)}")
        compose.onNodeWithTag(ContactListTestTags.ROW_PREFIX + "fpb").assertContentDescriptionEquals("老周, ${str(R.string.verify_status_unverified)}")
        compose.onNodeWithTag(ContactListTestTags.ROW_PREFIX + "fpb").performClick()
        assertThat(opened).isEqualTo("fpb")
    }

    /** 标识写在 clearAndSetSemantics 里才不会被清掉；行尾按钮在清语义的节点之外，也要可点。 */
    @Test
    fun `pending rows keep their tags, resend buttons work, and the section hides when empty`() {
        val contacts = MutableStateFlow(
            listOf(Contact(fingerprintHex = "fpr", username = "fpr", displayName = "阿青", pairedAt = 0L)),
        )
        val now = 1_000_000_000_000L
        val invites = MutableStateFlow(
            listOf(
                PendingPairingRecord("inv-1", "", now - 60_000L, inviteWire = "🔒a", note = "老周", lastSharedAtMillis = now),
                PendingPairingRecord("inv-2", "", now - 60_000L, inviteWire = "", lastSharedAtMillis = now), // migrated: cannot resend
            ),
        )
        val responses = MutableStateFlow(
            listOf(PairingResponseRecord("fpr", "🔒r", "d", now - 60_000L)),
        )
        val resent = mutableListOf<String>()
        var opened: String? = null
        compose.setContent {
            MoyuTheme {
                ContactListContent(
                    viewModel = ContactListViewModel(contacts, invites, responses, now = { now }),
                    contentPadding = PaddingValues(),
                    onOpenContact = {},
                    onAddContact = {},
                    onOpenPending = { opened = it.id },
                    onResendPending = { resent += it.id },
                    onDeleteInvite = { true },
                )
            }
        }
        compose.onNodeWithTag(ContactListTestTags.PENDING).assertExists()
        compose.onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-1").assertExists()
            .assertContentDescriptionEquals("老周, ${str(R.string.contacts_pending_awaiting)} · ${relative(now)}")
        compose.onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-2").assertExists()
        compose.onNodeWithTag(ContactListTestTags.PENDING_RESPONSE_PREFIX + "fpr").assertExists()
        compose.onNodeWithTag(ContactListTestTags.PENDING_RESEND_PREFIX + "inv-1").performClick()
        compose.onNodeWithTag(ContactListTestTags.PENDING_SENDBACK_PREFIX + "fpr").performClick()
        compose.onNodeWithTag(ContactListTestTags.PENDING_RESEND_PREFIX + "inv-2").assertDoesNotExist()
        assertThat(resent).containsExactly("inv-1", "fpr").inOrder()
        compose.onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-1").performClick()
        assertThat(opened).isEqualTo("inv-1")
        // 回应还没发出去的联系人不在联系人列表里重复。
        compose.onNodeWithTag(ContactListTestTags.ROW_PREFIX + "fpr").assertDoesNotExist()
        // 有配对中条目时不显示「还没有联系人」。
        compose.onNodeWithTag(ContactListTestTags.EMPTY).assertDoesNotExist()

        invites.value = emptyList()
        responses.value = emptyList()
        compose.onNodeWithTag(ContactListTestTags.PENDING).assertDoesNotExist()
    }

    private fun relative(now: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            now - 60_000L, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()

    private fun pendingInviteVm(): ContactListViewModel {
        val now = 1_000_000_000_000L
        return ContactListViewModel(
            MutableStateFlow(emptyList()),
            MutableStateFlow(listOf(PendingPairingRecord("inv-1", "", now - 60_000L, inviteWire = "🔒a", note = "老周", lastSharedAtMillis = now))),
            MutableStateFlow(emptyList()),
            now = { now },
        )
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.longPressAndAskDelete() {
        onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-1").performTouchInput { longClick() }
        onNodeWithText(str(R.string.common_delete)).performClick() // long-press menu item
    }

    @Test
    fun `a failed invite delete shows the failure message`() {
        compose.setContent {
            MoyuTheme {
                ContactListContent(
                    viewModel = pendingInviteVm(),
                    contentPadding = PaddingValues(),
                    onOpenContact = {},
                    onAddContact = {},
                    onOpenPending = {},
                    onResendPending = {},
                    onDeleteInvite = { false },
                )
            }
        }
        compose.longPressAndAskDelete()
        compose.onNodeWithText(deleteTitle).assertExists()
        compose.onNodeWithText(str(R.string.common_delete)).performClick() // 弹窗里的确认
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(str(R.string.common_delete_failed)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the delete confirmation survives a rotation`() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent {
            MoyuTheme {
                ContactListContent(
                    viewModel = pendingInviteVm(),
                    contentPadding = PaddingValues(),
                    onOpenContact = {},
                    onAddContact = {},
                    onOpenPending = {},
                    onResendPending = {},
                    onDeleteInvite = { true },
                )
            }
        }
        compose.longPressAndAskDelete()
        compose.onNodeWithText(deleteTitle).assertExists()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(deleteTitle).assertExists()
    }

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun vm(
        contacts: List<Contact> = emptyList(),
        invites: List<PendingPairingRecord> = emptyList(),
        responses: List<PairingResponseRecord> = emptyList(),
    ) = ContactListViewModel(MutableStateFlow(contacts), MutableStateFlow(invites), MutableStateFlow(responses), now = { now })

    private fun render(
        viewModel: ContactListViewModel,
        onOpenContact: (String) -> Unit = {},
        onDeleteInvite: suspend (String) -> Boolean = { true },
    ) = compose.setContent {
        MoyuTheme {
            ContactListContent(
                viewModel = viewModel,
                contentPadding = PaddingValues(),
                onOpenContact = onOpenContact,
                onAddContact = {},
                onOpenPending = {},
                onResendPending = {},
                onDeleteInvite = onDeleteInvite,
            )
        }
    }

    @Test
    fun `empty state shows only the add row and the hint`() {
        render(vm())
        compose.onNodeWithTag(ContactListTestTags.ADD).assertExists()
        compose.onNodeWithTag(ContactListTestTags.EMPTY).assertExists()
        compose.onNodeWithText(str(R.string.contacts_empty)).assertExists()
        compose.onNodeWithTag(ContactListTestTags.PENDING).assertDoesNotExist()
    }

    @Test
    fun `pending section hidden when there is nothing pending`() {
        render(vm(contacts = listOf(Contact(fingerprintHex = "fpa", username = "fpa", displayName = "阿青", pairedAt = 0L))))
        compose.onNodeWithTag(ContactListTestTags.PENDING).assertDoesNotExist()
        compose.onNodeWithTag(ContactListTestTags.ROW_PREFIX + "fpa").assertExists()
        compose.onNodeWithTag(ContactListTestTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `hint hidden when there are pending items but no contacts`() {
        render(vm(invites = listOf(PendingPairingRecord("inv-1", "", now - 60_000L, inviteWire = "🔒a", lastSharedAtMillis = now))))
        compose.onNodeWithTag(ContactListTestTags.PENDING).assertExists()
        compose.onNodeWithTag(ContactListTestTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.contacts_empty)).assertDoesNotExist()
    }

    /** 整行一个点击区：左端（名字一侧）与右端（验证印一侧）都落在同一个 onOpenContact 上。 */
    @Test
    fun `contact row is a single click target`() {
        val opened = mutableListOf<String>()
        render(
            vm(contacts = listOf(Contact(fingerprintHex = "fpa", username = "fpa", displayName = "阿青", pairedAt = 0L, verified = true))),
            onOpenContact = { opened += it },
        )
        val row = compose.onNodeWithTag(ContactListTestTags.ROW_PREFIX + "fpa")
        row.performTouchInput { click(Offset(64f, centerY)) }
        row.performTouchInput { click(Offset(width - 24f, centerY)) }
        assertThat(opened).containsExactly("fpa", "fpa").inOrder()
    }

    @Test
    fun `invite row long press offers delete and confirms with the fixed copy`() {
        val deleted = mutableListOf<String>()
        render(
            pendingInviteVm(),
            onDeleteInvite = { deleted += it; true },
        )
        compose.longPressAndAskDelete()
        compose.onNodeWithText(deleteTitle).assertExists()
        compose.onNodeWithText(str(R.string.pairing_delete_body)).assertExists()
        compose.onNodeWithText(str(R.string.common_cancel)).assertExists()
        compose.onNodeWithText(str(R.string.common_delete)).performClick()
        compose.waitUntil(5_000) { deleted.isNotEmpty() }
        assertThat(deleted).containsExactly("inv-1")
        compose.onNodeWithText(deleteTitle).assertDoesNotExist()
    }

    @Test
    fun `stale invite shows the expired hint and keeps its row action`() {
        render(
            vm(
                invites = listOf(
                    PendingPairingRecord(
                        "inv-old", "", now - 31 * day, inviteWire = "🔒a", note = "老周", lastSharedAtMillis = now - 31 * day,
                    ),
                ),
            ),
        )
        compose.onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-old").assertContentDescriptionContains(str(R.string.contacts_pending_stale).substringAfter(" · "), substring = true)
        compose.onNodeWithTag(ContactListTestTags.PENDING_RESEND_PREFIX + "inv-old").assertExists()
        compose.onNodeWithText(str(R.string.contacts_resend)).assertExists()
    }

    @Test
    fun `migrated invite has no resend button`() {
        render(vm(invites = listOf(PendingPairingRecord("inv-m", "", now - 60_000L, inviteWire = "", lastSharedAtMillis = now))))
        compose.onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-m").assertExists()
        compose.onNodeWithTag(ContactListTestTags.PENDING_RESEND_PREFIX + "inv-m").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.contacts_resend)).assertDoesNotExist()
    }

    @Test
    fun `never shared invite is not listed`() {
        render(vm(invites = listOf(PendingPairingRecord("inv-n", "", now - 60_000L, inviteWire = "🔒a"))))
        compose.onNodeWithTag(ContactListTestTags.PENDING).assertDoesNotExist()
        compose.onNodeWithTag(ContactListTestTags.PENDING_ROW_PREFIX + "inv-n").assertDoesNotExist()
        compose.onNodeWithTag(ContactListTestTags.EMPTY).assertExists()
    }

    @Test
    fun `add row fires onAddContact directly without a menu`() {
        val taps = mutableListOf<String>()
        compose.setContent {
            MoyuTheme {
                ContactListContent(
                    viewModel = vm(),
                    contentPadding = PaddingValues(),
                    onOpenContact = {},
                    onAddContact = { taps += "add" },
                    onOpenPending = {},
                    onResendPending = {},
                    onDeleteInvite = { true },
                )
            }
        }
        compose.onNodeWithTag(ContactListTestTags.ADD).performClick()
        assertThat(taps).containsExactly("add")
    }
}
