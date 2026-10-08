package app.chencang.android.ui.pairing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import app.chencang.shared.pairing.PairingCopy
import app.chencang.shared.pairing.inband.PairingTransport
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val INVITE_WIRE = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
private val RESPONSE_WIRE = PairingTransport.headerToWire(byteArrayOf(4, 5, 6))

/** 向导界面层：提示、按钮的出现与否，以及回调拿到的参数。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairingWizardScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = context.getString(id)

    private fun render(
        stage: WizardStage,
        inviteId: String? = null,
        canResendInvite: Boolean = false,
        note: String = "",
        onOpenContact: (String) -> Unit = {},
        onResend: () -> Unit = {},
        onBack: () -> Unit = {},
        shareTarget: Pair<String, String>? = null,
        onConfirmMatch: (String) -> Unit = {},
        onVerifyLater: (String) -> Unit = {},
        otherAwaitingInvites: Int = 0,
        onPaste: (String?) -> Unit = {},
        onScanRequest: () -> Unit = {},
        onDeleteInvite: (String) -> Unit = {},
        onClipboardPasted: () -> Unit = {},
    ) = compose.setContent {
        MoyuTheme {
            PairingWizardContent(
                shareSite = "https://site.test/",
                ui = WizardUi(stepIndex = 1, stepTitles = PairingWizardViewModel.SHOW_FIRST, stage = stage),
                inviteId = inviteId,
                canResendInvite = canResendInvite,
                note = note,
                shareTarget = shareTarget,
                otherAwaitingInvites = otherAwaitingInvites,
                onScanRequest = onScanRequest,
                onBack = onBack,
                onOpenContact = onOpenContact,
                onNoteChange = {},
                onCopied = {},
                onShareLaunched = {},
                onAdvance = {},
                onResend = onResend,
                onInputChanged = {},
                onPaste = onPaste,
                onSubmit = {},
                onConfirmMatch = onConfirmMatch,
                onVerifyLater = onVerifyLater,
                onNameContinue = {},
                onNameSkip = {},
                onRejectMismatch = {},
                onRetry = {},
                onDeleteInvite = onDeleteInvite,
                onClipboardPasted = onClipboardPasted,
            )
        }
    }

    @Test
    fun `already-paired hint shows the copy and a view-contact button that reports the fingerprint`() {
        var opened: String? = null
        render(
            WizardStage.Receive(ReceiveNotice(PairingCopy.ALREADY_PAIRED, isHint = true, contactFingerprintHex = "fp-42")),
            onOpenContact = { opened = it },
        )
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_HINT).assertTextEquals(str(R.string.pairing_error_already_paired))
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_OPEN_CONTACT).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_OPEN_CONTACT).performClick()
        assertThat(opened).isEqualTo("fp-42")
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_ERROR).assertDoesNotExist()
    }

    @Test
    fun `mutual-invite notice on the receive step offers to delete the invite`() {
        val deleted = mutableListOf<String>()
        render(
            WizardStage.Receive(
                ReceiveNotice(R.string.pairing_mutual_invite, isHint = true, deleteInviteId = "p-1"),
                waitingForPeer = true,
            ),
            inviteId = "p-1",
            onDeleteInvite = { deleted += it },
        )
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_HINT).assertTextEquals(str(R.string.pairing_mutual_invite))
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_DELETE_INVITE).performScrollTo().performClick()
        assertThat(deleted).containsExactly("p-1")
    }

    @Test
    fun `mutual-invite notice without a held invite deletes the matched invite`() {
        val deleted = mutableListOf<String>()
        render(
            WizardStage.Receive(ReceiveNotice(R.string.pairing_mutual_invite, isHint = true, deleteInviteId = "p-old")),
            inviteId = null,
            onDeleteInvite = { deleted += it },
        )
        compose.onNodeWithTag(PairingWizardTestTags.DELETE).assertDoesNotExist()
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_DELETE_INVITE).performScrollTo().performClick()
        assertThat(deleted).containsExactly("p-old")
    }

    @Test
    fun `the send step paste counts as a clipboard paste`() {
        var pasted = 0
        var consumed = 0
        render(
            WizardStage.Show(wire = INVITE_WIRE, isResponse = false), inviteId = "p-1", shareTarget = "invite" to "p-1",
            onPaste = { pasted++ }, onClipboardPasted = { consumed++ },
        )
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_PASTE).performScrollTo().performClick()
        assertThat(consumed).isEqualTo(1)
        assertThat(pasted).isEqualTo(1)
    }

    @Test
    fun `the enter step paste counts as a clipboard paste`() {
        var consumed = 0
        render(WizardStage.Receive(), onClipboardPasted = { consumed++ })
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_PASTE).performScrollTo().performClick()
        assertThat(consumed).isEqualTo(1)
    }

    @Test
    fun `no-matching-invite hint has no view-contact button`() {
        render(WizardStage.Receive(ReceiveNotice(PairingCopy.NO_MATCHING_INVITE, isHint = true)))
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_HINT)
            .assertTextEquals(str(R.string.pairing_error_no_matching_invite))
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_OPEN_CONTACT).assertDoesNotExist()
    }

    @Test
    fun `a handshake failure uses the error tag, not the hint tag`() {
        render(WizardStage.Receive(ReceiveNotice(R.string.pairing_error_submit, isHint = false)))
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_ERROR).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_HINT).assertDoesNotExist()
    }

    @Test
    fun `delete shows only while an invite is held, and asks first`() {
        render(WizardStage.Receive(), inviteId = "p-1")
        compose.onNodeWithTag(PairingWizardTestTags.DELETE).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.DELETE).performClick()
        compose.onNodeWithText(str(R.string.pairing_delete_title)).assertExists()
    }

    @Test
    fun `no delete without an invite`() {
        render(WizardStage.Receive(), inviteId = null)
        compose.onNodeWithTag(PairingWizardTestTags.DELETE).assertDoesNotExist()
    }

    @Test
    fun `showing an invite offers the note field, showing a response does not`() {
        render(WizardStage.Show(wire = INVITE_WIRE, isResponse = false), inviteId = "p-1", note = "Lao Zhou", otherAwaitingInvites = 1)
        compose.onNodeWithTag(PairingWizardTestTags.NOTE).assertExists()
    }

    @Test
    fun `the note field shows only when another invite is waiting`() {
        render(WizardStage.Show(wire = INVITE_WIRE, isResponse = false), inviteId = "p-1", otherAwaitingInvites = 0)
        compose.onNodeWithTag(PairingWizardTestTags.NOTE).assertDoesNotExist()
    }

    @Test
    fun `an invite show step offers paste and scan above the qr`() {
        var pasted = 0
        var scanned = 0
        render(
            WizardStage.Show(wire = INVITE_WIRE, isResponse = false), inviteId = "p-1",
            onPaste = { pasted++ }, onScanRequest = { scanned++ },
        )
        compose.onNodeWithText(str(R.string.add_contact_received_prompt)).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_PASTE).assertTextEquals(str(R.string.pairing_paste))
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_SCAN).assertTextEquals(str(R.string.pairing_scan))
        val pasteTop = compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_PASTE).fetchSemanticsNode().boundsInRoot.top
        val qrTop = compose.onNodeWithTag(PairingWizardTestTags.QR_IMAGE).fetchSemanticsNode().boundsInRoot.top
        assertThat(pasteTop).isLessThan(qrTop)
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_PASTE).performClick()
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_SCAN).performClick()
        assertThat(pasted).isEqualTo(1)
        assertThat(scanned).isEqualTo(1)
    }

    @Test
    fun `a response show step has no received row`() {
        render(WizardStage.Show(wire = RESPONSE_WIRE, isResponse = true), inviteId = null)
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_PASTE).assertDoesNotExist()
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_RECEIVED_SCAN).assertDoesNotExist()
    }

    @Test
    fun `showing a response has no note field`() {
        render(WizardStage.Show(wire = RESPONSE_WIRE, isResponse = true), inviteId = null)
        compose.onNodeWithTag(PairingWizardTestTags.NOTE).assertDoesNotExist()
    }

    @Test
    fun `resend-once-more shows on receive only for a resendable invite`() {
        var resent = 0
        render(WizardStage.Receive(), inviteId = "p-1", canResendInvite = true, onResend = { resent++ })
        compose.onNodeWithTag(PairingWizardTestTags.RESEND).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.RESEND).performClick()
        assertThat(resent).isEqualTo(1)
    }

    @Test
    fun `no resend for a migrated invite or without an invite`() {
        render(WizardStage.Receive(), inviteId = "p-1", canResendInvite = false)
        compose.onNodeWithTag(PairingWizardTestTags.RESEND).assertDoesNotExist()
    }

    @Test
    fun `a failure that cannot be retried offers only back`() {
        render(WizardStage.Failed(R.string.pairing_error_gone, retryable = false))
        compose.onNodeWithTag(PairingWizardTestTags.FAILED_RETRY).assertDoesNotExist()
        compose.onNodeWithTag(PairingWizardTestTags.FAILED_BACK).assertExists()
    }

    @Test
    fun `a retryable failure offers retry`() {
        render(WizardStage.Failed(R.string.common_action_failed))
        compose.onNodeWithTag(PairingWizardTestTags.FAILED_RETRY).assertExists()
    }

    @Test
    fun `the delete confirmation survives a rotation`() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent {
            MoyuTheme {
                PairingWizardContent(
                shareSite = "https://site.test/",
                    ui = WizardUi(stepIndex = 1, stepTitles = PairingWizardViewModel.SHOW_FIRST, stage = WizardStage.Receive()),
                    inviteId = "inv-1",
                    canResendInvite = true,
                    note = "",
                    shareTarget = null,
                    otherAwaitingInvites = 0,
                    onScanRequest = {},
                    onBack = {},
                    onOpenContact = {},
                    onNoteChange = {},
                    onCopied = {},
                    onShareLaunched = {},
                    onAdvance = {},
                    onResend = {},
                    onInputChanged = {},
                    onPaste = {},
                    onSubmit = {},
                    onConfirmMatch = {},
                    onVerifyLater = {},
                    onNameContinue = {},
                    onNameSkip = {},
                    onRejectMismatch = {},
                    onRetry = {},
                    onDeleteInvite = {},
                    onClipboardPasted = {},
                )
            }
        }
        compose.onNodeWithTag(PairingWizardTestTags.DELETE).performClick()
        compose.onNodeWithText(str(R.string.pairing_delete_title)).assertExists()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(str(R.string.pairing_delete_title)).assertExists()
    }

    @Test
    fun `the title is add contact`() {
        render(WizardStage.Receive())
        compose.onNodeWithText(str(R.string.pairing_add_contact)).assertExists()
    }

    @Test
    fun `the send step makes share the primary action and offers they-scanned-it next`() {
        render(WizardStage.Show(wire = INVITE_WIRE, isResponse = false), inviteId = "p-1", shareTarget = "invite" to "p-1")
        compose.onNodeWithTag(PairingWizardTestTags.SHARE_BUTTON).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.SHARE_BUTTON).assertTextEquals(str(R.string.pairing_share_to_peer))
        compose.onNodeWithTag(PairingWizardTestTags.COPY_BUTTON).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_NEXT).assertTextEquals(str(R.string.pairing_next_after_scan))
        compose.onNodeWithTag(PairingWizardTestTags.QR_IMAGE).assertExists()
    }

    @Test
    fun `the reply screen's manual button says sent-next`() {
        render(WizardStage.Show(wire = RESPONSE_WIRE, isResponse = true), shareTarget = "response" to "fp-x")
        compose.onNodeWithTag(PairingWizardTestTags.SHOW_NEXT).assertTextEquals(str(R.string.pairing_sent_next))
    }

    @Test
    fun `the enter step shows waiting copy only while waiting for the peer`() {
        render(WizardStage.Receive(waitingForPeer = true), inviteId = "p-1")
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_WAITING).assertTextEquals(str(R.string.pairing_waiting_for_peer))
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_PASTE).assertTextEquals(str(R.string.pairing_paste))
    }

    @Test
    fun `the enter step without a held code has no waiting copy`() {
        render(WizardStage.Receive())
        compose.onNodeWithTag(PairingWizardTestTags.RECEIVE_WAITING).assertDoesNotExist()
    }

    @Test
    fun `the verify step has no save button and passes the typed name to match and later`() {
        val matched = mutableListOf<String>()
        val later = mutableListOf<String>()
        render(
            WizardStage.Confirm(emoji = EMO, fingerprintHex = "fp", peerUsername = "fp", placeholderName = "Contact fp"),
            onConfirmMatch = { matched += it },
            onVerifyLater = { later += it },
        )
        compose.onNodeWithTag("pairing-wizard-name-save").assertDoesNotExist()
        compose.onNodeWithTag(PairingWizardTestTags.CONFIRM_LATER).assertExists()
        compose.onNodeWithText(str(R.string.verify_explain_compare)).assertExists()
        compose.onNodeWithTag(PairingWizardTestTags.NAME_FIELD).performScrollTo().performTextInput("Lao Zhou")
        compose.onNodeWithTag(PairingWizardTestTags.CONFIRM_LATER).performScrollTo().performClick()
        compose.onNodeWithTag(PairingWizardTestTags.CONFIRM_MATCH).performScrollTo().performClick()
        assertThat(later).containsExactly("Lao Zhou")
        assertThat(matched).containsExactly("Lao Zhou")
    }

    @Test
    fun `mutual-invite snackbar offers action and dismiss, and a later error snackbar still shows`() {
        val notices = MutableSharedFlow<ReceiveNotice>(extraBufferCapacity = 1)
        val host = SnackbarHostState()
        var deleted = 0
        lateinit var showLater: (String) -> Unit
        compose.setContent {
            MoyuTheme {
                val scope = rememberCoroutineScope()
                showLater = { msg -> scope.launch { host.showSnackbar(msg) } }
                ActionNoticeEffect(notices = notices, host = host, onAction = { deleted++ })
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        notices.tryEmit(ReceiveNotice(R.string.pairing_mutual_invite, isHint = true, deleteInviteId = "p-1"))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.pairing_mutual_invite)).assertExists()
        compose.onNodeWithText(str(R.string.pairing_mutual_invite_delete)).assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.pairing_mutual_invite)).assertDoesNotExist()
        assertThat(deleted).isEqualTo(0)

        showLater("later-error")
        compose.waitForIdle()
        compose.onNodeWithText("later-error").assertExists()
    }
}
