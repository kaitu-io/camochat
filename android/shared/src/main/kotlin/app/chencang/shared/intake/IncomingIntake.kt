package app.chencang.shared.intake

import android.util.Log
import androidx.annotation.StringRes
import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.WireReceiver
import kotlinx.coroutines.CancellationException

/**
 * Why incoming text could not be turned into a message. Granularity (what merges into what
 * when the cases can't be told apart):
 *
 * - [INCOMPLETE]: empty / whitespace only; or contains a lock but no line decodes (only the
 *   first line selected, half a selection). A wire that still decodes after being cut short
 *   can't be recognised as incomplete: it lands on [NOT_OURS] (wrong magic) or
 *   [CANNOT_DECRYPT] (right magic, AEAD fails).
 * - [NOT_OURS]: no lock at all; or decodes but the magic is neither session nor pairing.
 * - [LINK_ONLY]: no lock, only our bare share link (the header line's link copied alone). Reads
 *   as [INCOMPLETE] everywhere (same message); only the paste bar swaps in a "select all" hint.
 * - Session ciphertext whose wire is already stored is not a failure: [IntakeOutcome.AlreadyInThread].
 * - [NO_CONTACTS]: session ciphertext and this device has no contact at all (judged by the
 *   contact list, not the session store). Action: "Add contact".
 * - [CANNOT_DECRYPT]: session ciphertext, there are contacts, but no session opens it.
 *   "Already decrypted but deleted from the chat", "not sent to me" and "session lost" are
 *   all AEAD failures and merge here. Action: "Open app" (only shown from outside the app).
 * - [PENDING_INVITE]: session ciphertext no session opens, while an invite of mine is still
 *   awaiting its reply (see `awaitingInvites`): most likely the sender has not been added yet.
 *   Takes precedence over [NO_CONTACTS] / [CANNOT_DECRYPT]. No action.
 * - [SAVE_FAILED]: decrypted, but storing it failed (`receiveWireText` threw).
 */
enum class IntakeFailure(@StringRes val messageRes: Int, @StringRes val actionRes: Int?) {
    INCOMPLETE(R.string.intake_error_incomplete, null),
    NOT_OURS(R.string.intake_error_not_ours, null),
    LINK_ONLY(R.string.intake_error_incomplete, null),
    NO_CONTACTS(R.string.intake_error_no_contacts, R.string.pairing_add_contact),
    CANNOT_DECRYPT(R.string.intake_error_cannot_decrypt, R.string.intake_action_open_app),
    SAVE_FAILED(R.string.intake_error_save_failed, null),
    PENDING_INVITE(R.string.intake_error_pending_invite, null),
}

sealed interface IntakeOutcome {
    /** Decrypted and stored just now. */
    data class OpenThread(val peerUsername: String) : IntakeOutcome

    /** The same wire was already stored; nothing was decrypted. */
    data class AlreadyInThread(val peerUsername: String) : IntakeOutcome

    /** A pairing code or reply: the caller hands [wire] to the pairing flow. */
    data class Pairing(val wire: String) : IntakeOutcome

    data class Failed(val failure: IntakeFailure) : IntakeOutcome
}

interface IntakeHandler {
    fun classify(raw: String): IntakeKind
    suspend fun handle(raw: CharSequence?): IntakeOutcome
}

/**
 * Routes one piece of incoming text to an [IntakeOutcome]. Never logs the text or the wire,
 * only exception class names.
 */
class IncomingIntake(
    private val receiver: WireReceiver,
    private val findByWire: suspend (String) -> ChatMessage?,
    private val hasContacts: suspend () -> Boolean,
    private val hasAwaitingInvites: suspend () -> Boolean,
    private val classifier: (String) -> IntakeKind = { IntakeClassifier.classify(it) },
) : IntakeHandler {

    override fun classify(raw: String): IntakeKind = classifier(raw)

    override suspend fun handle(raw: CharSequence?): IntakeOutcome {
        val text = raw?.toString()
        if (text.isNullOrBlank()) return IntakeOutcome.Failed(IntakeFailure.INCOMPLETE)
        return when (val kind = classifier(text)) {
            is IntakeKind.PairingInvite -> IntakeOutcome.Pairing(kind.wire)
            is IntakeKind.PairingResponse -> IntakeOutcome.Pairing(kind.wire)
            IntakeKind.Incomplete -> IntakeOutcome.Failed(IntakeFailure.INCOMPLETE)
            IntakeKind.NotOurs -> IntakeOutcome.Failed(IntakeFailure.NOT_OURS)
            IntakeKind.LinkOnly -> IntakeOutcome.Failed(IntakeFailure.LINK_ONLY)
            is IntakeKind.Message -> handleMessage(kind.wire)
        }
    }

    private suspend fun handleMessage(wire: String): IntakeOutcome {
        findByWire(wire)?.let { return IntakeOutcome.AlreadyInThread(it.peerUsername) }
        val stored = try {
            receiver.receiveWireText(wire)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "intake: store after decrypt failed: ${e.javaClass.simpleName}")
            return IntakeOutcome.Failed(IntakeFailure.SAVE_FAILED)
        }
        if (stored != null) return IntakeOutcome.OpenThread(stored.peerUsername)
        if (hasAwaitingInvites()) return IntakeOutcome.Failed(IntakeFailure.PENDING_INVITE)
        return IntakeOutcome.Failed(
            if (hasContacts()) IntakeFailure.CANNOT_DECRYPT else IntakeFailure.NO_CONTACTS,
        )
    }

    private companion object {
        const val TAG = "Chencang"
    }
}
