package app.chencang.shared.pairing.inband

import androidx.annotation.StringRes
import app.chencang.shared.CcRepository
import app.chencang.shared.chat.WireLocator
import app.chencang.shared.pairing.PairingLink
import app.chencang.shared.crypto.PrekeyProvisioner
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.identity.IdentityStore
import app.chencang.shared.model.Contact
import app.chencang.shared.model.PairingState
import app.chencang.shared.pairing.PairingCopy
import app.chencang.shared.profile.PeerName
import app.chencang.shared.profile.normalizeMyName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.chencang.SecretIdentity
import uniffi.chencang.SecretSignedPreKey
import uniffi.chencang.decodeClassicalBundle
import uniffi.chencang.decodeClassicalHeader
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID

/**
 * Native-free seam that [app.chencang.android.ui.pairing.PairingWizardViewModel]
 * (which must stay uniffi-free) drives instead of touching [PairingCoordinator]
 * (and therefore uniffi) directly. [PairingCoordinator] is the production
 * implementation; tests inject fakes.
 */
interface PairingDriver {
    fun classifyIncoming(wireText: String): PairingTransport.WireKind

    /** Mint an invite, or hand back the one that was minted and never used (see [PairingCoordinator.startInvite]). */
    suspend fun startInvite(myDisplayName: String): PendingPairingRecord

    /** @throws AlreadyPairedException when the inviter is already a contact (delete the contact to re-pair); @throws OwnInviteException when the inviter is this device. */
    suspend fun acceptIncoming(wireText: String, myDisplayName: String): PairingCoordinator.AcceptOutcome

    /**
     * @throws NoMatchingInviteException when no pending invite matches the response.
     * @throws AlreadyPairedException when the responder is already a contact; the invite stays pending.
     */
    suspend fun completeIncoming(wireText: String): PairingCoordinator.CompleteOutcome

    /** Role-agnostic entry: recognise what was pasted and act on it. A genuine handshake failure is thrown. */
    suspend fun handleIncoming(raw: String, myDisplayName: String): IncomingOutcome

    suspend fun pendingInvite(id: String): PendingPairingRecord?

    /** @throws IllegalArgumentException when [note] is not an acceptable note; nothing is stored then. */
    suspend fun updateNote(pairingId: String, note: String)
    suspend fun markInviteShared(pairingId: String)

    /** The share sheet was opened for this invite: it may have been sent, so it is never discarded or reused. */
    suspend fun markInvitePresented(pairingId: String)
    suspend fun deleteInvite(pairingId: String)

    /**
     * Drops the invite only if it was never shared and its share sheet never opened (re-read under the
     * lock): the wizard calls this when it closes, so an unused pairing code does not linger. Any other
     * or missing invite is left alone.
     */
    suspend fun discardUnsharedInvite(pairingId: String)
    suspend fun pendingResponse(fingerprintHex: String): PairingResponseRecord?
    suspend fun markResponseShared(fingerprintHex: String)

    /** Drops the resendable response kept for this peer. The digest on the contact is NOT touched. */
    suspend fun forgetPeer(fingerprintHex: String)
    suspend fun clearAllPending()
}

/**
 * The single exit through which an invite's key material is destroyed. Called before the invite's
 * record is removed — on delete, on completion and on account wipe; if it throws, the record
 * stays so the destruction can be retried (no "record gone, key still there" orphan).
 *
 * Invites own no key material today ([PendingPairingRecord.keyHandle] is always null), so the
 * production implementation is [NoInviteKeyMaterial]. Reserved for the handshake-hardening spec.
 */
fun interface InviteKeyMaterialInvalidator {
    suspend fun invalidate(record: PendingPairingRecord)
}

object NoInviteKeyMaterial : InviteKeyMaterialInvalidator {
    override suspend fun invalidate(record: PendingPairingRecord) = Unit
}

/** Why pasted text was not acted on. None of these creates a contact, changes a record or writes a session. */
sealed interface IncomingRejection {
    @get:StringRes val messageRes: Int
    val contactFingerprintHex: String? get() = null

    data object NoMatchingInvite : IncomingRejection {
        override val messageRes get() = PairingCopy.NO_MATCHING_INVITE
    }

    data object SessionCiphertext : IncomingRejection {
        override val messageRes get() = PairingCopy.SESSION_CIPHERTEXT
    }

    data object OwnInvite : IncomingRejection {
        override val messageRes get() = PairingCopy.OWN_INVITE
    }

    data object NotPairingWire : IncomingRejection {
        override val messageRes get() = PairingCopy.NOT_PAIRING_WIRE
    }

    /**
     * The peer is already a contact. [matchedPairingId] is set only for a reply that answered one of my
     * pending invites while its sender is a contact whose invite I accepted (互发邀请): that invite of
     * mine is now useless, and the caller may offer to delete exactly it.
     */
    data class AlreadyPaired(val fingerprintHex: String, val matchedPairingId: String? = null) : IncomingRejection {
        override val messageRes get() = PairingCopy.ALREADY_PAIRED
        override val contactFingerprintHex: String get() = fingerprintHex
    }
}

/** A well-formed response that none of my pending invites produced. */
class NoMatchingInviteException : Exception()

/**
 * The handshake would be with [fingerprintHex], who is already a contact. Nothing was written: an
 * existing contact's session is never replaced — re-pairing requires deleting the contact first.
 */
class AlreadyPairedException(val fingerprintHex: String, val matchedPairingId: String? = null) : Exception()

/** The invite's inviter is this device: the user pasted an invite they issued themselves. Nothing was written. */
class OwnInviteException : Exception()

sealed interface IncomingOutcome {
    data class Accepted(val outcome: PairingCoordinator.AcceptOutcome) : IncomingOutcome
    data class Completed(val outcome: PairingCoordinator.CompleteOutcome) : IncomingOutcome
    data class Rejected(val reason: IncomingRejection) : IncomingOutcome
}

/**
 * Headless seam that drives zero-server in-band classical pairing for the
 * app. It wires the already-built pairing engine ([InbandPairing]) +
 * transport ([PairingTransport]) to persistence: identity/SPK on disk, the
 * Double Ratchet [RatchetSessionStore], the [CcRepository] contact list, the
 * list of my unanswered invites ([PendingInviteStore]) and the responses I
 * produced as an invitee ([PairingResponseStore]).
 *
 * Fully OFFLINE — never touches the server. The round entrypoints are:
 *
 *  - [startInvite] (A, round 1): mint a bundle wire and append a resumable
 *    pending record (public content only).
 *  - [acceptIncoming] (B, round 2a): accept A's bundle, persist B's session +
 *    contact + the response to send back to A. Pasting the same invite again
 *    never handshakes twice while the contact exists.
 *  - [completeIncoming] (A, round 2b): find the pending invite the response
 *    answers by trial computation, persist A's session + contact, remove that
 *    one record.
 *  - [handleIncoming]: role-agnostic front door over the two above.
 *
 * **Security invariants** (spec 2026-10-01-three-tab-shell §7.5): the trial computation
 * writes nothing; a response that matches no invite removes nothing; one response
 * completes at most one invite; and **no handshake is ever committed for a peer who is
 * already a contact** — on either path it is refused with [AlreadyPairedException] and zero
 * writes, so an in-use session, and the contact's name / verified flag / invite digest, can
 * only be replaced after the user deletes that contact.
 *
 * **Process-death resumable on A.** A retains nothing secret between rounds —
 * only the public pairing nonce in the pending record. [completeIncoming]
 * reloads A's long-lived IK/SPK from disk, so a coordinator rebuilt after the
 * app process was killed mid-pairing resumes from the persisted record.
 *
 * Ownership: the [uniffi.chencang.Session] produced by accept/complete is handed
 * straight to [RatchetSessionStore.put], which takes ownership — this coordinator
 * closes it only when it has to bail out before that hand-off. The reloaded
 * [SecretIdentity] / [SecretSignedPreKey] handles ARE closed (`.use {}`).
 *
 * No secrets (SRK, session keys, transcript, confirm tag, Secret* bytes) are
 * logged; the public nonce and wire text are the only externalized values.
 */
class PairingCoordinator(
    private val identityStore: IdentityStore,
    private val prekeyProvisioner: PrekeyProvisioner,
    private val sessionStore: RatchetSessionStore,
    private val repository: CcRepository,
    private val pending: PendingInviteStore,
    private val responses: PairingResponseStore,
    /** Localized default name for a new contact, given the first 6 hex digits of its fingerprint. */
    private val defaultContactName: (shortId: String) -> String,
    private val keyMaterial: InviteKeyMaterialInvalidator = NoInviteKeyMaterial,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newPairingId: () -> String = { UUID.randomUUID().toString() },
) : PairingDriver {
    /** Round-2a outcome (B): the header wire to return to A, plus what was persisted. */
    data class AcceptOutcome(val headerWire: String, val emoji: List<String>, val contact: Contact)

    /** Round-2b outcome (A): the finished pairing's emoji + persisted contact. */
    data class CompleteOutcome(val emoji: List<String>, val contact: Contact)

    /**
     * Serialises every entrypoint that reads-then-writes pairing state, so two submissions racing
     * each other (double tap, scan callback + paste) cannot both pass a "not seen yet" check and
     * handshake twice, or complete the same invite twice.
     */
    private val mutex = Mutex()

    /** Disambiguate a received/pasted 🔒 wire (session vs pairing-bundle vs pairing-header). */
    override fun classifyIncoming(wireText: String): PairingTransport.WireKind =
        PairingTransport.classify(wireText)

    /**
     * Round 1 (A). Returns the invite that was minted earlier and never used — never shared, share
     * sheet never opened, no note, text still on record — instead of piling up empty invites every time the wizard is
     * reopened. Otherwise mints a bundle from A's long-lived IK + SPK and appends a resumable
     * pending record carrying the invite text. A retains nothing secret, so a later
     * [completeIncoming] works even after a process kill.
     */
    override suspend fun startInvite(myDisplayName: String): PendingPairingRecord = mutex.withLock {
        pending.all().firstOrNull {
            it.lastSharedAtMillis == null && it.sheetPresentedAtMillis == null &&
                it.note.isNullOrEmpty() && it.inviteWire.isNotEmpty()
        }?.let { return@withLock it }

        val invite = identityStore.load().use { ik ->
            // Zero-server: a fresh identity has no SPK yet (the server boot path
            // that used to provision it is gone). Provision locally first, then
            // load the persisted SPK to mint the bundle.
            prekeyProvisioner.provisionLocallyIfNeeded(ik)
            prekeyProvisioner.loadActiveSpk().use { spk ->
                InbandPairing.buildInviteBundle(ik, spk, inviterUsername = myDisplayName)
            }
        }
        val record = PendingPairingRecord(
            pairingId = newPairingId(),
            pairingNonceB64 = Base64.getEncoder().encodeToString(invite.pairingNonce),
            createdAtMillis = now(),
            inviteWire = PairingTransport.bundleToWire(invite.bundleBytes),
        )
        pending.append(record)
        record
    }

    /**
     * Round 2a (B). Accept A's bundle wire. Looked up by the invite's digest first:
     *
     *  (a) a stored response for this digest whose contact still exists (and was itself created by
     *      accepting this invite) → NO handshake; the stored response and the contact's stored
     *      emoji are returned;
     *  (b) no such response, but a contact carries this digest, or the inviter (whose fingerprint
     *      is read from the bundle before any handshake) is already a contact → NO handshake;
     *      [AlreadyPairedException];
     *  (c) neither → handshake, persist the session, the response record and the contact (which
     *      keeps the digest for as long as it exists).
     *
     * A second handshake with an existing contact — the same invite again, or a newer invite from
     * the same person — would mint a new ephemeral key and overwrite the session: desynchronising
     * a peer that already completed with the first response, or destroying a session in use.
     * Re-pairing with the same person therefore requires deleting the contact first (the digest
     * goes with it).
     *
     * @throws IllegalArgumentException if [wireText] is not a pairing bundle.
     * @throws AlreadyPairedException in branch (b); nothing was written.
     * @throws OwnInviteException when the inviter is this device (an invite I issued, no longer pending); nothing was written.
     */
    override suspend fun acceptIncoming(wireText: String, myDisplayName: String): AcceptOutcome =
        mutex.withLock { acceptLocked(wireText, myDisplayName) }

    private suspend fun acceptLocked(wireText: String, myDisplayName: String): AcceptOutcome {
        val digest = PairingTransport.inviteDigest(wireText)
        val contacts = repository.contacts.first()

        val stored = responses.findByInviteDigest(digest)
        // The contact must still carry this digest: a response left behind by a contact that was
        // deleted and later re-created through another handshake belongs to a session that no
        // longer exists, and must not be handed out again.
        val storedContact = stored?.let { r ->
            contacts.firstOrNull { it.fingerprintHex == r.fingerprintHex && it.acceptedInviteDigest == digest }
        }
        if (stored != null && storedContact != null) {
            return AcceptOutcome(stored.responseWire, storedContact.safetyEmoji, storedContact)
        }
        contacts.firstOrNull { it.acceptedInviteDigest == digest }?.let {
            throw AlreadyPairedException(it.fingerprintHex)
        }

        val bundleBytes = PairingTransport.pairingPayload(wireText)
        // The inviter's identity is public content of the bundle, so "is this person already a
        // contact" is answered BEFORE the engine runs: no ephemeral key minted, nothing written.
        val inviterBundle = decodeClassicalBundle(bundleBytes)
        val inviterFp = inviterBundle.ik.let {
            InbandPairing.classicalFingerprintHex(it.ed25519, it.x25519)
        }
        if (contacts.any { it.fingerprintHex == inviterFp }) throw AlreadyPairedException(inviterFp)

        val r = identityStore.load().use { bIk ->
            // An invite whose inviter is this device is my own, even when its record is gone (completed
            // or deleted) and the text is still on the clipboard: refused before the engine, nothing written.
            if (inviterFp == InbandPairing.localFingerprintHex(bIk)) throw OwnInviteException()
            InbandPairing.accept(bIk, bundleBytes, bDisplayName = myDisplayName)
        }
        val fp = r.peerFingerprintHex
        val headerWire = PairingTransport.headerToWire(r.headerBytes)
        // Commit segment: not cancellable. Cancelling the caller (the wizard's viewModelScope) between
        // the writes would leave a session without a contact; once the handshake has run, it finishes.
        return withContext(NonCancellable) {
            // The session store takes ownership of the native handle (we do NOT close it).
            sessionStore.put(name = fp, session = r.session, peerAlias = fp)
            // The response goes to disk BEFORE the contact: this response is the only copy of the
            // ephemeral key the session above was built with. If the contact write then fails, the
            // record is a harmless orphan (ignored without its contact) and pasting the invite again
            // handshakes afresh; the other order could leave a contact whose digest blocks this
            // invite while the response it needs was never stored.
            responses.put(
                PairingResponseRecord(
                    fingerprintHex = fp,
                    responseWire = headerWire,
                    inviteDigest = digest,
                    createdAtMillis = now(),
                    lastSharedAtMillis = null,
                ),
            )
            val contact = pairedContact(
                fp, r.peerDeviceId, r.emoji,
                PeerName.sanitize(inviterBundle.inviterUsername) ?: defaultContactName(fp.take(6)),
                acceptedInviteDigest = digest,
            )
            repository.upsertContact(contact)
            AcceptOutcome(headerWire, r.emoji, contact)
        }
    }

    /**
     * Round 2b (A). Complete the pairing against B's response-header wire: try the response
     * against every pending invite and finish the one whose key confirmation verifies. Given only
     * the response — no id — this finds its invite; it is also the interface the receive-side
     * auto-completion of the next spec will call.
     *
     * If the responder is already a contact the completion is refused before anything is committed:
     * the invite stays pending (not completed, not removed, its key material not invalidated).
     *
     * Order on a match: invalidate the invite's key material → persist session + contact →
     * remove that one record. The contact's default name is the invite's note when there is one.
     *
     * @throws IllegalArgumentException if [wireText] is not a pairing header.
     * @throws NoMatchingInviteException if no pending invite matches; nothing was written.
     * @throws AlreadyPairedException if the responder is already a contact; nothing was written.
     * @throws Exception (handshake failure) if the response itself cannot be decoded.
     */
    override suspend fun completeIncoming(wireText: String): CompleteOutcome =
        mutex.withLock { completeLocked(wireText) }

    private suspend fun completeLocked(wireText: String): CompleteOutcome {
        require(PairingTransport.classify(wireText) == PairingTransport.WireKind.PAIRING_HEADER) {
            "not a pairing response"
        }
        val headerBytes = PairingTransport.pairingPayload(wireText)
        // A response that does not even decode is a genuine handshake failure, not "no matching
        // invite" — and must be reported as such even when there is no invite to try it against.
        val responderName = PeerName.sanitize(decodeClassicalHeader(headerBytes).bobDisplayName)

        val records = pending.all()
        if (records.isEmpty()) throw NoMatchingInviteException()

        // ── Trial phase: pure computation. Nothing below writes to any store until a match is
        // found; A's SPK is only loaded (an invite on record means it was provisioned at mint
        // time), never (re)provisioned here.
        val match = identityStore.load().use { ik ->
            prekeyProvisioner.loadActiveSpk().use { spk ->
                records.firstNotNullOfOrNull { record ->
                    tryComplete(ik, spk, record, headerBytes)?.let { record to it }
                }
            }
        } ?: throw NoMatchingInviteException()
        val (record, r) = match
        val fp = r.peerFingerprintHex

        // An existing contact's session is never replaced. This also makes a completion that
        // already committed its contact unrepeatable, even if removing its record failed.
        val existing = repository.contacts.first().firstOrNull { it.fingerprintHex == fp }
        if (existing != null) {
            r.session.close() // unowned native handle; nothing was persisted
            // 互发邀请: I already accepted their invite, so this invite of mine is spare — name it.
            throw AlreadyPairedException(fp, record.pairingId.takeIf { existing.acceptedInviteDigest != null })
        }

        // ── Commit phase: exactly one record, the first that verified. Not cancellable: once the key
        // material is invalidated the writes below must all land, or the caller could be cancelled
        // (the wizard's viewModelScope) with the session and contact written but the invite still on record.
        return withContext(NonCancellable) {
            try {
                keyMaterial.invalidate(record)
            } catch (t: Throwable) {
                // Nothing persisted yet and the record stays; just release the unowned native session.
                r.session.close()
                throw t
            }
            sessionStore.put(name = fp, session = r.session, peerAlias = fp)
            // A response record still held for this fingerprint has no contact (checked above), so it
            // is a leftover of a session that is now replaced: it must not resurface as 「回暗号还没发出去」
            // for the contact created below.
            if (responses.recordFor(fp) != null) responses.remove(fp)
            // Display name: the invite's note > the (sanitized) name the peer's response carries >
            // the localized default ("Contact xxxxxx").
            val displayName = record.note?.takeIf { it.isNotEmpty() } ?: responderName ?: defaultContactName(fp.take(6))
            val contact = pairedContact(fp, r.peerDeviceId, r.emoji, displayName, acceptedInviteDigest = null)
            repository.upsertContact(contact)
            pending.remove(record.pairingId)
            CompleteOutcome(r.emoji, contact)
        }
    }

    /**
     * One trial of [headerBytes] against [record]: the engine's pure `complete` computation.
     * Returns null when the response does not answer this invite: key confirmation fails, or the
     * record is damaged (nonce not base64, wrong length, anything the engine refuses with an
     * exception) — a damaged record matches nothing, but must not stop the records after it from
     * being tried. Touches no store. Same tolerance as iOS; only cancellation is rethrown.
     */
    private fun tryComplete(
        ik: SecretIdentity,
        spk: SecretSignedPreKey,
        record: PendingPairingRecord,
        headerBytes: ByteArray,
    ): InbandPairing.CompleteResult? {
        return try {
            val nonce = Base64.getDecoder().decode(record.pairingNonceB64)
            InbandPairing.complete(ik, spk, nonce, headerBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Role-agnostic front door (the receive step, and later the 「陈仓解密」 intake): locate the wire
     * in [raw] (the decodable 🔒 line, so a share header line above it or a nickname line is skipped;
     * falling back to the text from the first 🔒), classify it, and accept / complete / reject accordingly. Every rejection leaves
     * contacts, records and the session store untouched. A genuine handshake failure is thrown.
     */
    override suspend fun handleIncoming(raw: String, myDisplayName: String): IncomingOutcome = mutex.withLock {
        // 🔒 wire 优先；没有就取文本里最后一个配对链接（卡片二维码、只复制到链接）；都没有按原样交给分类。
        val wire = WireLocator.extract(raw) ?: PairingLink.wires(raw).lastOrNull() ?: PairingTransport.locate(raw)
        when (PairingTransport.classify(wire)) {
            PairingTransport.WireKind.PAIRING_BUNDLE ->
                if (isOwnInvite(wire)) {
                    IncomingOutcome.Rejected(IncomingRejection.OwnInvite)
                } else {
                    try {
                        IncomingOutcome.Accepted(acceptLocked(wire, myDisplayName))
                    } catch (e: AlreadyPairedException) {
                        IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(e.fingerprintHex))
                    } catch (e: OwnInviteException) {
                        IncomingOutcome.Rejected(IncomingRejection.OwnInvite)
                    }
                }
            PairingTransport.WireKind.PAIRING_HEADER ->
                try {
                    IncomingOutcome.Completed(completeLocked(wire))
                } catch (e: NoMatchingInviteException) {
                    IncomingOutcome.Rejected(IncomingRejection.NoMatchingInvite)
                } catch (e: AlreadyPairedException) {
                    IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired(e.fingerprintHex, e.matchedPairingId))
                }
            PairingTransport.WireKind.SESSION -> IncomingOutcome.Rejected(IncomingRejection.SessionCiphertext)
            PairingTransport.WireKind.UNKNOWN -> IncomingOutcome.Rejected(IncomingRejection.NotPairingWire)
        }
    }

    /**
     * Is [inviteWire] one of my own pending invites? Compared by digest; a record migrated from the
     * old single slot has no invite text to digest, so it is recognised by the public pairing
     * nonce inside the bundle instead. Both comparisons use public content only. A record whose
     * stored text is not a decodable invite matches nothing.
     */
    private suspend fun isOwnInvite(inviteWire: String): Boolean {
        val records = pending.all()
        if (records.isEmpty()) return false
        val digest = PairingTransport.inviteDigest(inviteWire)
        val (withText, migrated) = records.partition { it.inviteWire.isNotEmpty() }
        val isMine = withText.any {
            PairingTransport.classify(it.inviteWire) == PairingTransport.WireKind.PAIRING_BUNDLE &&
                PairingTransport.inviteDigest(it.inviteWire) == digest
        }
        if (isMine) return true
        if (migrated.isEmpty()) return false
        val nonceB64 = Base64.getEncoder().encodeToString(
            decodeClassicalBundle(PairingTransport.pairingPayload(inviteWire)).pairingNonce,
        )
        return migrated.any { it.pairingNonceB64 == nonceB64 }
    }

    override suspend fun pendingInvite(id: String): PendingPairingRecord? =
        pending.all().firstOrNull { it.pairingId == id }

    // Every writer below takes the same lock as accept / complete, so none of them can interleave
    // with a handshake that is suspended mid-commit (the one instance lives in CcServiceLocator).
    override suspend fun updateNote(pairingId: String, note: String) {
        val normalized = requireNotNull(normalizeMyName(note)) { "note is not acceptable" }
        mutex.withLock { pending.update(pairingId) { it.copy(note = normalized.ifEmpty { null }) } }
    }

    override suspend fun markInviteShared(pairingId: String) {
        val at = now()
        mutex.withLock { pending.update(pairingId) { it.copy(lastSharedAtMillis = at) } }
    }

    override suspend fun markInvitePresented(pairingId: String) {
        val at = now()
        mutex.withLock { pending.update(pairingId) { it.copy(sheetPresentedAtMillis = it.sheetPresentedAtMillis ?: at) } }
    }

    /** Invalidate first, remove second: if invalidation throws, the record stays and it propagates. */
    override suspend fun deleteInvite(pairingId: String) = mutex.withLock {
        val record = pending.all().firstOrNull { it.pairingId == pairingId } ?: return@withLock
        keyMaterial.invalidate(record)
        pending.remove(pairingId)
    }

    override suspend fun discardUnsharedInvite(pairingId: String) = mutex.withLock {
        val record = pending.all().firstOrNull { it.pairingId == pairingId } ?: return@withLock
        if (record.lastSharedAtMillis != null || record.sheetPresentedAtMillis != null) return@withLock
        keyMaterial.invalidate(record)
        pending.remove(pairingId)
    }

    override suspend fun pendingResponse(fingerprintHex: String): PairingResponseRecord? =
        responses.recordFor(fingerprintHex)

    override suspend fun markResponseShared(fingerprintHex: String) {
        val at = now()
        mutex.withLock { responses.markShared(fingerprintHex, at) }
    }

    /**
     * Drops the resendable response kept for [fingerprintHex] — called when the peer's first
     * message arrives (they evidently completed) and when the contact is deleted. Only the
     * response text goes; the digest on the contact stays and keeps blocking a second handshake
     * on the same invite. Runs on every received message, so it writes only when there is a record.
     */
    override suspend fun forgetPeer(fingerprintHex: String) = mutex.withLock {
        if (responses.recordFor(fingerprintHex) != null) responses.remove(fingerprintHex)
    }

    /**
     * Account wipe. Responses hold no key material and go first; each invite is then invalidated
     * and removed in that order, one by one — if an invalidation throws, that record (and the
     * ones after it) stay and the failure propagates.
     */
    override suspend fun clearAllPending() = mutex.withLock {
        responses.clear()
        for (record in pending.all()) {
            keyMaterial.invalidate(record)
            pending.remove(record.pairingId)
        }
    }

    /** `deviceId` is synthetic (= fingerprint); there is no server-issued id in the zero-server path. */
    private fun pairedContact(
        fp: String,
        deviceId: String,
        emoji: List<String>,
        displayName: String,
        acceptedInviteDigest: String?,
    ) = Contact(
        fingerprintHex = fp,
        username = fp,
        displayName = displayName,
        pairedAt = now(),
        pairingState = PairingState.PAIRED,
        safetyEmoji = emoji,
        deviceId = deviceId,
        acceptedInviteDigest = acceptedInviteDigest,
    )
}
