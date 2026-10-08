package app.chencang.android.ui.pairing

import app.chencang.shared.model.Contact
import app.chencang.shared.model.PairingState
import app.chencang.shared.pairing.inband.IncomingOutcome
import app.chencang.shared.pairing.inband.PairingCoordinator
import app.chencang.shared.pairing.inband.PairingDriver
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingPairingRecord
import app.chencang.shared.pairing.inband.PairingTransport

/** Shared 8-emoji fixture for the pairing VM tests. */
val EMO: List<String> = listOf("🐶", "🍎", "🚲", "🌙", "🎈", "🍉", "🎸", "⚓")

/** Minimal paired [Contact] keyed by [fp] for asserting persisted outcomes. */
fun contact(fp: String): Contact = Contact(
    fingerprintHex = fp,
    username = fp,
    displayName = "Contact " + fp.take(6),
    pairedAt = 0L,
    pairingState = PairingState.PAIRED,
    safetyEmoji = EMO,
    deviceId = fp,
)

/** A never-shared pending invite carrying [wire], as [PairingDriver.startInvite] returns it. */
fun inviteRecord(
    wire: String,
    pairingId: String = "pairing-1",
    note: String? = null,
    lastSharedAtMillis: Long? = null,
    sheetPresentedAtMillis: Long? = null,
): PendingPairingRecord = PendingPairingRecord(
    pairingId = pairingId,
    pairingNonceB64 = "",
    createdAtMillis = 0L,
    inviteWire = wire,
    note = note,
    lastSharedAtMillis = lastSharedAtMillis,
    sheetPresentedAtMillis = sheetPresentedAtMillis,
)

/** A stored response for [fp], as [PairingDriver.pendingResponse] returns it. */
fun responseRecord(fp: String, wire: String, lastSharedAtMillis: Long? = null): PairingResponseRecord =
    PairingResponseRecord(
        fingerprintHex = fp,
        responseWire = wire,
        inviteDigest = "digest-$fp",
        createdAtMillis = 0L,
        lastSharedAtMillis = lastSharedAtMillis,
    )

/**
 * Test double for [PairingDriver] with injectable lambdas. Every entrypoint
 * throws by default, so a test only wires the call it exercises and any stray
 * call fails loudly.
 */
class FakePairingDriver(
    private val classifyIncoming: (String) -> PairingTransport.WireKind = { error("unexpected classifyIncoming") },
    private val startInvite: suspend (String) -> PendingPairingRecord = { error("unexpected startInvite") },
    private val acceptIncoming: suspend (String, String) -> PairingCoordinator.AcceptOutcome =
        { _, _ -> error("unexpected acceptIncoming") },
    private val completeIncoming: suspend (String) -> PairingCoordinator.CompleteOutcome =
        { error("unexpected completeIncoming") },
    private val handleIncoming: suspend (String, String) -> IncomingOutcome =
        { _, _ -> throw NotImplementedError("unexpected handleIncoming") },
    private val pendingInvite: suspend (String) -> PendingPairingRecord? =
        { throw NotImplementedError("unexpected pendingInvite") },
    private val updateNote: suspend (String, String) -> Unit =
        { _, _ -> throw NotImplementedError("unexpected updateNote") },
    private val markInviteShared: suspend (String) -> Unit =
        { throw NotImplementedError("unexpected markInviteShared") },
    private val markInvitePresented: suspend (String) -> Unit =
        { throw NotImplementedError("unexpected markInvitePresented") },
    private val deleteInvite: suspend (String) -> Unit = { throw NotImplementedError("unexpected deleteInvite") },
    private val discardUnsharedInvite: suspend (String) -> Unit =
        { throw NotImplementedError("unexpected discardUnsharedInvite") },
    private val pendingResponse: suspend (String) -> PairingResponseRecord? =
        { throw NotImplementedError("unexpected pendingResponse") },
    private val markResponseShared: suspend (String) -> Unit =
        { throw NotImplementedError("unexpected markResponseShared") },
    private val forgetPeer: suspend (String) -> Unit = { throw NotImplementedError("unexpected forgetPeer") },
    private val clearAllPending: suspend () -> Unit = { throw NotImplementedError("unexpected clearAllPending") },
) : PairingDriver {
    override fun classifyIncoming(wireText: String): PairingTransport.WireKind = classifyIncoming.invoke(wireText)
    override suspend fun startInvite(myDisplayName: String): PendingPairingRecord = startInvite.invoke(myDisplayName)
    override suspend fun acceptIncoming(wireText: String, myDisplayName: String): PairingCoordinator.AcceptOutcome =
        acceptIncoming.invoke(wireText, myDisplayName)
    override suspend fun completeIncoming(wireText: String): PairingCoordinator.CompleteOutcome =
        completeIncoming.invoke(wireText)
    override suspend fun handleIncoming(raw: String, myDisplayName: String): IncomingOutcome =
        handleIncoming.invoke(raw, myDisplayName)
    override suspend fun pendingInvite(id: String): PendingPairingRecord? = pendingInvite.invoke(id)
    override suspend fun updateNote(pairingId: String, note: String) = updateNote.invoke(pairingId, note)
    override suspend fun markInviteShared(pairingId: String) = markInviteShared.invoke(pairingId)
    override suspend fun markInvitePresented(pairingId: String) = markInvitePresented.invoke(pairingId)
    override suspend fun deleteInvite(pairingId: String) = deleteInvite.invoke(pairingId)
    override suspend fun discardUnsharedInvite(pairingId: String) = discardUnsharedInvite.invoke(pairingId)
    override suspend fun pendingResponse(fingerprintHex: String): PairingResponseRecord? =
        pendingResponse.invoke(fingerprintHex)
    override suspend fun markResponseShared(fingerprintHex: String) = markResponseShared.invoke(fingerprintHex)
    override suspend fun forgetPeer(fingerprintHex: String) = forgetPeer.invoke(fingerprintHex)
    override suspend fun clearAllPending() = clearAllPending.invoke()
}
