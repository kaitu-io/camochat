import XCTest
@testable import ChencangShared
import Chencang

/// End-to-end proof for ``PairingCoordinator`` (iOS port of Android's
/// `PairingCoordinatorTest`; table B of the three-tab-shell plan): coordinators
/// over REAL handshake engines drive complete A↔B pairings, A survives a
/// simulated process kill between the two rounds, and the security invariants
/// hold — the trial computation writes nothing, a response that matches nothing
/// removes nothing, one response completes at most one invite, and no handshake
/// is ever committed for a peer who is already a contact.
///
/// All stores are isolated fakes (`FakeAppGroupDefaults`, `FakeKeychain`) — never
/// `.shared`, never the real App Group.
///
/// Runs `@MainActor` so identity/SPK generation (post-quantum keygen uses large
/// stack buffers) executes on the main thread's big stack.
@MainActor
final class PairingCoordinatorTests: XCTestCase {

    // MARK: - Fakes

    /// Re-materialises the SAME identity from serialized bytes, so a "process
    /// restart" reloads identical keys.
    private final class FakeIdentityProvider: InbandIdentityProviding, @unchecked Sendable {
        private let blob: Data
        init(_ id: SecretIdentity) { self.blob = id.serializeForLocalStorage() }
        @MainActor func loadIdentity() throws -> SecretIdentity { try SecretIdentity.fromLocalStorage(data: blob) }
    }

    private final class FakeSessionStore: PairedSessionPersisting, @unchecked Sendable {
        private let lock = NSLock()
        private var sessions: [String: Session] = [:]
        private var _writes = 0
        var failPut = false
        /// Runs just before a session is stored (ordering assertions).
        var onPut: (@Sendable (String) -> Void)?
        /// Suspends inside `put` until released (lets a test interleave other calls with a commit).
        var suspendInPut: (@Sendable () async -> Void)?
        var sessionCount: Int { lock.lock(); defer { lock.unlock() }; return sessions.count }
        var writes: Int { lock.lock(); defer { lock.unlock() }; return _writes }
        func put(_ session: Session, peerId: String) async throws {
            await suspendInPut?()
            onPut?(peerId)
            if failPut { throw NSError(domain: "test.session", code: 1) }
            lock.lock(); defer { lock.unlock() }
            sessions[peerId] = session
            _writes += 1
        }
        func session(_ peerId: String) -> Session? {
            lock.lock(); defer { lock.unlock() }
            return sessions[peerId]
        }
        func remove(_ peerId: String) {
            lock.lock(); defer { lock.unlock() }
            sessions[peerId] = nil
        }
        func remove(peerId: String) async { remove(peerId) }
    }

    /// The production App Group persister, with an injectable write failure and a hook.
    private final class FlakyContactStore: PairedContactPersisting, @unchecked Sendable {
        let inner: AppGroupContactPersister
        var failWrites = false
        var onUpsert: (@Sendable (String) -> Void)?
        init(sync: AppGroupSync) { inner = AppGroupContactPersister(sync: sync) }
        func upsert(_ contact: PairedContact) async throws {
            onUpsert?(contact.fingerprintHex)
            if failWrites { throw NSError(domain: "test.contacts", code: 1) }
            try await inner.upsert(contact)
        }
        func find(fingerprintHex: String) async -> PairedContact? { await inner.find(fingerprintHex: fingerprintHex) }
        func find(acceptedInviteDigest: String) async -> PairedContact? { await inner.find(acceptedInviteDigest: acceptedInviteDigest) }
    }

    private struct SpyKeyMaterial: InviteKeyMaterialInvalidating {
        let onInvalidate: @Sendable (PendingPairingRecord) throws -> Void
        func invalidate(_ record: PendingPairingRecord) throws { try onInvalidate(record) }
    }

    /// One-shot async latch: `wait()` returns once `open()` has been called.
    private actor Latch {
        private var isOpen = false
        private var waiters: [CheckedContinuation<Void, Never>] = []
        func wait() async {
            if isOpen { return }
            await withCheckedContinuation { waiters.append($0) }
        }
        func open() {
            isOpen = true
            waiters.forEach { $0.resume() }
            waiters = []
        }
    }

    private final class OrderLog: @unchecked Sendable {
        private let lock = NSLock()
        private var _lines: [String] = []
        var lines: [String] { lock.lock(); defer { lock.unlock() }; return _lines }
        func add(_ l: String) { lock.lock(); _lines.append(l); lock.unlock() }
    }

    private final class Clock: @unchecked Sendable {
        var ms: Int64 = 1_000
    }

    /// Per-identity bundle of stores. Identity and SPK (Keychain fake) persist across
    /// ``reopen()`` / ``sameIdentityNoData()``; the backing `AppGroupDefaults` of the
    /// two pairing stores and the contact mirror persist across ``reopen()`` only.
    @MainActor
    private final class Env {
        let identity: FakeIdentityProvider
        let provisioner: PrekeyProvisioner
        let keychain: FakeKeychain
        let sessionStore: FakeSessionStore
        let sync: AppGroupSync
        let contactStore: FlakyContactStore
        let pendingDefaults: FakeAppGroupDefaults
        let responseDefaults: AppGroupDefaults
        let pending: PendingInviteStore
        let responses: PairingResponseStore
        let clock: Clock
        var keyMaterial: InviteKeyMaterialInvalidating = NoInviteKeyMaterial()
        private(set) lazy var coord: PairingCoordinator = makeCoordinator()

        init(seedSpk: Bool = true) {
            let ik = SecretIdentity()
            identity = FakeIdentityProvider(ik)
            keychain = FakeKeychain()
            let defaults = UserDefaults(suiteName: "pairing-coord-test-\(UUID().uuidString)")!
            let store = SignedPreKeyStore(keychain: keychain)
            if seedSpk {
                let spk = try! SecretSignedPreKey(ik: ik, spkVersion: UInt32(PrekeyProvisioner.spkVersion))
                try! store.save(spk.serialize())
                defaults.set(PrekeyProvisioner.spkVersion, forKey: "chencang.spk_version")
            }
            provisioner = PrekeyProvisioner(store: store, defaults: defaults)
            sessionStore = FakeSessionStore()
            sync = AppGroupSync(defaults: FakeAppGroupDefaults())
            contactStore = FlakyContactStore(sync: sync)
            pendingDefaults = FakeAppGroupDefaults()
            responseDefaults = FakeAppGroupDefaults()
            pending = PendingInviteStore(defaults: pendingDefaults)
            responses = PairingResponseStore(defaults: responseDefaults)
            clock = Clock()
        }

        /// Fully explicit init for the derived environments.
        init(from o: Env, pendingDefaults: FakeAppGroupDefaults, responseDefaults: AppGroupDefaults,
             sessionStore: FakeSessionStore, sync: AppGroupSync) {
            identity = o.identity
            provisioner = o.provisioner
            keychain = o.keychain
            self.sessionStore = sessionStore
            self.sync = sync
            contactStore = FlakyContactStore(sync: sync)
            self.pendingDefaults = pendingDefaults
            self.responseDefaults = responseDefaults
            pending = PendingInviteStore(defaults: pendingDefaults)
            responses = PairingResponseStore(defaults: responseDefaults)
            clock = o.clock
        }

        func makeCoordinator() -> PairingCoordinator {
            let clock = self.clock
            return PairingCoordinator(
                identity: identity,
                prekeyProvisioner: provisioner,
                sessionStore: sessionStore,
                contactStore: contactStore,
                pending: pending,
                responses: responses,
                keyMaterial: keyMaterial,
                now: { clock.ms }
            )
        }

        /// A process restart: same identity/SPK/sessions/contacts, fresh in-memory store caches
        /// and a new coordinator over the same persisted bytes.
        func reopen() -> Env {
            Env(from: self, pendingDefaults: pendingDefaults, responseDefaults: responseDefaults,
                sessionStore: sessionStore, sync: sync)
        }

        /// The same person on a device that knows nothing of the other side: same identity and
        /// SPK, empty pairing stores, no contacts, no sessions.
        func sameIdentityNoData() -> Env {
            Env(from: self, pendingDefaults: FakeAppGroupDefaults(), responseDefaults: FakeAppGroupDefaults(),
                sessionStore: FakeSessionStore(), sync: AppGroupSync(defaults: FakeAppGroupDefaults()))
        }

        func contacts() throws -> [AppGroupContact] { try sync.readContacts() }

        /// The inviter's environment as an upgrade from the old build would see it: the legacy single
        /// slot holds `minted` in the three-field shape the old build wrote (no invite text).
        func upgradedFromSingleSlot(_ minted: PendingPairingRecord) -> Env {
            let legacyDefaults = FakeAppGroupDefaults()
            let legacy = #"{"pairingId":"\#(minted.pairingId)","pairingNonceB64":"\#(minted.pairingNonceB64)","createdAtMillis":\#(minted.createdAtMillis)}"#
            legacyDefaults.set(Data(legacy.utf8), forKey: "cc.pending_pairing.v1")
            return Env(from: self, pendingDefaults: legacyDefaults, responseDefaults: responseDefaults,
                       sessionStore: sessionStore, sync: sync)
        }

        /// The contact page's delete path (ContactsStore built fresh so its cache is not stale).
        func deleteContact(_ fp: String) async throws {
            try await ContactsStore(sync: sync).remove(contactId: fp)
            sessionStore.remove(fp)
        }
    }

    // MARK: - Helpers

    private func assertSessionsTalk(_ from: Env, _ fromPeerFp: String, _ to: Env, _ toPeerFp: String,
                                    file: StaticString = #filePath, line: UInt = #line) throws {
        let msg = Data((0..<24).map { UInt8($0) })
        let ct = try XCTUnwrap(from.sessionStore.session(fromPeerFp), file: file, line: line).encryptToBytes(plaintext: msg)
        let dec = try XCTUnwrap(to.sessionStore.session(toPeerFp), file: file, line: line).decryptFromBytes(ciphertext: ct)
        XCTAssertEqual(dec, msg, file: file, line: line)
    }

    private func accepted(_ o: IncomingOutcome) -> PairingCoordinator.AcceptOutcome? {
        if case let .accepted(a) = o { return a }
        return nil
    }
    private func completed(_ o: IncomingOutcome) -> PairingCoordinator.CompleteOutcome? {
        if case let .completed(c) = o { return c }
        return nil
    }
    private func rejection(_ o: IncomingOutcome) -> IncomingRejection? {
        if case let .rejected(r) = o { return r }
        return nil
    }

    private func assertThrowsRejection(_ expected: IncomingRejection, file: StaticString = #filePath, line: UInt = #line,
                                       _ block: () async throws -> Void) async {
        do {
            try await block()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch let r as IncomingRejection {
            XCTAssertEqual(r, expected, file: file, line: line)
        } catch {
            XCTFail("expected \(expected), got \(error)", file: file, line: line)
        }
    }

    private func assertThrowsAny(file: StaticString = #filePath, line: UInt = #line,
                                  _ block: () async throws -> Void) async {
        do {
            try await block()
            XCTFail("expected a throw", file: file, line: line)
        } catch {}
    }

    private let sessionCiphertext = encodeWire(ciphertext: Data([0xCC, 0xC8, 0x00, 0x00]))

    // MARK: - Pre-existing cases, on the new signatures

    func testFullPairingThroughCoordinatorSurvivesProcessDeath() async throws {
        let a = Env()
        let b = Env()

        let invite = try await a.coord.startInvite(myDisplayName: "alice")
        XCTAssertEqual(a.pending.records.count, 1)
        XCTAssertEqual(a.coord.classifyIncoming(invite.inviteWire), .pairingBundle)

        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "bob")
        XCTAssertEqual(try b.contacts().count, 1)

        // *** simulate A's app process being killed: NEW stores + coordinator over the SAME bytes ***
        let a2 = a.reopen()
        XCTAssertEqual(a2.pending.records.count, 1) // pending survived the "kill"

        let complete = try await a2.coord.completeIncoming(accept.headerWire)
        XCTAssertEqual(complete.emoji, accept.emoji)
        XCTAssertEqual(complete.emoji.count, 8)
        XCTAssertTrue(a2.pending.records.isEmpty) // removed on success
        XCTAssertEqual(try a.contacts().first?.emoji, accept.emoji)

        try assertSessionsTalk(b, accept.contact.fingerprintHex, a2, complete.contact.fingerprintHex)
    }

    // MARK: - Names carried by the handshake

    func testAcceptUsesSanitizedInviterNameElseDefault() async throws {
        let a = Env(), b = Env(), c = Env()
        let named = try await a.coord.startInvite(myDisplayName: "老王\u{202E}")
        let acc = try await b.coord.acceptIncoming(named.inviteWire, myDisplayName: "bob")
        XCTAssertEqual(acc.contact.displayName, "老王")

        let blank = try await c.coord.startInvite(myDisplayName: "\u{200B} ")
        let acc2 = try await b.coord.acceptIncoming(blank.inviteWire, myDisplayName: "bob")
        XCTAssertFalse(acc2.contact.displayName.isEmpty)
        XCTAssertTrue(acc2.contact.displayName.contains(String(acc2.contact.fingerprintHex.prefix(6))),
                      "default name expected, got \(acc2.contact.displayName)")
    }

    func testCompletePrefersNoteThenSanitizedResponderNameThenDefault() async throws {
        // sanitized responder name
        let a1 = Env(), b1 = Env()
        let i1 = try await a1.coord.startInvite(myDisplayName: "alice")
        let r1 = try await b1.coord.acceptIncoming(i1.inviteWire, myDisplayName: "小李\u{200B}\u{202E}")
        let c1 = try await a1.coord.completeIncoming(r1.headerWire)
        XCTAssertEqual(c1.contact.displayName, "小李")

        // note wins
        let a2 = Env(), b2 = Env()
        let i2 = try await a2.coord.startInvite(myDisplayName: "alice")
        try await a2.coord.updateNote(pairingId: i2.pairingId, note: "备注名")
        let r2 = try await b2.coord.acceptIncoming(i2.inviteWire, myDisplayName: "小李")
        let c2 = try await a2.coord.completeIncoming(r2.headerWire)
        XCTAssertEqual(c2.contact.displayName, "备注名")

        // nothing usable -> default
        let a3 = Env(), b3 = Env()
        let i3 = try await a3.coord.startInvite(myDisplayName: "alice")
        let r3 = try await b3.coord.acceptIncoming(i3.inviteWire, myDisplayName: "\u{200B}")
        let c3 = try await a3.coord.completeIncoming(r3.headerWire)
        XCTAssertTrue(c3.contact.displayName.contains(String(c3.contact.fingerprintHex.prefix(6))),
                      "default name expected, got \(c3.contact.displayName)")
    }

    func testHandleIncomingAcceptsInviteWrappedInShareHeader() async throws {
        let a = Env()
        let b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "alice")

        let accepted = try await b.coord.handleIncoming(
            PairingShareText.compose(wire: invite.inviteWire, site: ConfigRepository.shared.current().shareSite, isResponse: false), myDisplayName: "bob")
        guard case .accepted(let accept) = accepted else { return XCTFail("expected .accepted, got \(accepted)") }

        let completed = try await a.coord.handleIncoming(
            PairingShareText.compose(wire: accept.headerWire, site: ConfigRepository.shared.current().shareSite, isResponse: true), myDisplayName: "alice")
        guard case .completed = completed else { return XCTFail("expected .completed, got \(completed)") }
    }

    func testFreshIdentitiesPairWithoutPreSeededSpk() async throws {
        // Neither side has a pre-provisioned SPK. startInvite must provision A's SPK
        // LOCALLY before minting the invite, or the zero-server flow is dead on a fresh install.
        let a = Env(seedSpk: false)
        let b = Env(seedSpk: false)

        let invite = try await a.coord.startInvite(myDisplayName: "alice")
        XCTAssertFalse(invite.inviteWire.isEmpty)

        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "bob")
        let complete = try await a.coord.completeIncoming(accept.headerWire)

        XCTAssertEqual(complete.emoji, accept.emoji)
        XCTAssertEqual(complete.emoji.count, 8)
    }

    func testCompleteIncomingWithoutPendingInviteIsNoMatchingInvite() async throws {
        let a = Env()
        let b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "alice")
        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "bob")
        try a.pending.clear()

        await assertThrowsRejection(.noMatchingInvite) {
            _ = try await a.coord.completeIncoming(accept.headerWire)
        }
        XCTAssertEqual(a.sessionStore.writes, 0)
    }

    func testAcceptIncomingOnNonBundleWireThrows() async throws {
        let a = Env()
        let junk = "🔒garbage-not-a-real-wire"
        XCTAssertNotEqual(a.coord.classifyIncoming(junk), .pairingBundle)
        do {
            _ = try await a.coord.acceptIncoming(junk, myDisplayName: "alice")
            XCTFail("expected malformed error")
        } catch PairingError.malformed {
            // expected
        }
    }

    // MARK: - Table B

    func testB01TwoInvitesCoexist() async throws {
        let a = Env()
        let first = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.markInviteShared(pairingId: first.pairingId)
        let second = try await a.coord.startInvite(myDisplayName: "我")

        XCTAssertEqual(a.pending.records.map(\.pairingId), [first.pairingId, second.pairingId])
        XCTAssertNotEqual(second.inviteWire, first.inviteWire)
        XCTAssertNotEqual(second.pairingNonceB64, first.pairingNonceB64)
    }

    func testB02ResponseCompletesOnlyItsOwnInvite() async throws {
        let a = Env(), b = Env()
        let first = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.markInviteShared(pairingId: first.pairingId)
        let second = try await a.coord.startInvite(myDisplayName: "我")

        let accept = try await b.coord.acceptIncoming(second.inviteWire, myDisplayName: "我")
        let complete = try await a.coord.completeIncoming(accept.headerWire)

        XCTAssertEqual(a.pending.records.map(\.pairingId), [first.pairingId])
        XCTAssertEqual(try a.contacts().count, 1)
        XCTAssertEqual(complete.emoji, accept.emoji)
        XCTAssertEqual(a.sessionStore.writes, 1)
    }

    func testB03ResponseToSomeoneElsesInviteMatchesNothingAndTrialWritesNothing() async throws {
        let a = Env(), b = Env(), c = Env()
        let first = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.markInviteShared(pairingId: first.pairingId)
        _ = try await a.coord.startInvite(myDisplayName: "我")
        let cInvite = try await c.coord.startInvite(myDisplayName: "我")
        let stray = try await b.coord.acceptIncoming(cInvite.inviteWire, myDisplayName: "我").headerWire

        // Snapshot every persisted byte the coordinator could touch.
        let before = a.pending.records
        let pendingBytes = a.pendingDefaults.data(forKey: "cc.pending_pairings.v2")
        let responseBytes = a.responseDefaults.data(forKey: "cc.pairing_responses.v1")
        let keychainWrites = a.keychain.writeCount
        let contactBytes = a.sync.defaultsDataForTests

        let outcome = try await a.coord.handleIncoming(stray, myDisplayName: "我")
        XCTAssertEqual(rejection(outcome), .noMatchingInvite)
        XCTAssertEqual(rejection(outcome)?.message, L10n.pairingErrorNoMatchingInvite)
        await assertThrowsRejection(.noMatchingInvite) { _ = try await a.coord.completeIncoming(stray) }

        XCTAssertEqual(a.pending.records, before) // no record removed, none altered
        XCTAssertEqual(try a.contacts().count, 0)
        XCTAssertEqual(a.sessionStore.writes, 0) // the trial computation wrote nothing
        XCTAssertEqual(a.pendingDefaults.data(forKey: "cc.pending_pairings.v2"), pendingBytes)
        XCTAssertEqual(a.responseDefaults.data(forKey: "cc.pairing_responses.v1"), responseBytes)
        XCTAssertEqual(a.keychain.writeCount, keychainWrites) // no SPK (re)provisioned during the trial
        XCTAssertEqual(a.sync.defaultsDataForTests, contactBytes)
    }

    func testB04SameResponseCannotCompleteTwice() async throws {
        let a = Env(), b = Env()
        let first = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.markInviteShared(pairingId: first.pairingId)
        let second = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(second.inviteWire, myDisplayName: "我").headerWire
        _ = try await a.coord.completeIncoming(response)

        let again = try await a.coord.handleIncoming(response, myDisplayName: "我")
        XCTAssertEqual(rejection(again), .noMatchingInvite)

        XCTAssertEqual(a.pending.records.map(\.pairingId), [first.pairingId])
        XCTAssertEqual(try a.contacts().count, 1)
        XCTAssertEqual(a.sessionStore.writes, 1)
    }

    func testB04bTwoConcurrentSubmissionsOfOneResponseCompleteAtMostOnce() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").headerWire

        async let r1 = a.coord.handleIncoming(response, myDisplayName: "我")
        async let r2 = a.coord.handleIncoming(response, myDisplayName: "我")
        let outcomes = try await [r1, r2]

        XCTAssertEqual(outcomes.compactMap(completed).count, 1)
        XCTAssertEqual(outcomes.compactMap(rejection).count, 1)
        XCTAssertEqual(a.sessionStore.writes, 1)
        XCTAssertEqual(try a.contacts().count, 1)
        XCTAssertTrue(a.pending.records.isEmpty)
    }

    func testB05UnusedInviteIsReused() async throws {
        let a = Env()
        let first = try await a.coord.startInvite(myDisplayName: "我")
        let again = try await a.coord.startInvite(myDisplayName: "我")
        XCTAssertEqual(again, first)
        XCTAssertEqual(a.pending.records.count, 1)

        // A note makes it "used" …
        try await a.coord.updateNote(pairingId: first.pairingId, note: "发给老周的")
        let second = try await a.coord.startInvite(myDisplayName: "我")
        XCTAssertNotEqual(second.pairingId, first.pairingId)
        XCTAssertEqual(a.pending.records.count, 2)

        // … and so does having been shared.
        try await a.coord.markInviteShared(pairingId: second.pairingId)
        let third = try await a.coord.startInvite(myDisplayName: "我")
        XCTAssertEqual(a.pending.records.map(\.pairingId), [first.pairingId, second.pairingId, third.pairingId])
    }

    /// 向导关闭时丢弃没发出去的邀请:有备注也丢(不再攒成看不见的记录);已分享的绝不删(重读记录判断);
    /// 不存在的 id 什么都不做。
    func testDiscardUnsharedInviteRemovesOnlyANeverSharedInvite() async throws {
        let a = Env()
        let noted = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.updateNote(pairingId: noted.pairingId, note: "发给老周的")
        let shared = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.markInviteShared(pairingId: shared.pairingId)

        try await a.coord.discardUnsharedInvite(pairingId: noted.pairingId)
        try await a.coord.discardUnsharedInvite(pairingId: shared.pairingId)
        try await a.coord.discardUnsharedInvite(pairingId: "no-such-invite")

        XCTAssertEqual(a.pending.records.map(\.pairingId), [shared.pairingId])
    }

    func testB06InviteMigratedFromSingleSlotStillCompletes() async throws {
        let a = Env(), b = Env()
        let minted = try await a.coord.startInvite(myDisplayName: "我") // the old build kept only its nonce
        let up = a.upgradedFromSingleSlot(minted)

        let migrated = try XCTUnwrap(up.pending.records.first)
        XCTAssertEqual(up.pending.records.count, 1)
        XCTAssertEqual(migrated.inviteWire, "")
        XCTAssertEqual(migrated.pairingNonceB64, minted.pairingNonceB64)

        let accept = try await b.coord.acceptIncoming(minted.inviteWire, myDisplayName: "我")
        let complete = try await up.coord.completeIncoming(accept.headerWire)

        XCTAssertEqual(complete.emoji, accept.emoji)
        XCTAssertTrue(up.pending.records.isEmpty)
        try assertSessionsTalk(b, accept.contact.fingerprintHex, up, complete.contact.fingerprintHex)
    }

    func testB07NoteBecomesTheNewContactsName() async throws {
        let a = Env(), b = Env(), c = Env()
        let noted = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.updateNote(pairingId: noted.pairingId, note: "发给老周的")
        let withNote = try await a.coord.completeIncoming(
            try await b.coord.acceptIncoming(noted.inviteWire, myDisplayName: "我").headerWire)
        XCTAssertEqual(withNote.contact.displayName, "发给老周的")

        let plain = try await a.coord.startInvite(myDisplayName: "我")
        let withoutNote = try await a.coord.completeIncoming(
            try await c.coord.acceptIncoming(plain.inviteWire, myDisplayName: "").headerWire)
        XCTAssertEqual(withoutNote.contact.displayName, L10n.contactsDefaultName(String(withoutNote.contact.fingerprintHex.prefix(6))))

        XCTAssertEqual(Set(try a.contacts().map(\.displayName)), ["发给老周的", withoutNote.contact.displayName])
    }

    func testB08CompletesAfterStoresAndCoordinatorAreRebuilt() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.updateNote(pairingId: invite.pairingId, note: "老周")
        try await a.coord.markInviteShared(pairingId: invite.pairingId)
        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")

        let restarted = a.reopen()
        XCTAssertEqual(restarted.coord.pendingInvite(id: invite.pairingId)?.note, "老周")
        let complete = try await restarted.coord.completeIncoming(accept.headerWire)

        XCTAssertEqual(complete.emoji, accept.emoji)
        XCTAssertEqual(complete.contact.displayName, "老周")
        XCTAssertTrue(restarted.pending.records.isEmpty)
        try assertSessionsTalk(b, accept.contact.fingerprintHex, restarted, complete.contact.fingerprintHex)
    }

    /// 作废必须先于删记录:spy 在被调用的那一刻记录还在,事后记录没了。
    func testB09KeyMaterialIsInvalidatedBeforeTheRecordGoes() async throws {
        final class Log: @unchecked Sendable { var lines: [String] = [] }
        let log = Log()
        let a = Env(), b = Env()
        let pending = a.pending
        a.keyMaterial = SpyKeyMaterial { record in
            let there = MainActor.assumeIsolated { pending.record(id: record.pairingId) != nil }
            log.lines.append("invalidate:\(record.pairingId):recordStillThere=\(there)")
        }

        // delete
        let deleted = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.deleteInvite(pairingId: deleted.pairingId)
        XCTAssertEqual(log.lines, ["invalidate:\(deleted.pairingId):recordStillThere=true"])
        XCTAssertTrue(a.pending.records.isEmpty)

        // completion
        log.lines = []
        let done = try await a.coord.startInvite(myDisplayName: "我")
        _ = try await a.coord.completeIncoming(
            try await b.coord.acceptIncoming(done.inviteWire, myDisplayName: "我").headerWire)
        XCTAssertEqual(log.lines, ["invalidate:\(done.pairingId):recordStillThere=true"])
        XCTAssertTrue(a.pending.records.isEmpty)

        // account wipe: one by one, in order
        log.lines = []
        let one = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.markInviteShared(pairingId: one.pairingId)
        let two = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.clearAllPending()
        XCTAssertEqual(log.lines, [
            "invalidate:\(one.pairingId):recordStillThere=true",
            "invalidate:\(two.pairingId):recordStillThere=true",
        ])
        XCTAssertTrue(a.pending.records.isEmpty)
    }

    func testB09bFailedInvalidationKeepsTheRecord() async throws {
        let a = Env(), b = Env()
        a.keyMaterial = SpyKeyMaterial { _ in throw NSError(domain: "test.secureStorage", code: 1) }
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").headerWire

        await assertThrowsAny { try await a.coord.deleteInvite(pairingId: invite.pairingId) }
        XCTAssertEqual(a.pending.records, [invite])

        // Completion: nothing may be persisted when the invite's key material could not be destroyed.
        do {
            _ = try await a.coord.completeIncoming(response)
            XCTFail("expected the invalidation failure to propagate")
        } catch {
            XCTAssertEqual((error as NSError).domain, "test.secureStorage")
        }
        XCTAssertEqual(a.pending.records, [invite])
        XCTAssertEqual(try a.contacts().count, 0)
        XCTAssertEqual(a.sessionStore.writes, 0)

        await assertThrowsAny { try await a.coord.clearAllPending() }
        XCTAssertEqual(a.pending.records, [invite])

        // Once invalidation works again the same response still completes — it was a retryable failure.
        a.keyMaterial = NoInviteKeyMaterial()
        let healed = a.reopen()
        let complete = try await healed.coord.completeIncoming(response)
        XCTAssertFalse(complete.contact.fingerprintHex.isEmpty)
        XCTAssertTrue(healed.pending.records.isEmpty)
    }

    func testB10AcceptStoresTheResponseAsUnsent() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        b.clock.ms = 5_000

        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")

        let stored = try XCTUnwrap(b.coord.pendingResponse(fingerprintHex: accept.contact.fingerprintHex))
        XCTAssertEqual(stored.responseWire, accept.headerWire)
        XCTAssertNil(stored.lastSharedAtMillis)
        XCTAssertEqual(stored.createdAtMillis, 5_000)
        XCTAssertEqual(stored.inviteDigest, try PairingTransport.inviteDigest(invite.inviteWire))

        b.clock.ms = 6_000
        try await b.coord.markResponseShared(fingerprintHex: accept.contact.fingerprintHex)
        XCTAssertEqual(b.coord.pendingResponse(fingerprintHex: accept.contact.fingerprintHex)?.lastSharedAtMillis, 6_000)
    }

    func testB11PastingTheSameInviteAgainReturnsTheStoredResponse() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let first = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        XCTAssertEqual(b.sessionStore.writes, 1)

        let second = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        let viaIntake = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")

        XCTAssertEqual(second.headerWire, first.headerWire)
        XCTAssertEqual(second.emoji, first.emoji)
        XCTAssertEqual(second.contact.fingerprintHex, first.contact.fingerprintHex)
        XCTAssertEqual(second.contact.acceptedInviteDigest, first.contact.acceptedInviteDigest)
        XCTAssertEqual(accepted(viaIntake)?.headerWire, second.headerWire)
        XCTAssertEqual(b.sessionStore.writes, 1) // no second handshake: the session was not overwritten
        XCTAssertEqual(try b.contacts().map(\.id), [first.contact.fingerprintHex])

        let complete = try await a.coord.completeIncoming(second.headerWire)
        XCTAssertEqual(complete.emoji, first.emoji)
        try assertSessionsTalk(b, first.contact.fingerprintHex, a, complete.contact.fingerprintHex)
    }

    func testB11bTwoConcurrentSubmissionsOfOneInviteHandshakeOnce() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")

        async let r1 = b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        async let r2 = b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        let outcomes = try await [r1, r2]

        let wires = outcomes.compactMap(accepted).map(\.headerWire)
        XCTAssertEqual(wires.count, 2)
        XCTAssertEqual(Set(wires).count, 1) // both got the SAME response
        XCTAssertEqual(b.sessionStore.writes, 1) // and only one session was written
        XCTAssertEqual(try b.contacts().count, 1)
    }

    func testB12AfterDeletingTheContactTheInviteHandshakesAfresh() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let first = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        let fpA = first.contact.fingerprintHex

        // Delete the contact the way the contact page does — but WITHOUT forgetPeer, the harder case:
        // a response record left behind must not be handed out for a contact that is gone.
        try await b.deleteContact(fpA)

        let second = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")

        XCTAssertNotEqual(second.headerWire, first.headerWire)
        XCTAssertEqual(b.sessionStore.writes, 2)
        XCTAssertEqual(try b.contacts().first?.acceptedInviteDigest, try PairingTransport.inviteDigest(invite.inviteWire))
        XCTAssertEqual(b.coord.pendingResponse(fingerprintHex: fpA)?.responseWire, second.headerWire)
        let complete = try await a.coord.completeIncoming(second.headerWire)
        try assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex)
    }

    func testB13IntakeAcceptsAnInviteAndCompletesAResponse() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")

        let acceptedOutcome = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        let accept = try XCTUnwrap(accepted(acceptedOutcome))

        let completedOutcome = try await a.coord.handleIncoming(accept.headerWire, myDisplayName: "我")
        let complete = try XCTUnwrap(completed(completedOutcome))

        XCTAssertEqual(complete.emoji, accept.emoji)
        try assertSessionsTalk(b, accept.contact.fingerprintHex, a, complete.contact.fingerprintHex)
    }

    func testB14IntakeRejectionsChangeNothing() async throws {
        let a = Env(), b = Env()
        // A already has one contact and one pending invite, so "unchanged" is not vacuous.
        let earlier = try await a.coord.startInvite(myDisplayName: "我")
        _ = try await a.coord.completeIncoming(
            try await b.coord.acceptIncoming(earlier.inviteWire, myDisplayName: "我").headerWire)
        let own = try await a.coord.startInvite(myDisplayName: "我")
        let records = a.pending.records
        let contactBytes = a.sync.defaultsDataForTests
        let writes = a.sessionStore.writes
        let keychainWrites = a.keychain.writeCount

        let cases: [(String, IncomingRejection, String)] = [
            (own.inviteWire, .ownInvite, L10n.pairingErrorOwnCode),
            (sessionCiphertext, .sessionCiphertext, L10n.pairingErrorIsMessage),
            ("hello", .notPairingWire, L10n.pairingErrorNotPairing),
            ("", .notPairingWire, L10n.pairingErrorNotPairing),
        ]
        for (raw, reason, message) in cases {
            let outcome = try await a.coord.handleIncoming(raw, myDisplayName: "我")

            XCTAssertEqual(rejection(outcome), reason, raw)
            XCTAssertEqual(reason.message, message)
            XCTAssertNil(reason.contactId)
            XCTAssertEqual(a.pending.records, records)
            XCTAssertEqual(a.sync.defaultsDataForTests, contactBytes)
            XCTAssertEqual(a.sessionStore.writes, writes)
            XCTAssertEqual(a.keychain.writeCount, keychainWrites)
        }
    }

    func testB15IntakeFindsTheWireInsideCopiedText() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")

        let acceptedOutcome = try await b.coord.handleIncoming("他发来的：\n" + invite.inviteWire + "  \n", myDisplayName: "我")
        let accept = try XCTUnwrap(accepted(acceptedOutcome))
        // Same result as the bare wire: the stored digest is the bare invite's.
        XCTAssertEqual(accept.contact.acceptedInviteDigest, try PairingTransport.inviteDigest(invite.inviteWire))
        let bare = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        XCTAssertEqual(accepted(bare)?.headerWire, accept.headerWire)

        let completedOutcome = try await a.coord.handleIncoming("  他回的：" + accept.headerWire + "\n\n", myDisplayName: "我")
        XCTAssertEqual(completed(completedOutcome)?.emoji, accept.emoji)
    }

    func testB16ForgetPeerDropsOnlyTheResponseRecord() async throws {
        let a = Env(), b = Env(), c = Env()
        let fpA = try await b.coord.acceptIncoming(
            try await a.coord.startInvite(myDisplayName: "我").inviteWire, myDisplayName: "我").contact.fingerprintHex
        let fpC = try await b.coord.acceptIncoming(
            try await c.coord.startInvite(myDisplayName: "我").inviteWire, myDisplayName: "我").contact.fingerprintHex
        let contactsBefore = try b.contacts()

        try await b.coord.forgetPeer(fingerprintHex: fpA)
        try await b.coord.forgetPeer(fingerprintHex: "no-such-peer") // unknown peer: no-op

        XCTAssertNil(b.coord.pendingResponse(fingerprintHex: fpA))
        XCTAssertNotNil(b.coord.pendingResponse(fingerprintHex: fpC))
        XCTAssertEqual(b.responses.records.map(\.fingerprintHex), [fpC])
        // The contacts (digests included) and sessions stay: forgetPeer is the first-message hook.
        XCTAssertEqual(try b.contacts(), contactsBefore)
        XCTAssertNotNil(b.sessionStore.session(fpA))
    }

    func testB17ClearAllPendingEmptiesBothTables() async throws {
        let a = Env(), b = Env()
        _ = try await b.coord.acceptIncoming(try await a.coord.startInvite(myDisplayName: "我").inviteWire, myDisplayName: "我")
        let shared = try await b.coord.startInvite(myDisplayName: "我")
        try await b.coord.markInviteShared(pairingId: shared.pairingId)
        _ = try await b.coord.startInvite(myDisplayName: "我")
        XCTAssertEqual(b.pending.records.count, 2)
        XCTAssertEqual(b.responses.records.count, 1)

        try await b.coord.clearAllPending()

        XCTAssertTrue(b.pending.records.isEmpty)
        XCTAssertTrue(b.responses.records.isEmpty)
    }

    func testB18OnlyTheAcceptingSideRecordsTheInviteDigest() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")

        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        let complete = try await a.coord.completeIncoming(accept.headerWire)

        let digest = try PairingTransport.inviteDigest(invite.inviteWire)
        XCTAssertNotNil(digest.range(of: "^[0-9a-f]{64}$", options: .regularExpression))
        XCTAssertEqual(try b.contacts().first?.acceptedInviteDigest, digest)
        XCTAssertEqual(accept.contact.acceptedInviteDigest, digest)
        XCTAssertNil(try a.contacts().first?.acceptedInviteDigest)
        XCTAssertNil(complete.contact.acceptedInviteDigest)
    }

    /// 接受成功后的落库顺序:会话 → 回应记录 → 联系人,联系人最后写。
    func testB18bAcceptPersistsSessionThenResponseThenContact() async throws {
        final class Log: @unchecked Sendable { var lines: [String] = [] }
        let log = Log()
        let a = Env(), b = Env()
        // The hooks run off the main actor, so look at the persisted bytes rather than the store.
        let responseDefaults = b.responseDefaults
        b.sessionStore.onPut = { _ in
            let there = responseDefaults.data(forKey: "cc.pairing_responses.v1") != nil
            log.lines.append("session(responsePresent=\(there))")
        }
        b.contactStore.onUpsert = { _ in
            let there = responseDefaults.data(forKey: "cc.pairing_responses.v1") != nil
            log.lines.append("contact(responsePresent=\(there))")
        }
        let invite = try await a.coord.startInvite(myDisplayName: "我")

        _ = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")

        XCTAssertEqual(log.lines, ["session(responsePresent=false)", "contact(responsePresent=true)"])
    }

    func testB18cFailedSessionWriteLeavesNothingBehind() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        b.sessionStore.failPut = true

        do {
            _ = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
            XCTFail("expected the session write to fail")
        } catch {
            XCTAssertEqual((error as NSError).domain, "test.session")
        }

        XCTAssertEqual(try b.contacts().count, 0)
        XCTAssertTrue(b.responses.records.isEmpty)
    }

    func testB19InviteOfAnInUseContactIsRefusedWithoutAHandshake() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        let fpA = accept.contact.fingerprintHex
        let complete = try await a.coord.completeIncoming(accept.headerWire)
        try await b.coord.forgetPeer(fingerprintHex: fpA) // A's first message arrived
        let contactsBefore = try b.contacts()

        let outcome = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")

        XCTAssertEqual(rejection(outcome), .alreadyPaired(fingerprintHex: fpA))
        XCTAssertEqual(rejection(outcome)?.message, L10n.pairingErrorAlreadyPaired)
        XCTAssertEqual(rejection(outcome)?.contactId, fpA)
        await assertThrowsRejection(.alreadyPaired(fingerprintHex: fpA)) {
            _ = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        }

        XCTAssertEqual(b.sessionStore.writes, 1) // the in-use session was not overwritten
        XCTAssertEqual(try b.contacts(), contactsBefore)
        XCTAssertEqual(try b.contacts().first?.acceptedInviteDigest, try PairingTransport.inviteDigest(invite.inviteWire))
        XCTAssertNil(b.coord.pendingResponse(fingerprintHex: fpA)) // and no response record was conjured up
        try assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex)
    }

    func testB20RenameAndVerifyKeepTheDigest() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let fpA = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").contact.fingerprintHex

        let store = ContactsStore(sync: b.sync)
        try await store.rename(contactId: fpA, to: "老周")
        try await store.markVerified(contactId: fpA)
        try await b.coord.forgetPeer(fingerprintHex: fpA)

        let outcome = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        XCTAssertEqual(rejection(outcome), .alreadyPaired(fingerprintHex: fpA))
        XCTAssertEqual(b.sessionStore.writes, 1)
        let contact = try XCTUnwrap(try b.contacts().first)
        XCTAssertEqual(contact.displayName, "老周")
        XCTAssertTrue(contact.isVerified)
    }

    // M4: my own invite, no longer pending (completed or deleted), still on the clipboard: it is
    // refused as my own invite — by the inviter's fingerprint in the bundle — with zero writes.
    func testM4OwnInviteNoLongerPendingIsRefusedAsOwn() async throws {
        let a = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        try await a.coord.deleteInvite(pairingId: invite.pairingId)
        XCTAssertTrue(a.pending.records.isEmpty)

        let outcome = try await a.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")

        XCTAssertEqual(rejection(outcome), .ownInvite)
        XCTAssertEqual(a.sessionStore.writes, 0)
        XCTAssertEqual(try a.contacts().count, 0)
        XCTAssertTrue(a.responses.records.isEmpty)
        XCTAssertTrue(a.pending.records.isEmpty)
    }

    func testB21MigratedOwnInviteRecognisedByNonce() async throws {
        let a = Env()
        let minted = try await a.coord.startInvite(myDisplayName: "我")
        let up = a.upgradedFromSingleSlot(minted)
        let before = up.pending.records
        XCTAssertEqual(before.first?.inviteWire, "") // no text to digest: only the nonce can identify it

        let outcome = try await up.coord.handleIncoming(minted.inviteWire, myDisplayName: "我")

        XCTAssertEqual(rejection(outcome), .ownInvite)
        XCTAssertEqual(up.pending.records, before)
        XCTAssertEqual(try up.contacts().count, 0)
        XCTAssertEqual(up.sessionStore.writes, 0)
    }

    // MARK: - A peer who is already a contact is never handshaken with again (B17)

    func testNewerInviteFromAnExistingContactIsRefusedAndTheOldInviteStaysBlocked() async throws {
        let a = Env(), b = Env()
        let invite1 = try await a.coord.startInvite(myDisplayName: "我")
        let accept = try await b.coord.acceptIncoming(invite1.inviteWire, myDisplayName: "我")
        let fpA = accept.contact.fingerprintHex
        let complete = try await a.coord.completeIncoming(accept.headerWire)
        let store = ContactsStore(sync: b.sync)
        try await store.rename(contactId: fpA, to: "老周")
        try await store.markVerified(contactId: fpA)
        try await b.coord.forgetPeer(fingerprintHex: fpA) // A's first message arrived: an in-use contact
        let contactBefore = try XCTUnwrap(try b.contacts().first)
        let writesBefore = b.sessionStore.writes
        // A — on a device that no longer knows B — mints a second invite and sends it.
        let invite2 = try await a.sameIdentityNoData().coord.startInvite(myDisplayName: "我")
        XCTAssertNotEqual(invite2.inviteWire, invite1.inviteWire)

        // Invite 2: a different digest, but the inviter is already a contact.
        let o2 = try await b.coord.handleIncoming(invite2.inviteWire, myDisplayName: "我")
        XCTAssertEqual(rejection(o2), .alreadyPaired(fingerprintHex: fpA))
        await assertThrowsRejection(.alreadyPaired(fingerprintHex: fpA)) {
            _ = try await b.coord.acceptIncoming(invite2.inviteWire, myDisplayName: "我")
        }
        // Invite 1 pasted again by accident: still blocked — its digest was never replaced.
        let o1 = try await b.coord.handleIncoming(invite1.inviteWire, myDisplayName: "我")
        XCTAssertEqual(rejection(o1), .alreadyPaired(fingerprintHex: fpA))

        XCTAssertEqual(b.sessionStore.writes, writesBefore)
        XCTAssertEqual(try b.contacts(), [contactBefore])
        XCTAssertEqual(contactBefore.displayName, "老周")
        XCTAssertTrue(contactBefore.isVerified)
        XCTAssertEqual(contactBefore.acceptedInviteDigest, try PairingTransport.inviteDigest(invite1.inviteWire))
        XCTAssertNil(b.coord.pendingResponse(fingerprintHex: fpA))
        XCTAssertTrue(b.pending.records.isEmpty)
        try assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex) // the original session is intact
    }

    func testResponseFromAnExistingContactIsRefusedAndTheInviteStaysPending() async throws {
        final class Log: @unchecked Sendable { var lines: [String] = [] }
        let log = Log()
        let a = Env(), b = Env()
        b.keyMaterial = SpyKeyMaterial { log.lines.append("invalidate:\($0.pairingId)") }
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        let fpA = accept.contact.fingerprintHex
        let complete = try await a.coord.completeIncoming(accept.headerWire)
        let store = ContactsStore(sync: b.sync)
        try await store.rename(contactId: fpA, to: "老周")
        try await store.markVerified(contactId: fpA)
        try await b.coord.forgetPeer(fingerprintHex: fpA)
        // B has an invite of its own out, and A (from a device that does not know B) answers it.
        let myInvite = try await b.coord.startInvite(myDisplayName: "我")
        let responseFromA = try await a.sameIdentityNoData().coord.acceptIncoming(myInvite.inviteWire, myDisplayName: "我").headerWire
        let contactBefore = try XCTUnwrap(try b.contacts().first)
        let writesBefore = b.sessionStore.writes
        let pendingBytes = b.pendingDefaults.data(forKey: "cc.pending_pairings.v2")

        // B accepted A's invite earlier, so this is a mutual invite: the rejection names B's matched invite.
        let outcome = try await b.coord.handleIncoming(responseFromA, myDisplayName: "我")
        XCTAssertEqual(rejection(outcome), .alreadyPaired(fingerprintHex: fpA, matchedPairingId: myInvite.pairingId))
        await assertThrowsRejection(.alreadyPaired(fingerprintHex: fpA, matchedPairingId: myInvite.pairingId)) {
            _ = try await b.coord.completeIncoming(responseFromA)
        }

        // The invite is neither completed nor removed, and its key material was not invalidated.
        XCTAssertEqual(b.pending.records, [myInvite])
        XCTAssertEqual(b.pendingDefaults.data(forKey: "cc.pending_pairings.v2"), pendingBytes)
        XCTAssertEqual(log.lines, [])
        XCTAssertEqual(b.sessionStore.writes, writesBefore)
        // The contact — digest included — is exactly as it was …
        XCTAssertEqual(try b.contacts(), [contactBefore])
        XCTAssertEqual(contactBefore.acceptedInviteDigest, try PairingTransport.inviteDigest(invite.inviteWire))
        XCTAssertTrue(contactBefore.isVerified)
        XCTAssertEqual(contactBefore.displayName, "老周")
        // … so A's old invite is still blocked, and the original session still works.
        let again = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        XCTAssertEqual(rejection(again), .alreadyPaired(fingerprintHex: fpA))
        XCTAssertEqual(b.sessionStore.writes, writesBefore)
        try assertSessionsTalk(b, fpA, a, complete.contact.fingerprintHex)
    }

    func testResponseFromAContactWhoseInviteIDidNotAcceptNamesNoInvite() async throws {
        let a = Env(), b = Env()
        // B invited A and completed: B's contact for A carries no accepted-invite digest.
        let firstInvite = try await b.coord.startInvite(myDisplayName: "我")
        let firstAccept = try await a.coord.acceptIncoming(firstInvite.inviteWire, myDisplayName: "我")
        let fpA = try await b.coord.completeIncoming(firstAccept.headerWire).contact.fingerprintHex
        let myInvite = try await b.coord.startInvite(myDisplayName: "我")
        let responseFromA = try await a.sameIdentityNoData().coord.acceptIncoming(myInvite.inviteWire, myDisplayName: "我").headerWire

        let outcome = try await b.coord.handleIncoming(responseFromA, myDisplayName: "我")
        XCTAssertEqual(rejection(outcome), .alreadyPaired(fingerprintHex: fpA))
        XCTAssertEqual(b.pending.records, [myInvite])
    }

    func testAfterDeletingTheContactTheSameResponseCompletes() async throws {
        let a = Env(), b = Env()
        let fpA = try await b.coord.acceptIncoming(
            try await a.coord.startInvite(myDisplayName: "我").inviteWire, myDisplayName: "我").contact.fingerprintHex
        let myInvite = try await b.coord.startInvite(myDisplayName: "我")
        let aElsewhere = a.sameIdentityNoData()
        let acceptByA = try await aElsewhere.coord.acceptIncoming(myInvite.inviteWire, myDisplayName: "我")
        // (While the contact exists this reply is refused or resolved as a mutual invite — covered elsewhere.)

        // Deleting the contact is the one way to re-pair. Done WITHOUT forgetPeer, so the response
        // B stored for A is left behind …
        try await b.deleteContact(fpA)
        XCTAssertNotNil(b.coord.pendingResponse(fingerprintHex: fpA))

        let complete = try await b.coord.completeIncoming(acceptByA.headerWire)

        XCTAssertEqual(complete.contact.fingerprintHex, fpA)
        XCTAssertTrue(b.pending.records.isEmpty)
        XCTAssertNil(try b.contacts().first?.acceptedInviteDigest)
        // … and must not outlive its session: no 「回暗号还没发出去」 row for the new contact.
        XCTAssertNil(b.coord.pendingResponse(fingerprintHex: fpA))
        XCTAssertEqual(pendingItems(invites: b.pending.records, responses: b.responses.records,
                                    contactIds: [fpA], nowMillis: b.clock.ms), [])
        try assertSessionsTalk(aElsewhere, acceptByA.contact.fingerprintHex, b, fpA)
    }

    func testFailedContactWriteLeavesAnIgnorableOrphanAndARepasteHandshakesAfresh() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")

        b.contactStore.failWrites = true
        do {
            _ = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
            XCTFail("expected the contact write to fail")
        } catch {
            XCTAssertEqual((error as NSError).domain, "test.contacts")
        }

        // What is left: a session and a response record, no contact. The record is invisible
        // without its contact, and nothing blocks the invite.
        XCTAssertEqual(try b.contacts().count, 0)
        let orphan = try XCTUnwrap(b.responses.records.first)
        XCTAssertEqual(b.sessionStore.writes, 1)
        XCTAssertEqual(pendingItems(invites: b.pending.records, responses: [orphan], contactIds: [], nowMillis: b.clock.ms), [])
        XCTAssertTrue(unsentResponseFingerprints(responses: [orphan], contactIds: []).isEmpty)

        b.contactStore.failWrites = false
        let retry = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")

        XCTAssertNotEqual(retry.headerWire, orphan.responseWire) // a fresh handshake, not the orphan
        XCTAssertEqual(b.sessionStore.writes, 2)
        XCTAssertEqual(try b.contacts().map(\.id), [retry.contact.fingerprintHex])
        XCTAssertEqual(b.responses.records.map(\.responseWire), [retry.headerWire])
        let complete = try await a.coord.completeIncoming(retry.headerWire)
        try assertSessionsTalk(b, retry.contact.fingerprintHex, a, complete.contact.fingerprintHex)
    }

    // MARK: - A damaged record never takes the whole match down

    func testDamagedRecordsAreSkippedAndALaterRecordStillMatches() async throws {
        let a = Env(), b = Env(), c = Env()
        // Three kinds of damage, all ahead of the real invite: nonce is not base64, nonce is valid
        // base64 but the wrong length, and an invite text that does not decode.
        try a.pending.append(PendingPairingRecord(
            pairingId: "damaged-b64", pairingNonceB64: "!!not base64!!", createdAtMillis: 1,
            inviteWire: "🔒not-a-decodable-invite", lastSharedAtMillis: 1))
        try a.pending.append(PendingPairingRecord(
            pairingId: "damaged-len", pairingNonceB64: "AAAA", createdAtMillis: 2,
            inviteWire: "", lastSharedAtMillis: 2))
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        XCTAssertEqual(a.pending.records.map(\.pairingId), ["damaged-b64", "damaged-len", invite.pairingId])

        // Own-invite check: the damaged records neither match nor abort the comparison.
        let own = try await a.coord.handleIncoming(invite.inviteWire, myDisplayName: "我")
        XCTAssertEqual(rejection(own), .ownInvite)
        let cInvite = try await c.coord.startInvite(myDisplayName: "我")
        let foreign = try await a.coord.handleIncoming(cInvite.inviteWire, myDisplayName: "我")
        XCTAssertNotNil(accepted(foreign))

        // Trial: skipped, and the record after them completes.
        let accept = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我")
        let complete = try await a.coord.completeIncoming(accept.headerWire)

        XCTAssertEqual(complete.emoji, accept.emoji)
        XCTAssertEqual(a.pending.records.map(\.pairingId), ["damaged-b64", "damaged-len"]) // untouched
    }

    func testCompleteWithoutTheSpkThrowsAndWritesNothing() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").headerWire
        try a.keychain.delete(key: "spk_active_v1")
        let keychainWrites = a.keychain.writeCount

        // Not provisioned afresh (a new SPK could never match the invite) — a genuine failure.
        do {
            _ = try await a.coord.handleIncoming(response, myDisplayName: "我")
            XCTFail("expected a state error")
        } catch PairingError.state {
            // expected
        }

        XCTAssertNil(a.keychain.items["spk_active_v1"])
        XCTAssertEqual(a.keychain.writeCount, keychainWrites)
        XCTAssertEqual(a.pending.records, [invite])
        XCTAssertEqual(try a.contacts().count, 0)
        XCTAssertEqual(a.sessionStore.writes, 0)
    }

    // MARK: - Beyond table B

    func testUndecodableResponseIsAHandshakeFailureNotAMissingInvite() async throws {
        let a = Env()
        let garbled = PairingTransport.headerToWire(Data([0x01, 0x02, 0x03]))
        XCTAssertEqual(a.coord.classifyIncoming(garbled), .pairingHeader)

        // With no invite on record …
        do {
            _ = try await a.coord.handleIncoming(garbled, myDisplayName: "我")
            XCTFail("expected a handshake failure")
        } catch let r as IncomingRejection {
            XCTFail("a garbled response is not a rejection: \(r)")
        } catch {}

        // … and with one: thrown either way, and the invite survives.
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        do {
            _ = try await a.coord.handleIncoming(garbled, myDisplayName: "我")
            XCTFail("expected a handshake failure")
        } catch let r as IncomingRejection {
            XCTFail("a garbled response is not a rejection: \(r)")
        } catch {}
        XCTAssertEqual(a.pending.records, [invite])
        XCTAssertEqual(try a.contacts().count, 0)
        XCTAssertEqual(a.sessionStore.writes, 0)
    }

    func testUpdateNoteRejectsAnOverLongNoteAndStoresNothing() async throws {
        let a = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let before = a.pendingDefaults.data(forKey: "cc.pending_pairings.v2")

        await assertThrowsAny { try await a.coord.updateNote(pairingId: invite.pairingId, note: "一二三四五六七八九") } // 27 bytes
        XCTAssertNil(a.coord.pendingInvite(id: invite.pairingId)?.note)
        XCTAssertEqual(a.pendingDefaults.data(forKey: "cc.pending_pairings.v2"), before)

        try await a.coord.updateNote(pairingId: invite.pairingId, note: "  老周\n")
        XCTAssertEqual(a.coord.pendingInvite(id: invite.pairingId)?.note, "老周")
        try await a.coord.updateNote(pairingId: invite.pairingId, note: "   ") // cleared → un-noted again
        XCTAssertNil(a.coord.pendingInvite(id: invite.pairingId)?.note)
        XCTAssertNil(a.coord.pendingInvite(id: "no-such-id"))
    }

    // MARK: - Review round 1: the gate is process-wide, and every writer goes through it

    /// 生产里每个入口各自 `makeDefault()`:两个不同的协调器实例并发处理同一邀请,也只能握手一次。
    func testTwoCoordinatorInstancesHandshakeOnceForTheSameInvite() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let c1 = b.makeCoordinator(), c2 = b.makeCoordinator()

        async let r1 = c1.handleIncoming(invite.inviteWire, myDisplayName: "我")
        async let r2 = c2.handleIncoming(invite.inviteWire, myDisplayName: "我")
        let outcomes = try await [r1, r2]

        let wires = outcomes.compactMap(accepted).map(\.headerWire)
        XCTAssertEqual(wires.count, 2)
        XCTAssertEqual(Set(wires).count, 1) // one response, handed out twice
        XCTAssertEqual(b.sessionStore.writes, 1) // one handshake, one session write
        XCTAssertEqual(b.responses.records.count, 1)
        XCTAssertEqual(try b.contacts().count, 1)
    }

    func testTwoCoordinatorInstancesCompleteTheSameResponseOnce() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").headerWire
        let c1 = a.makeCoordinator(), c2 = a.makeCoordinator()

        async let r1 = c1.handleIncoming(response, myDisplayName: "我")
        async let r2 = c2.handleIncoming(response, myDisplayName: "我")
        let outcomes = try await [r1, r2]

        XCTAssertEqual(outcomes.compactMap(completed).count, 1)
        XCTAssertEqual(outcomes.compactMap(rejection).filter { $0 == .noMatchingInvite }.count, 1)
        XCTAssertEqual(a.sessionStore.writes, 1)
        XCTAssertEqual(try a.contacts().count, 1)
    }

    /// 完成挂在「写会话」上时,这条邀请的记录被别处删掉:不建联系人,已写的会话回滚。
    func testInviteDeletedWhileCompletionIsSuspendedCreatesNoContactAndRollsBackTheSession() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").headerWire
        let reached = Latch(), release = Latch()
        a.sessionStore.suspendInPut = { await reached.open(); await release.wait() }

        let task = Task { try await a.coord.completeIncoming(response) }
        await reached.wait()
        try a.pending.remove(id: invite.pairingId) // a writer that does not go through the coordinator
        await release.open()

        do {
            _ = try await task.value
            XCTFail("expected the completion to abort")
        } catch let r as IncomingRejection {
            XCTAssertEqual(r, .noMatchingInvite)
        }
        XCTAssertEqual(try a.contacts().count, 0)
        XCTAssertEqual(a.sessionStore.sessionCount, 0) // the session written a moment ago is gone again
        XCTAssertTrue(a.pending.records.isEmpty)
    }

    /// 账号擦除(clearAllPending)与挂起中的接受交错:擦除排在接受之后,回应记录不会被复活。
    func testClearAllPendingWaitsForAnInFlightAccept() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let reached = Latch(), release = Latch()
        b.sessionStore.suspendInPut = { await reached.open(); await release.wait() }

        let acceptTask = Task { try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我") }
        await reached.wait()
        let clearTask = Task { try await b.makeCoordinator().clearAllPending() }
        for _ in 0..<20 { await Task.yield() } // give the clear every chance to jump the queue
        await release.open()
        _ = try await acceptTask.value
        try await clearTask.value

        // The clear ran strictly after the accept: had it run in the middle, the accept's response
        // record (written afterwards) would have survived the wipe.
        XCTAssertTrue(b.responses.records.isEmpty)
        XCTAssertTrue(b.pending.records.isEmpty)
    }

    func testDeleteInviteWaitsForAnInFlightCompletion() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "我")
        let response = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "我").headerWire
        let reached = Latch(), release = Latch()
        a.sessionStore.suspendInPut = { await reached.open(); await release.wait() }

        let completeTask = Task { try await a.coord.completeIncoming(response) }
        await reached.wait()
        let order = OrderLog()
        let deleteTask = Task {
            try await a.makeCoordinator().deleteInvite(pairingId: invite.pairingId)
            order.add("delete")
        }
        for _ in 0..<20 { await Task.yield() }
        order.add("release")
        await release.open()
        _ = try await completeTask.value
        order.add("completed")
        try await deleteTask.value

        // The delete could not finish before the suspended completion was released.
        XCTAssertEqual(order.lines.first, "release")
        XCTAssertEqual(try a.contacts().count, 1)
        XCTAssertTrue(a.pending.records.isEmpty)
    }

    // MARK: - Review round 1: minor

    /// 两半各自尝试、抛第一个错误:邀请那一半作废失败时,回应表照样已清,错误照样上抛。
    /// (回应表的写入走不抛错的 `AppGroupDefaults.set`,没有注入失败的接缝,所以只测这一个方向。)
    func testClearAllPendingStillClearsResponsesWhenTheInviteHalfFails() async throws {
        let a = Env(), b = Env()
        b.keyMaterial = SpyKeyMaterial { _ in throw NSError(domain: "test.secureStorage", code: 7) }
        _ = try await b.coord.acceptIncoming(try await a.coord.startInvite(myDisplayName: "我").inviteWire,
                                             myDisplayName: "我")
        let mine = try await b.coord.startInvite(myDisplayName: "我")
        XCTAssertEqual(b.responses.records.count, 1)

        do {
            try await b.coord.clearAllPending()
            XCTFail("expected the invalidation failure")
        } catch {
            XCTAssertEqual((error as NSError).domain, "test.secureStorage")
        }

        XCTAssertTrue(b.responses.records.isEmpty)
        XCTAssertEqual(b.pending.records.map(\.pairingId), [mine.pairingId]) // the record whose invalidation failed stays
    }

    /// 跨端已知向量:固定负载字节 → 固定摘要(Android 用同一组断言)。
    func testInviteDigestKnownVector() throws {
        let payload = Data((0..<32).map { UInt8($0) })
        XCTAssertEqual(payload.map { String(format: "%02x", $0) }.joined(),
                       "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        let wire = PairingTransport.bundleToWire(payload)
        XCTAssertEqual(try PairingTransport.inviteDigest(wire),
                       "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd")
        // 前面带着复制来的文字也一样。
        XCTAssertEqual(try PairingTransport.inviteDigest(PairingTransport.locate("他发来的：\n" + wire + "\n")),
                       "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd")
    }

    func testRejectionCopyComesFromL10n() {
        XCTAssertEqual(IncomingRejection.noMatchingInvite.message, L10n.pairingErrorNoMatchingInvite)
        XCTAssertEqual(IncomingRejection.sessionCiphertext.message, L10n.pairingErrorIsMessage)
        XCTAssertEqual(IncomingRejection.ownInvite.message, L10n.pairingErrorOwnCode)
        XCTAssertEqual(IncomingRejection.notPairingWire.message, L10n.pairingErrorNotPairing)
        XCTAssertEqual(IncomingRejection.alreadyPaired(fingerprintHex: "ff").message, L10n.pairingErrorAlreadyPaired)
        XCTAssertEqual(IncomingRejection.mutualInvite(fingerprintHex: "ff", retiredPairingId: "p").message,
                       L10n.pairingMutualInviteResolved)
    }

    /// 面对面只扫二维码:发起方从未点分享/复制(邀请未标已分享),对方扫码接受后,发起方贴回对方的配对码照样完成。
    func testUnsharedInviteStillCompletesAfterQrOnlyExchange() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "")
        XCTAssertNil(a.pending.record(id: invite.pairingId)?.lastSharedAtMillis)
        let accepted = try await b.coord.acceptIncoming(invite.inviteWire, myDisplayName: "")

        let outcome = try await a.coord.handleIncoming(accepted.headerWire, myDisplayName: "")

        guard case .completed = outcome else { return XCTFail("expected completed, got \(outcome)") }
        XCTAssertNil(a.pending.record(id: invite.pairingId), "完成的配对码离开配对中")
    }

    // MARK: - Mutual invites

    /// Several messages alternating both ways, each decrypted by the other side's stored session.
    private func assertConversation(_ x: Env, _ xPeerFp: String, _ y: Env, _ yPeerFp: String,
                                    file: StaticString = #filePath, line: UInt = #line) throws {
        for i in 0..<3 {
            for (from, fromPeer, to, toPeer) in [(x, xPeerFp, y, yPeerFp), (y, yPeerFp, x, xPeerFp)] {
                let msg = Data("message \(i)".utf8)
                let ct = try XCTUnwrap(from.sessionStore.session(fromPeer), file: file, line: line).encryptToBytes(plaintext: msg)
                let dec = try XCTUnwrap(to.sessionStore.session(toPeer), file: file, line: line).decryptFromBytes(ciphertext: ct)
                XCTAssertEqual(dec, msg, file: file, line: line)
            }
        }
    }

    /// Both tap 「+」, each pastes the OTHER's invite (each becomes the accepter of a different handshake),
    /// then each pastes the reply the other sent back — in either order. They converge on ONE session:
    /// the higher fingerprint's invite. The higher side completes it (replacing the session it got by
    /// accepting), the lower side keeps its session and retires its own invite.
    private func runMutualInvites(higherPastesFirst: Bool) async throws {
        let a = Env(), b = Env()
        let inviteA = try await a.coord.startInvite(myDisplayName: "alice")
        let inviteB = try await b.coord.startInvite(myDisplayName: "bob")
        let outA = try await a.coord.handleIncoming(inviteB.inviteWire, myDisplayName: "alice")
        let outB = try await b.coord.handleIncoming(inviteA.inviteWire, myDisplayName: "bob")
        let replyFromA = try XCTUnwrap(accepted(outA))
        let replyFromB = try XCTUnwrap(accepted(outB))
        let fpB = replyFromA.contact.fingerprintHex, fpA = replyFromB.contact.fingerprintHex
        XCTAssertNotEqual(replyFromA.emoji, replyFromB.emoji, "two unrelated handshakes")

        let (hi, hiFp, hiInvite, replyToHi) = fpA > fpB ? (a, fpA, inviteA, replyFromB) : (b, fpB, inviteB, replyFromA)
        let (lo, loFp, loInvite, replyToLo) = fpA > fpB ? (b, fpB, inviteB, replyFromA) : (a, fpA, inviteA, replyFromB)
        // The higher side renamed and (wrongly) marked the first emoji as matching before the reply came.
        try await ContactsStore(sync: hi.sync).rename(contactId: loFp, to: "老周")
        try await ContactsStore(sync: hi.sync).markVerified(contactId: loFp)

        func pasteAtHi() async throws {
            let outcome = try await hi.coord.handleIncoming(replyToHi.headerWire, myDisplayName: "")
            let done = try XCTUnwrap(completed(outcome), "higher side completes its own invite, got \(outcome)")
            XCTAssertEqual(done.emoji, replyToHi.emoji, "the emoji the lower side already shows")
        }
        func pasteAtLo() async throws {
            let outcome = try await lo.coord.handleIncoming(replyToLo.headerWire, myDisplayName: "")
            XCTAssertEqual(rejection(outcome), .mutualInvite(fingerprintHex: hiFp, retiredPairingId: loInvite.pairingId))
            XCTAssertEqual(rejection(outcome)?.message, L10n.pairingMutualInviteResolved)
            XCTAssertEqual(rejection(outcome)?.contactId, hiFp)
        }
        if higherPastesFirst {
            try await pasteAtHi(); try await pasteAtLo()
        } else {
            try await pasteAtLo(); try await pasteAtHi()
        }

        // No invite left on either side; one contact each, showing the same emoji.
        XCTAssertTrue(hi.pending.records.isEmpty)
        XCTAssertTrue(lo.pending.records.isEmpty)
        let hiContact = try XCTUnwrap(try hi.contacts().first { $0.id == loFp })
        let loContact = try XCTUnwrap(try lo.contacts().first { $0.id == hiFp })
        XCTAssertEqual(try hi.contacts().count, 1)
        XCTAssertEqual(try lo.contacts().count, 1)
        XCTAssertEqual(hiContact.emoji, loContact.emoji)
        XCTAssertEqual(hiContact.emoji, replyToHi.emoji)
        // Higher side: name kept, verification reset (it was for the discarded session), now the inviter,
        // and its response to the lower side's invite is gone (it can never complete).
        XCTAssertEqual(hiContact.displayName, "老周")
        XCTAssertFalse(hiContact.isVerified)
        XCTAssertNil(hiContact.acceptedInviteDigest)
        XCTAssertNil(hi.responses.record(for: loFp))
        // Lower side: still the accepter of the higher side's invite; its reply stays resendable.
        XCTAssertEqual(loContact.acceptedInviteDigest, try PairingTransport.inviteDigest(hiInvite.inviteWire))
        XCTAssertEqual(lo.responses.record(for: hiFp)?.responseWire, replyToHi.headerWire)

        try assertConversation(hi, loFp, lo, hiFp)

        // Nothing stale can start a second session: both replies and both invites again change nothing.
        let hiWrites = hi.sessionStore.writes, loWrites = lo.sessionStore.writes
        let staleAtHi = try await hi.coord.handleIncoming(replyToHi.headerWire, myDisplayName: "")
        let staleAtLo = try await lo.coord.handleIncoming(replyToLo.headerWire, myDisplayName: "")
        let inviteAgainAtHi = try await hi.coord.handleIncoming(loInvite.inviteWire, myDisplayName: "")
        let inviteAgainAtLo = try await lo.coord.handleIncoming(hiInvite.inviteWire, myDisplayName: "")
        XCTAssertEqual(rejection(staleAtHi), .noMatchingInvite)
        XCTAssertEqual(rejection(staleAtLo), .noMatchingInvite)
        XCTAssertEqual(rejection(inviteAgainAtHi), .alreadyPaired(fingerprintHex: loFp))
        let again = try XCTUnwrap(accepted(inviteAgainAtLo))
        XCTAssertEqual(again.headerWire, replyToHi.headerWire, "the stored reply, no new handshake")
        XCTAssertEqual(hi.sessionStore.writes, hiWrites)
        XCTAssertEqual(lo.sessionStore.writes, loWrites)
        try assertConversation(lo, hiFp, hi, loFp)
    }

    func testMutualInvitesConvergeWhenTheHigherSidePastesFirst() async throws {
        try await runMutualInvites(higherPastesFirst: true)
    }

    func testMutualInvitesConvergeWhenTheLowerSidePastesFirst() async throws {
        try await runMutualInvites(higherPastesFirst: false)
    }

    /// The lower side's invite is retired before the record goes: invalidate → remove; a failed
    /// invalidation keeps the record and propagates.
    func testMutualInviteRetirementInvalidatesFirst() async throws {
        let a = Env(), b = Env()
        let inviteA = try await a.coord.startInvite(myDisplayName: "")
        let inviteB = try await b.coord.startInvite(myDisplayName: "")
        let replyFromA = try await a.coord.acceptIncoming(inviteB.inviteWire, myDisplayName: "")
        let replyFromB = try await b.coord.acceptIncoming(inviteA.inviteWire, myDisplayName: "")
        let aIsLower = replyFromB.contact.fingerprintHex < replyFromA.contact.fingerprintHex
        let lo = aIsLower ? a : b
        let replyToLo = aIsLower ? replyFromB : replyFromA
        lo.keyMaterial = SpyKeyMaterial { _ in throw NSError(domain: "test.invalidate", code: 1) }
        let coord = lo.makeCoordinator()
        await assertThrowsAny { _ = try await coord.handleIncoming(replyToLo.headerWire, myDisplayName: "") }
        XCTAssertEqual(lo.pending.records.count, 1, "the record whose invalidation failed stays")
    }

    /// One invite, accepted and completed: unaffected by the mutual-invite rule even while the accepter's
    /// response is still on file.
    func testSingleInviteFlowStillCompletesNormally() async throws {
        let a = Env(), b = Env()
        let invite = try await a.coord.startInvite(myDisplayName: "alice")
        let acceptOut = try await b.coord.handleIncoming(invite.inviteWire, myDisplayName: "bob")
        let accept = try XCTUnwrap(accepted(acceptOut))
        let doneOut = try await a.coord.handleIncoming(accept.headerWire, myDisplayName: "alice")
        let done = try XCTUnwrap(completed(doneOut))
        XCTAssertEqual(done.emoji, accept.emoji)
        try assertConversation(a, done.contact.fingerprintHex, b, accept.contact.fingerprintHex)
    }

    /// 「对方先发了他的?」: I minted and shared my invite, then pasted theirs instead; they never saw mine.
    /// I am the accepter, they complete; my unused invite stays pending and changes nothing.
    func testTheySentTheirsFirstPathStillWorks() async throws {
        let a = Env(), b = Env()
        let mine = try await b.coord.startInvite(myDisplayName: "bob")
        try await b.coord.markInviteShared(pairingId: mine.pairingId)
        let theirs = try await a.coord.startInvite(myDisplayName: "alice")
        let acceptOut = try await b.coord.handleIncoming(theirs.inviteWire, myDisplayName: "bob")
        let accept = try XCTUnwrap(accepted(acceptOut))
        let doneOut = try await a.coord.handleIncoming(accept.headerWire, myDisplayName: "alice")
        let done = try XCTUnwrap(completed(doneOut))
        XCTAssertEqual(done.emoji, accept.emoji)
        XCTAssertEqual(b.pending.records.map(\.pairingId), [mine.pairingId])
        try assertConversation(a, done.contact.fingerprintHex, b, accept.contact.fingerprintHex)
    }
}

// MARK: - Test-only helpers

private extension AppGroupSync {
    /// The persisted contacts, for "nothing was written" assertions (JSON key order is not stable
    /// across encodes, so compare decoded values rather than bytes).
    var defaultsDataForTests: [AppGroupContact]? { try? readContacts() }
}
