package app.chencang.android.receive

import app.chencang.android.navigation.LaunchRequest
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntakeRoutingTest {

    @Test fun `decrypted message opens the thread`() {
        assertThat(IntakeRouting.route(IntakeOutcome.OpenThread("alice")))
            .isEqualTo(IntakeRoute.Main(LaunchRequest.OpenThread("alice")))
    }

    @Test fun `already stored message says so then opens the thread`() {
        assertThat(IntakeRouting.route(IntakeOutcome.AlreadyInThread("alice")))
            .isEqualTo(IntakeRoute.Notice(R.string.intake_already_in_thread, LaunchRequest.OpenThread("alice")))
    }

    @Test fun `pairing code opens the wizard with the wire`() {
        assertThat(IntakeRouting.route(IntakeOutcome.Pairing("🔒w")))
            .isEqualTo(IntakeRoute.Main(LaunchRequest.OpenWizard("🔒w")))
    }

    @Test fun `no contacts asks to add a contact`() {
        assertThat(IntakeRouting.route(IntakeOutcome.Failed(IntakeFailure.NO_CONTACTS)))
            .isEqualTo(
                IntakeRoute.Ask(R.string.intake_error_no_contacts, R.string.pairing_add_contact, LaunchRequest.AddContact),
            )
    }

    @Test fun `pending invite is a plain notice with no action`() {
        assertThat(IntakeRouting.route(IntakeOutcome.Failed(IntakeFailure.PENDING_INVITE)))
            .isEqualTo(IntakeRoute.Notice(R.string.intake_error_pending_invite, then = null))
    }

    @Test fun `cannot decrypt offers to open the app`() {
        assertThat(IntakeRouting.route(IntakeOutcome.Failed(IntakeFailure.CANNOT_DECRYPT)))
            .isEqualTo(
                IntakeRoute.Ask(R.string.intake_error_cannot_decrypt, R.string.intake_action_open_app, LaunchRequest.None),
            )
    }

    @Test fun `other failures are a notice and nothing else`() {
        listOf(IntakeFailure.INCOMPLETE, IntakeFailure.NOT_OURS, IntakeFailure.LINK_ONLY, IntakeFailure.SAVE_FAILED).forEach { f ->
            assertThat(IntakeRouting.route(IntakeOutcome.Failed(f)))
                .isEqualTo(IntakeRoute.Notice(f.messageRes, then = null))
        }
    }
}
