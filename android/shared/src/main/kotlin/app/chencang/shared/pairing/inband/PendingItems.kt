package app.chencang.shared.pairing.inband

/** What a "pairing" row is waiting on (spec 2026-10-01-three-tab-shell §3.5.3). */
enum class PendingKind {
    /** Responder: the pairing code to send back hasn't been shared yet. */
    ResponseUnsent,

    /** Initiator: the code went out; waiting for the peer's code. */
    InviteAwaiting,
}

/**
 * One row of the "pairing" section.
 *
 * @property id the invite's `pairingId`, or — for [PendingKind.ResponseUnsent] — the peer's fingerprint.
 * @property note the invite's note; always null for a response (the row shows the contact's name).
 * @property isStale created more than 30 days ago: shown as "may have expired" and not counted in the badge.
 * @property canResend there is a stored wire text to share again (false for a migrated invite).
 */
data class PendingItem(
    val id: String,
    val kind: PendingKind,
    val note: String?,
    val createdAtMillis: Long,
    val isStale: Boolean,
    val canResend: Boolean,
) {
    /** The ball is in my court: something unsent that is not stale. This is what the tab badge counts. */
    val needsMyAction: Boolean get() = kind == PendingKind.ResponseUnsent && !isStale
}

/** "may have expired" threshold: created more than 30 days ago (exactly 30 days is not stale). */
private const val STALE_AFTER_MILLIS = 2_592_000_000L

/**
 * Derives the "pairing" rows. Only invites that went out appear (shared, or the share sheet was at least
 * presented — the same test as [awaitingInvites], so the count row never exceeds the rows listed here). A response appears only while it has never
 * been shared AND its contact still exists — once shared the peer is an ordinary contact, and a
 * record whose contact was deleted must not linger. Order: rows that need my action first, the
 * rest after; newest first within each group.
 */
fun pendingItems(
    invites: List<PendingPairingRecord>,
    responses: List<PairingResponseRecord>,
    contactFingerprints: Set<String>,
    nowMillis: Long,
): List<PendingItem> {
    fun isStale(createdAtMillis: Long) = nowMillis - createdAtMillis > STALE_AFTER_MILLIS

    val responseItems = responses
        .filter { it.lastSharedAtMillis == null && it.fingerprintHex in contactFingerprints }
        .map {
            PendingItem(
                id = it.fingerprintHex,
                kind = PendingKind.ResponseUnsent,
                note = null,
                createdAtMillis = it.createdAtMillis,
                isStale = isStale(it.createdAtMillis),
                canResend = it.responseWire.isNotEmpty(),
            )
        }
    // An invite that neither was shared nor had its share sheet presented is not listed: the wizard
    // drops it on close, and nothing went out, so there is nothing to wait for. Stale ones stay
    // (marked), so this list is a superset of awaitingInvites (which also cuts at 7 days).
    val inviteItems = invites.filter { it.lastSharedAtMillis != null || it.sheetPresentedAtMillis != null }.map {
        PendingItem(
            id = it.pairingId,
            kind = PendingKind.InviteAwaiting,
            note = it.note,
            createdAtMillis = it.createdAtMillis,
            isStale = isStale(it.createdAtMillis),
            canResend = it.inviteWire.isNotEmpty(),
        )
    }
    return (responseItems + inviteItems).sortedWith(
        compareByDescending<PendingItem> { it.needsMyAction }
            .thenByDescending { it.createdAtMillis }
            .thenBy { it.id },
    )
}

/** Contacts-tab badge: the rows that need my action. */
fun pendingBadgeCount(items: List<PendingItem>): Int = items.count { it.needsMyAction }

/**
 * Fingerprints of contacts whose response has never been shared: they are listed under "pairing"
 * only, so the contact list leaves them out.
 */
fun unsentResponseFingerprints(
    responses: List<PairingResponseRecord>,
    contactFingerprints: Set<String>,
): Set<String> = responses
    .filter { it.lastSharedAtMillis == null && it.fingerprintHex in contactFingerprints }
    .map { it.fingerprintHex }
    .toSet()
