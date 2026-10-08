package app.chencang.shared.pairing

import androidx.annotation.StringRes
import app.chencang.shared.R

/**
 * User-facing copy for the pairing wizard's receive step (spec 2026-10-01-three-tab-shell §3.5.4),
 * as string resources so the UI resolves them in the system language.
 */
object PairingCopy {
    /** A reply was pasted, but none of my pending pairing codes matches it. */
    @StringRes val NO_MATCHING_INVITE: Int = R.string.pairing_error_no_matching_invite

    /** An encrypted message was pasted. */
    @StringRes val SESSION_CIPHERTEXT: Int = R.string.pairing_error_is_message

    /** My own pairing code was pasted. */
    @StringRes val OWN_INVITE: Int = R.string.pairing_error_own_code

    /** Anything else / empty. */
    @StringRes val NOT_PAIRING_WIRE: Int = R.string.pairing_error_not_pairing

    /** The pairing code of someone who is already a contact. */
    @StringRes val ALREADY_PAIRED: Int = R.string.pairing_error_already_paired

    /** Mutual invites resolved in favour of theirs: my invite was retired. */
    @StringRes val MUTUAL_INVITE_RESOLVED: Int = R.string.pairing_mutual_invite_resolved
}
