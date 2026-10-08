package app.chencang.android.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import app.chencang.shared.CcServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The share sheet's "a target app was chosen" callback for a pairing code (same mechanism as
 * [ShareCompletionReceiver]): marks the invite / response as shared, then tells an open wizard via
 * [PairingShareEvents] so it can move on. Cancelling the sheet sends nothing.
 *
 * Not exported (manifest `exported=false`); extras carry only the kind and the pairingId /
 * fingerprint, never the pairing code itself. The marks are no-ops for an unknown id.
 */
class PairingShareReceiver : BroadcastReceiver() {

    /** Mark actions and the app scope; production uses [CcServiceLocator], tests swap [deps]. */
    class Handle(
        val scope: CoroutineScope,
        val markInvite: suspend (pairingId: String) -> Unit,
        val markResponse: suspend (fingerprintHex: String) -> Unit,
    )

    fun interface Deps {
        fun resolve(context: Context): Handle
    }

    override fun onReceive(context: Context, intent: Intent) {
        val target = parse(intent) ?: return
        val handle = deps.resolve(context)
        // Non-null when dispatched by the system; null when a unit test calls onReceive directly.
        val pending: PendingResult? = goAsync()
        handle.scope.launch {
            try {
                when (target.kind) {
                    KIND_INVITE -> handle.markInvite(target.id)
                    else -> handle.markResponse(target.id)
                }
                PairingShareEvents.emit(target.id)
            } catch (e: Exception) {
                Log.e(TAG, "mark pairing code shared failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    data class Target(val kind: String, val id: String)

    companion object {
        private const val TAG = "PairingShare"
        const val ACTION = "app.chencang.android.action.PAIRING_SHARED"
        const val EXTRA_KIND = "app.chencang.android.extra.PAIRING_KIND"
        const val EXTRA_ID = "app.chencang.android.extra.PAIRING_ID"
        const val KIND_INVITE = "invite"
        const val KIND_RESPONSE = "response"

        @Volatile
        internal var deps: Deps = Deps { ctx ->
            val locator = CcServiceLocator.from(ctx)
            Handle(
                scope = locator.scope,
                markInvite = { locator.pairingCoordinator.markInviteShared(it) },
                markResponse = { locator.pairingCoordinator.markResponseShared(it) },
            )
        }

        /** Only a callback for a really chosen target, with a known kind and an id, is accepted. */
        internal fun parse(intent: Intent): Target? {
            if (intent.action != ACTION || !intent.hasExtra(Intent.EXTRA_CHOSEN_COMPONENT)) return null
            val kind = intent.getStringExtra(EXTRA_KIND)?.takeIf { it == KIND_INVITE || it == KIND_RESPONSE } ?: return null
            val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotEmpty() } ?: return null
            return Target(kind, id)
        }

        /**
         * Explicit component; `FLAG_MUTABLE` so the system can fill in `EXTRA_CHOSEN_COMPONENT`;
         * `FLAG_UPDATE_CURRENT` + a per-id identifier so two pending codes never share extras.
         * The only holder is the system share sheet; the worst a rewritten extra can do is mark one of
         * my own pending codes as shared.
         */
        fun pendingIntent(context: Context, kind: String, id: String): PendingIntent {
            val intent = Intent(context, PairingShareReceiver::class.java)
                .setAction(ACTION)
                .setIdentifier(id)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
            return PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun callback(context: Context, kind: String, id: String): IntentSender =
            pendingIntent(context, kind, id).intentSender
    }
}
