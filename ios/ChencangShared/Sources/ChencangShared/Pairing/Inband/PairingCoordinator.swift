import Foundation
import Chencang

/// Long-lived identity provider for pairing. Production wraps the Keychain-backed
/// `SecretIdentity`; tests inject a fake that re-materialises the SAME identity
/// from serialized bytes (so a "process restart" reloads identical keys).
public protocol InbandIdentityProviding: Sendable {
    /// `@MainActor` so the `SecretIdentity` deserialize (post-quantum keygen with
    /// large stack buffers) runs on the main thread's big stack — overflowing the
    /// async cooperative pool's small stack SIGBUSes on device. This compiler-
    /// enforces the invariant the ``PairingCoordinator`` doc-comment relies on.
    @MainActor func loadIdentity() throws -> SecretIdentity
}

/// Persists a freshly-paired Double-Ratchet ``Session`` under the peer's id.
/// Takes ownership of the native handle. Production wraps ``SessionStore``.
public protocol PairedSessionPersisting: Sendable {
    func put(_ session: Session, peerId: String) async throws
    /// Drops the session stored under `peerId` (rolls back a commit that had to be abandoned).
    func remove(peerId: String) async
}

/// A freshly-paired peer, keyed by the local classical fingerprint.
public struct PairedContact: Equatable, Sendable {
    public let fingerprintHex: String
    public let displayName: String
    public let emoji: [String]
    public let deviceId: String
    public let pairedAtMillis: Int64
    /// 接受(对方的)邀请而建出的联系人带那份邀请的摘要;发起方建出的为 nil。
    public let acceptedInviteDigest: String?

    public init(
        fingerprintHex: String, displayName: String, emoji: [String], deviceId: String, pairedAtMillis: Int64,
        acceptedInviteDigest: String? = nil
    ) {
        self.fingerprintHex = fingerprintHex
        self.displayName = displayName
        self.emoji = emoji
        self.deviceId = deviceId
        self.pairedAtMillis = pairedAtMillis
        self.acceptedInviteDigest = acceptedInviteDigest
    }
    /// 写进联系人镜像的配对时间;0(老镜像读回来的占位)当作未知。
    public var pairedAt: Date? {
        pairedAtMillis > 0 ? Date(timeIntervalSince1970: Double(pairedAtMillis) / 1000) : nil
    }
}

/// Upserts a paired contact (keyed by ``PairedContact/fingerprintHex``) and looks
/// contacts up. Production mirrors into the App Group via ``AppGroupSync``.
public protocol PairedContactPersisting: Sendable {
    func upsert(_ contact: PairedContact) async throws
    func find(fingerprintHex: String) async -> PairedContact?
    func find(acceptedInviteDigest: String) async -> PairedContact?
}

/// 「作废密钥材料」出口。删除邀请、邀请完成、清空全部三处都**先作废、后删记录**;作废抛错则记录保留并上抛,
/// 不会出现「记录没了、密钥还在」。
///
/// **实现必须幂等**:邀请完成时 `invalidate` 在写会话之前执行,写会话失败后用户重试会对同一条记录再调一次。邀请今天不持有任何密钥材料(``PendingPairingRecord/keyHandle`` 恒为 nil),
/// 生产注入 ``NoInviteKeyMaterial``;为握手加固那份 spec 预留。
public protocol InviteKeyMaterialInvalidating: Sendable {
    func invalidate(_ record: PendingPairingRecord) throws
}

public struct NoInviteKeyMaterial: InviteKeyMaterialInvalidating {
    public init() {}
    public func invalidate(_ record: PendingPairingRecord) throws {}
}

/// 贴进来的文本为什么没被执行。任何一种都不建联系人、不动记录、不写会话存储。
public enum IncomingRejection: Error, Equatable {
    case noMatchingInvite
    case sessionCiphertext
    case ownInvite
    case notPairingWire
    /// 对方已是联系人。`matchedPairingId` 只在「回执对上了我一份待完成邀请、而发件人是我接受过其邀请、且已在用的联系人」
    /// (互发邀请,早已收到过对方的消息)时非空:那份邀请已无用,调用方可以只删它。
    case alreadyPaired(fingerprintHex: String, matchedPairingId: String? = nil)
    /// 互发邀请、双方都接受了对方的那份,而按指纹定下来的是**对方的**邀请:我这边留着的会话就是对的,
    /// 回执对上的那份我的邀请(`retiredPairingId`)已经作废删掉;对方贴我发回的配对码后会改用同一个会话。
    /// 联系人、会话都没动。
    case mutualInvite(fingerprintHex: String, retiredPairingId: String)

    public var message: String {
        switch self {
        case .noMatchingInvite: return L10n.pairingErrorNoMatchingInvite
        // 向导与来件分流都在前面把会话消息拦下了,正常走不到;仍给一条说得通的文案。
        case .sessionCiphertext: return L10n.pairingErrorIsMessage
        case .ownInvite: return L10n.pairingErrorOwnCode
        case .notPairingWire: return L10n.pairingErrorNotPairing
        case .alreadyPaired: return L10n.pairingErrorAlreadyPaired
        case .mutualInvite: return L10n.pairingMutualInviteResolved
        }
    }

    /// 只有 `alreadyPaired` / `mutualInvite` 非空:界面据此显示「查看联系人」。
    public var contactId: String? {
        switch self {
        case let .alreadyPaired(fp, _), let .mutualInvite(fp, _): return fp
        default: return nil
        }
    }
}

public enum IncomingOutcome {
    case accepted(PairingCoordinator.AcceptOutcome)
    case completed(PairingCoordinator.CompleteOutcome)
    case rejected(IncomingRejection)
}

/// Headless seam that drives zero-server in-band classical pairing for the Companion app — the iOS
/// port of chencang-android's `PairingCoordinator`. It wires the already-built engine
/// (``InbandPairing``) + transport (``PairingTransport``) to persistence: identity/SPK, the Double
/// Ratchet session store, the contact mirror, the list of my unanswered invites
/// (``PendingInviteStore``) and the responses I produced as an invitee (``PairingResponseStore``).
/// Both stores hold an in-memory cache the UI observes, so the coordinator always works on the injected
/// instances (production: `.shared`), never on private copies.
///
/// Fully OFFLINE — never touches the server. The round entrypoints are:
///
///  - ``startInvite(myDisplayName:)`` (A, round 1): mint a bundle wire and append a resumable pending
///    record (public content only).
///  - ``acceptIncoming(_:myDisplayName:)`` (B, round 2a): accept A's bundle, persist B's session +
///    response + contact. Pasting the same invite again never handshakes twice while the contact exists.
///  - ``completeIncoming(_:)`` (A, round 2b): find the pending invite the response answers by trial
///    computation, persist A's session + contact, remove that one record.
///  - ``handleIncoming(_:myDisplayName:)``: role-agnostic front door over the two above.
///
/// **Security invariants** (spec 2026-10-01-three-tab-shell §7.5): the trial computation writes nothing;
/// a response that matches no invite removes nothing; one response completes at most one invite; and
/// **no handshake is ever committed for a peer who is already a contact** — on either path it is refused
/// with ``IncomingRejection/alreadyPaired(fingerprintHex:)`` and zero writes, so an in-use session, and
/// the contact's name / verified flag / invite digest, can only be replaced after the user deletes that
/// contact. Entrypoints are serialised, so two racing submissions cannot both pass a check.
///
/// The one exception is the **mutual-invite window**: both people sent an invite and each accepted the
/// other's, so each holds half of a different handshake and neither session can work. When the reply to
/// my invite arrives from such a contact — one created by accepting their invite, whose messages have
/// never decrypted here (my response to them is still on file) — both sides converge on the invite of
/// the side with the higher fingerprint (see `resolveMutualInvite`).
///
/// **Process-death resumable on A.** A retains nothing secret between rounds — only the public pairing
/// nonce in the pending record. ``completeIncoming(_:)`` reloads A's long-lived IK/SPK, so a coordinator
/// rebuilt after the app process was killed mid-pairing resumes from the persisted record.
///
/// No secrets (SRK, session keys, transcript, confirm tag) are logged; the public nonce and wire text
/// are the only externalised values.
///
/// **Runs on the main actor.** Pairing is a discrete, one-shot user action, and the underlying
/// post-quantum keygen / identity-deserialize use large stack buffers that overflow Swift's small async
/// cooperative-pool stacks. Pinning the coordinator to the main actor (where `IdentityStore` /
/// `ContactsStore` already live) guarantees those native calls run on the main thread's big stack. The
/// handshake itself is a few ms of classical crypto, so this does not jank the UI.
@MainActor
public final class PairingCoordinator {
    /// Round-2a outcome (B): the header wire to return to A, plus what was persisted.
    public struct AcceptOutcome: Sendable {
        public let headerWire: String
        public let emoji: [String]
        public let contact: PairedContact
    }

    /// Round-2b outcome (A): the finished pairing's emoji + persisted contact.
    public struct CompleteOutcome: Sendable {
        public let emoji: [String]
        public let contact: PairedContact
    }

    private let identity: InbandIdentityProviding
    private let prekeyProvisioner: PrekeyProvisioner
    private let sessionStore: PairedSessionPersisting
    private let contactStore: PairedContactPersisting
    private let pending: PendingInviteStore
    private let responses: PairingResponseStore
    private let keyMaterial: InviteKeyMaterialInvalidating
    private let now: @Sendable () -> Int64
    private let newPairingId: @Sendable () -> String

    public init(
        identity: InbandIdentityProviding,
        prekeyProvisioner: PrekeyProvisioner,
        sessionStore: PairedSessionPersisting,
        contactStore: PairedContactPersisting,
        pending: PendingInviteStore,
        responses: PairingResponseStore,
        keyMaterial: InviteKeyMaterialInvalidating = NoInviteKeyMaterial(),
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        newPairingId: @escaping @Sendable () -> String = { UUID().uuidString }
    ) {
        self.identity = identity
        self.prekeyProvisioner = prekeyProvisioner
        self.sessionStore = sessionStore
        self.contactStore = contactStore
        self.pending = pending
        self.responses = responses
        self.keyMaterial = keyMaterial
        self.now = now
        self.newPairingId = newPairingId
    }

    // MARK: - Serialisation

    /// Every entrypoint that writes pairing state — the three rounds AND the record writers (notes, shared
    /// marks, delete, forget, clear) — runs one at a time, so a delete or an account wipe cannot slip in
    /// while a completion is suspended between its writes. The coordinator is
    /// `@MainActor`, but its persistence calls suspend, and a suspended call lets the next submission in:
    /// two submissions racing each other (double tap, scan callback + paste) could both pass a
    /// "not seen yet" check and handshake twice, or complete the same invite twice.
    ///
    /// The gate is process-wide (`static`), not per instance: production builds a coordinator wherever one
    /// is needed (`makeDefault()` in each wizard, the settings wipe, …), and all of them write the same
    /// shared stores. A per-instance gate would let two instances both pass the "not seen yet" check.
    private static var gateHeld = false
    private static var gateWaiters: [CheckedContinuation<Void, Never>] = []

    private func serialized<T>(_ body: () async throws -> T) async rethrows -> T {
        if Self.gateHeld {
            await withCheckedContinuation { Self.gateWaiters.append($0) }
        } else {
            Self.gateHeld = true
        }
        defer {
            if Self.gateWaiters.isEmpty {
                Self.gateHeld = false
            } else {
                Self.gateWaiters.removeFirst().resume() // hand the gate straight to the next waiter
            }
        }
        return try await body()
    }

    /// Disambiguate a received/pasted 🔒 wire (session vs pairing-bundle vs pairing-header).
    public func classifyIncoming(_ wireText: String) -> PairingTransport.WireKind {
        PairingTransport.classify(wireText)
    }

    // MARK: - Round 1 (A)

    /// Round 1 (A). Returns the invite that was minted earlier and never used — never shared, share sheet
    /// never opened, no note, text still on record — instead of piling up empty invites every time the wizard is reopened.
    /// Otherwise mints a bundle from A's long-lived IK + SPK and appends a resumable pending record
    /// carrying the invite text. A retains nothing secret, so a later ``completeIncoming(_:)`` works
    /// even after a process kill.
    public func startInvite(myDisplayName: String) async throws -> PendingPairingRecord {
        try await serialized {
            if let unused = pending.records.first(where: {
                $0.lastSharedAtMillis == nil && $0.sheetPresentedAtMillis == nil
                    && ($0.note ?? "").isEmpty && !$0.inviteWire.isEmpty
            }) {
                return unused
            }
            let aIk = try identity.loadIdentity()
            // Zero-server: a fresh identity has no SPK yet. Provision locally first, then load the
            // persisted SPK to mint the bundle.
            try prekeyProvisioner.provisionLocallyIfNeeded(identity: aIk)
            let spk = try prekeyProvisioner.loadActiveSpk()
            let invite = try InbandPairing.buildInviteBundle(aIk: aIk, aSpk: spk, inviterUsername: myDisplayName)
            let record = PendingPairingRecord(
                pairingId: newPairingId(),
                pairingNonceB64: invite.pairingNonce.base64EncodedString(),
                createdAtMillis: now(),
                inviteWire: PairingTransport.bundleToWire(invite.bundleBytes)
            )
            try pending.append(record)
            return record
        }
    }

    // MARK: - Round 2a (B)

    /// Round 2a (B). Accept A's bundle wire. Looked up by the invite's digest first:
    ///
    ///  (a) a stored response for this digest whose contact still exists (and was itself created by
    ///      accepting this invite) → NO handshake; the stored response and the contact's stored emoji
    ///      are returned;
    ///  (b) no such response, but a contact carries this digest, or the inviter (whose fingerprint is
    ///      read from the bundle before any handshake) is already a contact → NO handshake;
    ///      ``IncomingRejection/alreadyPaired(fingerprintHex:)``;
    ///  (c) neither → handshake; persist the session, then the response record, then the contact (which
    ///      keeps the digest for as long as it exists).
    ///
    /// A second handshake with an existing contact — the same invite again, or a newer invite from the
    /// same person — would mint a new ephemeral key and overwrite the session: desynchronising a peer
    /// that already completed with the first response, or destroying a session in use. Re-pairing with
    /// the same person therefore requires deleting the contact first (the digest goes with it).
    ///
    /// - Throws: ``PairingError/malformed(_:)`` if `wireText` is not a pairing bundle;
    ///   ``IncomingRejection/alreadyPaired(fingerprintHex:)`` in branch (b), with nothing written.
    public func acceptIncoming(_ wireText: String, myDisplayName: String) async throws -> AcceptOutcome {
        try await serialized { try await acceptLocked(wireText, myDisplayName: myDisplayName) }
    }

    private func acceptLocked(_ wireText: String, myDisplayName: String) async throws -> AcceptOutcome {
        guard PairingTransport.classify(wireText) == .pairingBundle else {
            throw PairingError.malformed("not a pairing invite")
        }
        let digest = try PairingTransport.inviteDigest(wireText)

        // The contact must still carry this digest: a response left behind by a contact that was deleted
        // and later re-created through another handshake belongs to a session that no longer exists,
        // and must not be handed out again.
        if let stored = responses.find(inviteDigest: digest),
           let contact = await contactStore.find(fingerprintHex: stored.fingerprintHex),
           contact.acceptedInviteDigest == digest {
            return AcceptOutcome(headerWire: stored.responseWire, emoji: contact.emoji, contact: contact)
        }
        if let contact = await contactStore.find(acceptedInviteDigest: digest) {
            throw IncomingRejection.alreadyPaired(fingerprintHex: contact.fingerprintHex)
        }

        let bundleBytes = try PairingTransport.pairingPayload(wireText)
        // The inviter's identity is public content of the bundle, so "is this person already a contact"
        // is answered BEFORE the engine runs: no ephemeral key minted, nothing written.
        let inviterBundle = try decodeClassicalBundle(bytes: bundleBytes)
        let inviterIk = inviterBundle.ik
        let inviterFp = InbandPairing.classicalFingerprintHex(ed25519: inviterIk.ed25519, x25519: inviterIk.x25519)
        // An invite whose inviter is this device is my own, even when its record is gone (completed or
        // deleted) and the text is still on the clipboard: refused before the engine, nothing written.
        let bIk = try identity.loadIdentity()
        if inviterFp == InbandPairing.localFingerprintHex(bIk) {
            throw IncomingRejection.ownInvite
        }
        if await contactStore.find(fingerprintHex: inviterFp) != nil {
            throw IncomingRejection.alreadyPaired(fingerprintHex: inviterFp)
        }

        let r = try InbandPairing.accept(bIk: bIk, bundleBytes: bundleBytes, bDisplayName: myDisplayName)
        let fp = r.peerFingerprintHex
        let headerWire = PairingTransport.headerToWire(r.headerBytes)

        // Persist order — session → response → contact, the contact LAST. The response is the only copy
        // of the ephemeral key's output the session was built with; if the contact write then fails, the
        // record is a harmless orphan (ignored without its contact; pasting the invite again handshakes
        // afresh). The other order could leave a contact whose digest blocks this invite while the
        // session or response it needs was never stored.
        try await sessionStore.put(r.session, peerId: fp)
        try responses.put(
            PairingResponseRecord(
                fingerprintHex: fp, responseWire: headerWire, inviteDigest: digest,
                createdAtMillis: now(), lastSharedAtMillis: nil
            )
        )
        let contact = pairedContact(fp: fp, deviceId: r.peerDeviceId, emoji: r.emoji,
                                    displayName: PeerName.sanitize(inviterBundle.inviterUsername) ?? Self.defaultName(fp),
                                    acceptedInviteDigest: digest)
        try await contactStore.upsert(contact)
        return AcceptOutcome(headerWire: headerWire, emoji: r.emoji, contact: contact)
    }

    // MARK: - Round 2b (A)

    /// Round 2b (A). Complete the pairing against B's response-header wire: try the response against
    /// every pending invite and finish the one whose key confirmation verifies. Given only the response
    /// — no id — this finds its invite; it is also the interface the receive-side auto-completion of the
    /// next spec will call.
    ///
    /// If the responder is already a contact the completion is refused before anything is committed:
    /// the invite stays pending (not completed, not removed, its key material not invalidated).
    ///
    /// Order on a match: invalidate the invite's key material → persist session → contact → remove that
    /// one record.
    ///
    /// - Throws: ``PairingError/malformed(_:)`` if `wireText` is not a pairing header;
    ///   ``IncomingRejection/noMatchingInvite`` if no pending invite matches (nothing written);
    ///   ``IncomingRejection/alreadyPaired(fingerprintHex:)`` if the responder is already a contact
    ///   (nothing written); a handshake error if the response itself cannot be decoded.
    public func completeIncoming(_ wireText: String) async throws -> CompleteOutcome {
        try await serialized { try await completeLocked(wireText) }
    }

    private func completeLocked(_ wireText: String) async throws -> CompleteOutcome {
        guard PairingTransport.classify(wireText) == .pairingHeader else {
            throw PairingError.malformed("not a pairing response")
        }
        let headerBytes = try PairingTransport.pairingPayload(wireText)
        // A response that does not even decode is a genuine handshake failure, not "no matching
        // invite" — and must be reported as such even when there is no invite to try it against.
        let responseHeader = try decodeClassicalHeader(bytes: headerBytes)

        let records = pending.records
        if records.isEmpty { throw IncomingRejection.noMatchingInvite }

        // ── Trial phase: pure computation. Nothing below writes to any store until a match is found;
        // A's SPK is only loaded (an invite on record means it was provisioned at mint time), never
        // (re)provisioned here.
        let aIk = try identity.loadIdentity()
        let spk = try prekeyProvisioner.loadActiveSpk()
        var match: (record: PendingPairingRecord, result: InbandPairing.CompleteResult)?
        for record in records {
            if let r = Self.tryComplete(aIk: aIk, spk: spk, record: record, headerBytes: headerBytes) {
                match = (record, r)
                break
            }
        }
        guard let (record, r) = match else { throw IncomingRejection.noMatchingInvite }
        let fp = r.peerFingerprintHex

        // An existing contact's session is never replaced — with one exception, the mutual-invite window
        // below. This also makes a completion that already committed its contact unrepeatable, even if
        // removing its record failed. (`r.session` is an unowned native handle that was never persisted;
        // it frees on release.)
        if let existing = await contactStore.find(fingerprintHex: fp) {
            guard existing.acceptedInviteDigest != nil else {
                throw IncomingRejection.alreadyPaired(fingerprintHex: fp)
            }
            guard responses.record(for: fp) != nil else {
                // 互发邀请,但对方的消息早已解开过(回应记录随之清掉)= 我接受的那份在用:这份我的邀请多余,报出来。
                throw IncomingRejection.alreadyPaired(fingerprintHex: fp, matchedPairingId: record.pairingId)
            }
            return try await resolveMutualInvite(
                record: record, result: r, existing: existing, localFp: InbandPairing.localFingerprintHex(aIk))
        }

        // ── Commit phase: exactly one record, the first that verified.
        try keyMaterial.invalidate(record) // throws → nothing persisted, the record stays
        try await sessionStore.put(r.session, peerId: fp)
        // The record writers are serialised with us, but the stores are shared: re-confirm the invite is
        // still on record before the contact goes in, and undo the session if it is not.
        guard pending.record(id: record.pairingId) != nil else {
            await sessionStore.remove(peerId: fp)
            throw IncomingRejection.noMatchingInvite
        }
        // A response record still held for this fingerprint has no contact (checked above), so it is a
        // leftover of a session that is now replaced: it must not resurface as 「回暗号还没发出去」 for
        // the contact created below.
        if responses.record(for: fp) != nil { try responses.remove(fingerprintHex: fp) }
        // Display name: the invite's note > the (sanitized) name the peer's response carries > the
        // localized default 「联系人 xxxxxx」.
        let displayName: String
        if let note = record.note, !note.isEmpty {
            displayName = note
        } else {
            displayName = PeerName.sanitize(responseHeader.bobDisplayName) ?? Self.defaultName(fp)
        }
        let contact = pairedContact(fp: fp, deviceId: r.peerDeviceId, emoji: r.emoji,
                                    displayName: displayName, acceptedInviteDigest: nil)
        try await contactStore.upsert(contact)
        try pending.remove(id: record.pairingId)
        return CompleteOutcome(emoji: r.emoji, contact: contact)
    }

    /// The mutual-invite window: we each pasted the other's invite, so each side holds the accepter's half
    /// of a DIFFERENT handshake, and now the peer's reply to my invite has arrived. The reply proves the
    /// peer holds the accepter's half of MY invite; my response record for them is still on file, so no
    /// message of theirs has ever decrypted here — the session I hold from accepting their invite has
    /// never worked for them, and replacing it loses nothing they can read.
    ///
    /// Both sides reach this point with the same two fingerprints and pick the same handshake: the invite
    /// of the side with the HIGHER fingerprint wins.
    ///
    ///  - Mine wins (local > peer): complete my invite and replace the session I got by accepting
    ///    theirs. The contact keeps its name, takes the new safety emoji (verification is reset — the old
    ///    emoji belonged to the discarded session), and no longer records an accepted invite; my response
    ///    to their invite is dropped (they will never complete it: their reply here retires it on their
    ///    side). Returns the completion like a normal round 2b.
    ///  - Theirs wins (local < peer): keep the session I hold (it is the peer's winning handshake), retire
    ///    the invite this reply answered (key material invalidated, record removed) and report
    ///    ``IncomingRejection/mutualInvite(fingerprintHex:retiredPairingId:)``. My response record stays:
    ///    the peer needs that code to finish their side.
    private func resolveMutualInvite(
        record: PendingPairingRecord, result r: InbandPairing.CompleteResult, existing: PairedContact, localFp: String
    ) async throws -> CompleteOutcome {
        let fp = r.peerFingerprintHex
        guard localFp > fp else {
            try keyMaterial.invalidate(record) // throws → nothing removed, the record stays
            try pending.remove(id: record.pairingId)
            throw IncomingRejection.mutualInvite(fingerprintHex: fp, retiredPairingId: record.pairingId)
        }
        try keyMaterial.invalidate(record) // throws → nothing persisted, the record stays
        // Replaces the session from accepting their invite. Unlike a fresh completion there is no rollback
        // if the record vanishes meanwhile: the old session is useless to the peer either way, and the
        // reply authentically answered this invite.
        try await sessionStore.put(r.session, peerId: fp)
        if responses.record(for: fp) != nil { try responses.remove(fingerprintHex: fp) }
        let contact = PairedContact(
            fingerprintHex: fp, displayName: existing.displayName, emoji: r.emoji, deviceId: r.peerDeviceId,
            pairedAtMillis: existing.pairedAtMillis > 0 ? existing.pairedAtMillis : now(), acceptedInviteDigest: nil
        )
        try await contactStore.upsert(contact)
        if pending.record(id: record.pairingId) != nil { try pending.remove(id: record.pairingId) }
        return CompleteOutcome(emoji: r.emoji, contact: contact)
    }

    /// One trial of `headerBytes` against `record`: the engine's pure `complete` computation. Returns nil
    /// when the response does not answer this invite — key confirmation fails — and for a damaged record
    /// (nonce not base64, wrong length, anything the engine refuses): a damaged record matches nothing,
    /// but must not stop the records after it from being tried. Touches no store.
    private static func tryComplete(
        aIk: SecretIdentity, spk: SecretSignedPreKey, record: PendingPairingRecord, headerBytes: Data
    ) -> InbandPairing.CompleteResult? {
        guard let nonce = Data(base64Encoded: record.pairingNonceB64) else { return nil }
        return try? InbandPairing.complete(aIk: aIk, aSpk: spk, pairingNonce: nonce, headerBytes: headerBytes)
    }

    // MARK: - Receive step

    /// Role-agnostic front door (the receive step, and later the 「陈仓解密」 intake): locate the wire in
    /// `raw`, classify it, and accept / complete / reject accordingly. Every rejection leaves contacts,
    /// records and the session store untouched. A genuine handshake failure is thrown, not swallowed.
    public func handleIncoming(_ raw: String, myDisplayName: String) async throws -> IncomingOutcome {
        try await serialized {
            let wire = PairingTransport.locate(raw)
            switch PairingTransport.classify(wire) {
            case .pairingBundle:
                if try isOwnInvite(wire) { return .rejected(.ownInvite) }
                do {
                    return .accepted(try await acceptLocked(wire, myDisplayName: myDisplayName))
                } catch let rejection as IncomingRejection {
                    return .rejected(rejection)
                }
            case .pairingHeader:
                do {
                    return .completed(try await completeLocked(wire))
                } catch let rejection as IncomingRejection {
                    return .rejected(rejection)
                }
            case .session:
                return .rejected(.sessionCiphertext)
            case .unknown:
                return .rejected(.notPairingWire)
            }
        }
    }

    /// Is `inviteWire` one of my own pending invites? Compared by digest; a record migrated from the old
    /// single slot has no invite text to digest, so it is recognised by the public pairing nonce inside
    /// the bundle instead. Both comparisons use public content only. A record whose stored text is not a
    /// decodable invite matches nothing.
    private func isOwnInvite(_ inviteWire: String) throws -> Bool {
        let records = pending.records
        if records.isEmpty { return false }
        let digest = try PairingTransport.inviteDigest(inviteWire)
        let withText = records.filter { !$0.inviteWire.isEmpty }
        let migrated = records.filter { $0.inviteWire.isEmpty }
        let isMine = withText.contains {
            (try? PairingTransport.inviteDigest($0.inviteWire)) == digest
        }
        if isMine { return true }
        if migrated.isEmpty { return false }
        let nonceB64 = try decodeClassicalBundle(bytes: PairingTransport.pairingPayload(inviteWire))
            .pairingNonce.base64EncodedString()
        return migrated.contains { $0.pairingNonceB64 == nonceB64 }
    }

    // MARK: - Records

    public func pendingInvite(id: String) -> PendingPairingRecord? { pending.record(id: id) }

    /// 待办库里全部邀请记录(未过滤;「待完成邀请」用 ``awaitingInvites(_:nowMillis:)`` 筛)。
    public var pendingInviteRecords: [PendingPairingRecord] { pending.records }

    /// - Throws: ``PairingError/malformed(_:)`` when `note` is not an acceptable note; nothing is stored then.
    public func updateNote(pairingId: String, note: String) async throws {
        guard let normalized = normalizeMyName(note) else { throw PairingError.malformed("note is not acceptable") }
        try await serialized {
            try pending.update(id: pairingId) { $0.note = normalized.isEmpty ? nil : normalized }
        }
    }

    public func markInviteShared(pairingId: String) async throws {
        let at = now()
        try await serialized { try pending.update(id: pairingId) { $0.lastSharedAtMillis = at } }
    }

    /// The share sheet was opened for this invite: it may have been sent (some sheets never report
    /// completion), so it is never discarded by ``discardUnsharedInvite(pairingId:)`` nor reused by
    /// ``startInvite(myDisplayName:)``. It is not marked shared, so it stays out of 「配对中」.
    public func markInvitePresented(pairingId: String) async throws {
        let at = now()
        try await serialized {
            try pending.update(id: pairingId) { $0.sheetPresentedAtMillis = $0.sheetPresentedAtMillis ?? at }
        }
    }

    /// Invalidate first, remove second: if invalidation throws, the record stays and it propagates.
    public func deleteInvite(pairingId: String) async throws {
        try await serialized {
            guard let record = pending.record(id: pairingId) else { return }
            try keyMaterial.invalidate(record)
            try pending.remove(id: pairingId)
        }
    }

    /// The wizard closed while holding an invite that was never handed out (no share sheet opened, not
    /// copied, not scanned): drop it instead of leaving a record 「配对中」 never shows. The record is
    /// re-read under the gate, so an invite marked shared or presented in the meantime is never deleted; an unknown id
    /// is a no-op. Invalidate first, remove second (same as ``deleteInvite(pairingId:)``).
    public func discardUnsharedInvite(pairingId: String) async throws {
        try await serialized {
            guard let record = pending.record(id: pairingId),
                  record.lastSharedAtMillis == nil, record.sheetPresentedAtMillis == nil else { return }
            try keyMaterial.invalidate(record)
            try pending.remove(id: pairingId)
        }
    }

    public func pendingResponse(fingerprintHex: String) -> PairingResponseRecord? {
        responses.record(for: fingerprintHex)
    }

    public func markResponseShared(fingerprintHex: String) async throws {
        let at = now()
        try await serialized { try responses.markShared(fingerprintHex: fingerprintHex, at: at) }
    }

    /// Drops the resendable response kept for `fingerprintHex` — called when the peer's first message
    /// arrives (they evidently completed) and when the contact is deleted or a pairing is rejected at
    /// 对印. Only the response text goes; the digest on the contact stays and keeps blocking a second
    /// handshake on the same invite, and the contact and session are not touched here (the caller that
    /// deletes a contact removes those itself). Runs on every received message, so it writes only when
    /// there is a record.
    public func forgetPeer(fingerprintHex: String) async throws {
        try await serialized {
            if responses.record(for: fingerprintHex) != nil { try responses.remove(fingerprintHex: fingerprintHex) }
        }
    }

    /// Account wipe. Waits for any pairing operation in flight, so nothing it writes can outlive the wipe.
    /// Responses hold no key material and go first; each invite is then invalidated and removed in that
    /// order, one by one — if an invalidation throws, that record (and the ones after it) stay. The two
    /// halves are attempted independently: a failure in one never skips the other, and the first error
    /// is the one thrown.
    public func clearAllPending() async throws {
        try await serialized {
            var firstError: Error?
            do { try responses.clear() } catch { firstError = error }
            do {
                for record in pending.records {
                    try keyMaterial.invalidate(record)
                    try pending.remove(id: record.pairingId)
                }
            } catch {
                firstError = firstError ?? error
            }
            if let firstError { throw firstError }
        }
    }

    /// 默认联系人名:按配对当时的系统语言生成(之后不随语言切换迁移)。
    private static func defaultName(_ fp: String) -> String {
        L10n.contactsDefaultName(String(fp.prefix(6)))
    }

    /// `deviceId` is synthetic (= fingerprint); there is no server-issued id in the zero-server path.
    private func pairedContact(
        fp: String, deviceId: String, emoji: [String], displayName: String, acceptedInviteDigest: String?
    ) -> PairedContact {
        PairedContact(
            fingerprintHex: fp, displayName: displayName, emoji: emoji, deviceId: deviceId,
            pairedAtMillis: now(), acceptedInviteDigest: acceptedInviteDigest
        )
    }
}
