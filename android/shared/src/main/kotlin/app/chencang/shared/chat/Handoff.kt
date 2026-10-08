package app.chencang.shared.chat

import androidx.annotation.StringRes
import app.chencang.shared.R

/**
 * What the user has done with an outgoing message. The app never learns whether the peer
 * received it, so this only states the user's own action.
 */
enum class Handoff(@get:StringRes val statusRes: Int) {
    NOT_SENT(R.string.status_encrypted_not_sent),
    COPIED(R.string.status_copied),
    SHARED(R.string.status_shared),
    ;

    companion object {
        /** `copied` -> COPIED, `sent` (incl. legacy copies) -> SHARED, anything else -> NOT_SENT. */
        fun of(status: String): Handoff = when (status) {
            ChatMessage.STATUS_COPIED -> COPIED
            ChatMessage.STATUS_SENT -> SHARED
            else -> NOT_SENT
        }
    }
}
