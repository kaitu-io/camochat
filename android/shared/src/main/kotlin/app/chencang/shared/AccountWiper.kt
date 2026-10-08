package app.chencang.shared

/**
 * Aggregated account-level wipe. Best-effort: every step runs even when an
 * earlier one throws — a single bad store must not leave the rest of the
 * account intact — and failures are re-thrown at the end as one [WipeFailed].
 *
 * Step order is pairing-state-first (so a suspended accept cannot write a contact back after it was
 * cleared), then data-first, identity-last: a crash mid-way leaves the safe
 * residue (identity present, data gone), never the reverse.
 */
class AccountWiper(
    private val clearChat: suspend () -> Unit,
    private val clearMedia: suspend () -> Unit,
    private val clearSessions: suspend () -> Unit,
    private val clearContacts: suspend () -> Unit,
    private val clearPendingPairing: suspend () -> Unit,
    private val clearDevicePrefs: suspend () -> Unit,
    /** 昵称 + 印章头像（在 `AppPrefs` 里，不随 devicePrefs 清掉，必须显式清）。 */
    private val clearMyProfile: suspend () -> Unit,
    private val wipeSpk: suspend () -> Unit,
    private val wipeIdentity: suspend () -> Unit,
    private val deleteKeystoreKey: suspend () -> Unit,
) {

    class WipeFailed(val failures: List<Pair<String, Throwable>>) : RuntimeException(
        "account wipe: ${failures.size} step(s) failed: " +
            failures.joinToString { "${it.first}=${it.second.javaClass.simpleName}" },
    )

    suspend fun wipeAll() {
        val failures = mutableListOf<Pair<String, Throwable>>()
        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                failures += name to t
            }
        }
        // 配对状态先清(经协调器、在它的锁内):等挂起中的接受/完成走完,否则它可在清联系人之后把联系人写回。
        step("pendingPairing", clearPendingPairing)
        step("chat", clearChat)
        step("media", clearMedia)
        step("sessions", clearSessions)
        step("contacts", clearContacts)
        step("devicePrefs", clearDevicePrefs)
        step("myProfile", clearMyProfile)
        step("spk", wipeSpk)
        step("identity", wipeIdentity)
        step("keystoreKey", deleteKeystoreKey)
        if (failures.isNotEmpty()) throw WipeFailed(failures)
    }
}
