package app.chencang.android.navigation

import android.content.Intent

/**
 * What the app should open once it is up: handed to [MainActivity] by an intake entry
 * (text-selection menu, system share).
 */
sealed interface LaunchRequest {
    data class OpenThread(val peerUsername: String) : LaunchRequest

    /** The add-contact wizard on "enter their code"; a non-null [wire] is submitted right away. */
    data class OpenWizard(val wire: String?) : LaunchRequest

    /** The add-contact wizard as the side that sends the first code. */
    data object AddContact : LaunchRequest

    data object None : LaunchRequest

    /** Single-string form for intent extras and `rememberSaveable`. */
    fun encode(): String = when (this) {
        is OpenThread -> "$KIND_THREAD:$peerUsername"
        is OpenWizard -> if (wire == null) KIND_WIZARD else "$KIND_WIZARD:$wire"
        AddContact -> KIND_ADD
        None -> KIND_NONE
    }

    companion object {
        private const val KIND_THREAD = "thread"
        private const val KIND_WIZARD = "wizard"
        private const val KIND_ADD = "add"
        private const val KIND_NONE = "none"

        fun decode(encoded: String?): LaunchRequest {
            if (encoded == null) return None
            val kind = encoded.substringBefore(':')
            val arg = if (':' in encoded) encoded.substringAfter(':') else null
            return when (kind) {
                KIND_THREAD -> arg?.let { OpenThread(it) } ?: None
                KIND_WIZARD -> OpenWizard(arg)
                KIND_ADD -> AddContact
                else -> None
            }
        }
    }
}

private const val EXTRA_LAUNCH_REQUEST = "cc.launch_request"

fun LaunchRequest.toExtras(intent: Intent) {
    intent.putExtra(EXTRA_LAUNCH_REQUEST, encode())
}

/**
 * Reads and removes the request: `intent` is the Activity's live Intent, reused verbatim on a
 * rotation / dark-mode recreation, so leaving the extra would replay the navigation.
 */
fun launchRequestFrom(intent: Intent): LaunchRequest {
    val encoded = intent.getStringExtra(EXTRA_LAUNCH_REQUEST)
    intent.removeExtra(EXTRA_LAUNCH_REQUEST)
    return LaunchRequest.decode(encoded)
}

/** Without an identity every launch (including pairing links and intake) runs onboarding first. */
fun startDestination(hasIdentity: Boolean): String = if (hasIdentity) Routes.MAIN else Routes.ONBOARDING

/** One navigation step; [CcNavGraph] executes them in order. */
sealed interface NavStep {
    /** Replace onboarding with `main`. */
    data object ToMain : NavStep

    data class ToThread(val peerUsername: String) : NavStep

    /** `role` ∈ `initiator | redeemer`; a non-null [wire] is handed over via [INCOMING_WIRE_KEY]. */
    data class ToWizard(val role: String, val wire: String?) : NavStep
}

/** Steps to carry out [request] when `main` is already the current screen. */
fun plan(request: LaunchRequest): List<NavStep> = when (request) {
    is LaunchRequest.OpenThread -> listOf(NavStep.ToThread(request.peerUsername))
    is LaunchRequest.OpenWizard -> listOf(NavStep.ToWizard("redeemer", request.wire))
    LaunchRequest.AddContact -> listOf(NavStep.ToWizard("initiator", null))
    LaunchRequest.None -> emptyList()
}

/** Steps once onboarding finishes: land on `main`, then carry out [request]. */
fun afterOnboarding(request: LaunchRequest): List<NavStep> = listOf(NavStep.ToMain) + plan(request)
