package app.chencang.android.receive

import androidx.annotation.StringRes
import app.chencang.android.navigation.LaunchRequest
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeOutcome

/** What an intake entry outside the app does with an [IntakeOutcome]. */
sealed interface IntakeRoute {
    /** Open the app straight away. */
    data class Main(val request: LaunchRequest) : IntakeRoute

    /** Show a toast; then open the app with [then], or just close when null. */
    data class Notice(@StringRes val messageRes: Int, val then: LaunchRequest?) : IntakeRoute

    /** Ask in a dialog: the action button opens the app with [onAction]; cancel just closes. */
    data class Ask(@StringRes val messageRes: Int, @StringRes val actionRes: Int, val onAction: LaunchRequest) :
        IntakeRoute
}

object IntakeRouting {
    fun route(outcome: IntakeOutcome): IntakeRoute = when (outcome) {
        is IntakeOutcome.OpenThread -> IntakeRoute.Main(LaunchRequest.OpenThread(outcome.peerUsername))
        is IntakeOutcome.AlreadyInThread ->
            IntakeRoute.Notice(R.string.intake_already_in_thread, then = LaunchRequest.OpenThread(outcome.peerUsername))
        is IntakeOutcome.Pairing -> IntakeRoute.Main(LaunchRequest.OpenWizard(outcome.wire))
        is IntakeOutcome.Failed -> when (outcome.failure) {
            IntakeFailure.NO_CONTACTS ->
                IntakeRoute.Ask(R.string.intake_error_no_contacts, R.string.pairing_add_contact, LaunchRequest.AddContact)
            IntakeFailure.CANNOT_DECRYPT ->
                IntakeRoute.Ask(R.string.intake_error_cannot_decrypt, R.string.intake_action_open_app, LaunchRequest.None)
            else -> IntakeRoute.Notice(outcome.failure.messageRes, then = null)
        }
    }
}
