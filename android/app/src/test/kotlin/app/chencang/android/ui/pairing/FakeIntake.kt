package app.chencang.android.ui.pairing

import app.chencang.shared.intake.IntakeHandler
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.intake.IntakeOutcome

/**
 * Native-free [IntakeHandler] for the wizard tests. By default any text with a lock is a pairing
 * code (so the fake 🔒 wires of the older tests reach the driver), blank is incomplete, anything
 * else is not ours; [handle] fails loudly unless injected.
 */
class FakeIntake(
    private val classify: (String) -> IntakeKind = { raw ->
        when {
            raw.isBlank() -> IntakeKind.Incomplete
            raw.contains("🔒") -> IntakeKind.PairingInvite(raw.trim())
            else -> IntakeKind.NotOurs
        }
    },
    private val handle: suspend (CharSequence?) -> IntakeOutcome = { error("unexpected intake.handle") },
) : IntakeHandler {
    val handled = mutableListOf<String>()

    override fun classify(raw: String): IntakeKind = classify.invoke(raw)

    override suspend fun handle(raw: CharSequence?): IntakeOutcome {
        handled += raw.toString()
        return handle.invoke(raw)
    }
}
