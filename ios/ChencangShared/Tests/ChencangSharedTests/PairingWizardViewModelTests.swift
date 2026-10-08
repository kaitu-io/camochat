import XCTest
import Combine
@testable import ChencangShared
import Chencang

/// Drives ``PairingWizardViewModel`` against a REAL ``PairingCoordinator`` (a concrete class, not a
/// protocol). Each ``Rig`` is one "device": its own identity / SPK, pairing stores, contact mirror and
/// session store; the wizard's own ``ContactsStore`` reads the SAME mirror the coordinator writes, so what
/// a test sees is what production sees.
///
/// Cases W1–W14 are the cross-platform wizard table (spec 2026-10-01-three-tab-shell, 规 W / 表 W) plus
/// the iOS-side items of Task 6: the re-pasted-invite verified-flag regression, the 对印-reject cleanup,
/// the "gone" terminal page, resuming a held invite after a rebuild, and the visible delete failure.
///
/// `@MainActor` so the post-quantum-adjacent keygen inside the real pairing engine runs on the main
/// thread's big stack — same reasoning as every other pairing test in this target.
@MainActor
final class PairingWizardViewModelTests: XCTestCase {

    // MARK: - Fakes

    private final class FakeIdentityProvider: InbandIdentityProviding, @unchecked Sendable {
        private let blob: Data
        init(_ id: SecretIdentity) { self.blob = id.serializeForLocalStorage() }
        @MainActor func loadIdentity() throws -> SecretIdentity { try SecretIdentity.fromLocalStorage(data: blob) }
    }

    private final class CountingSessions: PairedSessionPersisting, @unchecked Sendable {
        private let lock = NSLock()
        private var _writes = 0
        var writes: Int { lock.lock(); defer { lock.unlock() }; return _writes }
        func put(_ session: Session, peerId: String) async throws {
            lock.lock(); defer { lock.unlock() }
            _writes += 1
        }
        func remove(peerId: String) async {}
    }

    /// The production App Group persister, counting how many lookups the coordinator ran.
    private final class CountingContacts: PairedContactPersisting, @unchecked Sendable {
        let inner: AppGroupContactPersister
        private let lock = NSLock()
        private var _finds = 0
        var finds: Int { lock.lock(); defer { lock.unlock() }; return _finds }
        init(sync: AppGroupSync) { inner = AppGroupContactPersister(sync: sync) }
        private func bump() { lock.lock(); _finds += 1; lock.unlock() }
        func upsert(_ contact: PairedContact) async throws { try await inner.upsert(contact) }
        func find(fingerprintHex: String) async -> PairedContact? { bump(); return await inner.find(fingerprintHex: fingerprintHex) }
        func find(acceptedInviteDigest: String) async -> PairedContact? { bump(); return await inner.find(acceptedInviteDigest: acceptedInviteDigest) }
    }

    private struct ThrowingKeyMaterial: InviteKeyMaterialInvalidating {
        func invalidate(_ record: PendingPairingRecord) throws { throw NSError(domain: "test.keymaterial", code: 1) }
    }

    /// One device.
    @MainActor
    private final class Rig {
        let coordinator: PairingCoordinator
        let invites = PendingInviteStore(defaults: FakeAppGroupDefaults())
        let responses = PairingResponseStore(defaults: FakeAppGroupDefaults())
        let sessions = CountingSessions()
        let contacts: CountingContacts
        let sync = AppGroupSync(defaults: FakeAppGroupDefaults())
        let contactsStore: ContactsStore
        let sessionStore: SessionStore
        let keychain = FakeKeychain()

        init(keyMaterial: InviteKeyMaterialInvalidating = NoInviteKeyMaterial()) {
            let ik = SecretIdentity()
            let spkKeychain = FakeKeychain()
            let defaults = UserDefaults(suiteName: "pairing-wizard-vm-test-\(UUID().uuidString)")!
            let store = SignedPreKeyStore(keychain: spkKeychain)
            let spk = try! SecretSignedPreKey(ik: ik, spkVersion: UInt32(PrekeyProvisioner.spkVersion))
            try! store.save(spk.serialize())
            defaults.set(PrekeyProvisioner.spkVersion, forKey: "chencang.spk_version")
            contacts = CountingContacts(sync: sync)
            contactsStore = ContactsStore(sync: sync)
            sessionStore = SessionStore(keychain: keychain)
            coordinator = PairingCoordinator(
                identity: FakeIdentityProvider(ik),
                prekeyProvisioner: PrekeyProvisioner(store: store, defaults: defaults),
                sessionStore: sessions,
                contactStore: contacts,
                pending: invites,
                responses: responses,
                keyMaterial: keyMaterial
            )
        }
    }

    /// 删除联系人时可按需写失败的联系人库。
    @MainActor
    private final class FailingRemoveContactsStore: ContactsStore {
        var failRemove = false
        override func remove(contactId: String) async throws {
            if failRemove { throw PairingError.malformed("injected remove failure") }
            try await super.remove(contactId: contactId)
        }
    }

    /// The "my name" App Group slot as the wizard sees it: read at call time, written by the name prompt.
    private final class Profile {
        var name: String
        var done: Bool
        var saves: [String?] = []
        init(name: String = "", done: Bool = true) { self.name = name; self.done = done }
        func save(_ n: String?) {
            saves.append(n)
            if let n { name = n }
            done = true
        }
    }

    private func makeVM(
        _ entry: WizardEntry,
        _ rig: Rig,
        name: String = "",
        profile: Profile? = nil,
        held: String? = nil,
        contactsStore: ContactsStore? = nil,
        onHeld: @escaping (String?) -> Void = { _ in },
        onDiscardable: @escaping (String?) -> Void = { _ in },
        onWipeThread: @escaping (String) -> Void = { _ in },
        route: @escaping @MainActor (String) async -> IntakeRoute = { _ in .failed(.cannotOpen) }
    ) -> PairingWizardViewModel {
        PairingWizardViewModel(
            entry: entry,
            coordinator: rig.coordinator,
            contactsStore: contactsStore ?? rig.contactsStore,
            sessionStore: rig.sessionStore,
            myDisplayName: { profile?.name ?? name },
            namePromptDone: { profile?.done ?? true },
            saveName: { profile?.save($0) },
            heldInviteId: held,
            onInviteHeld: onHeld,
            onInviteDiscardable: onDiscardable,
            onWipeThread: onWipeThread,
            routeSessionMessage: route
        )
    }

    private func notice(_ vm: PairingWizardViewModel, file: StaticString = #filePath, line: UInt = #line) -> ReceiveNotice? {
        guard case let .receive(n) = vm.ui.stage else {
            XCTFail("expected receive, got \(vm.ui.stage)", file: file, line: line)
            return nil
        }
        return n
    }

    private let recvFirst = [L10n.pairingStepEnter, L10n.pairingStepSend, L10n.pairingStepVerify]
    private let showFirst = [L10n.pairingStepSend, L10n.pairingStepEnter, L10n.pairingStepVerify]

    // MARK: - Full round trips (existing behaviour, now through handleIncoming)

    func testWizardInviteAndReplyCarryTheNameReadAtSendTime() async throws {
        // Initiator wizard: the invite carries the App Group name.
        let a = Rig(), b = Rig()
        let vmA = PairingWizardViewModel(
            entry: .initiator, coordinator: a.coordinator, contactsStore: a.contactsStore,
            sessionStore: a.sessionStore, myDisplayName: { "阿青" }, namePromptDone: { true }, saveName: { _ in })
        await vmA.start()
        guard case let .show(wire, _) = vmA.ui.stage else { return XCTFail("expected show, got \(vmA.ui.stage)") }
        let acc = try await b.coordinator.acceptIncoming(wire, myDisplayName: "bob")
        XCTAssertEqual(acc.contact.displayName, "阿青")

        // Redeemer wizard: the reply carries the name as it is when the invite is pasted.
        let c = Rig(), d = Rig()
        var dName = ""
        let vmD = PairingWizardViewModel(
            entry: .redeemer, coordinator: d.coordinator, contactsStore: d.contactsStore,
            sessionStore: d.sessionStore, myDisplayName: { dName }, namePromptDone: { true }, saveName: { _ in })
        await vmD.start()
        dName = "小李"
        let invite = try await c.coordinator.startInvite(myDisplayName: "carol")
        await vmD.submitWire(invite.inviteWire)
        guard case let .show(reply, true) = vmD.ui.stage else { return XCTFail("expected reply, got \(vmD.ui.stage)") }
        let done = try await c.coordinator.completeIncoming(reply)
        XCTAssertEqual(done.contact.displayName, "小李")
    }

    func testInitiatorFullPathShowsInviteReceivesResponseConfirmsAndOpensThread() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.initiator, a, name: "alice")

        await vm.start()
        XCTAssertEqual(vm.ui.stepIndex, 0)
        guard case let .show(wire, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(wire.hasPrefix("🔒"))
        XCTAssertFalse(isResponse)

        await vm.handOffDone(faceToFace: false)
        XCTAssertEqual(vm.ui.stepIndex, 1)
        XCTAssertNil(notice(vm))

        let bAccept = try await b.coordinator.acceptIncoming(wire, myDisplayName: "bob")
        await vm.submitWire(bAccept.headerWire)
        XCTAssertEqual(vm.ui.stepIndex, 2)
        guard case let .confirm(emoji, fingerprintHex, peerId, _) = vm.ui.stage else {
            return XCTFail("expected confirm, got \(vm.ui.stage)")
        }
        XCTAssertEqual(emoji, bAccept.emoji)
        XCTAssertEqual(peerId, fingerprintHex, "iOS has no separate username — peerId IS the fingerprint")
        XCTAssertEqual(a.contactsStore.contacts.first?.id, fingerprintHex)
        XCTAssertEqual(a.contactsStore.contacts.first?.isVerified, false)
        XCTAssertEqual(a.contactsStore.contacts.first?.emoji, emoji)

        XCTAssertNil(vm.openThreadPeerId)
        await vm.confirmMatch(name: "")
        XCTAssertEqual(vm.openThreadPeerId, peerId)
        XCTAssertEqual(a.contactsStore.contacts.first?.isVerified, true)
        XCTAssertEqual(a.contactsStore.contacts.first?.emoji, emoji, "markVerified must keep the emoji")
    }

    func testRedeemerFullPathReceivesInviteShowsResponseConfirmsAndOpensThread() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.redeemer, b, name: "bob")

        await vm.start()
        XCTAssertEqual(vm.ui.stepIndex, 0)
        XCTAssertNil(notice(vm))

        let bundleWire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        await vm.submitWire(bundleWire)
        XCTAssertEqual(vm.ui.stepIndex, 1)
        guard case let .show(responseWire, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)

        await vm.handOffDone(faceToFace: true)
        XCTAssertEqual(vm.ui.stepIndex, 2)
        guard case let .confirm(emoji, fingerprintHex, peerId, _) = vm.ui.stage else {
            return XCTFail("expected confirm, got \(vm.ui.stage)")
        }
        XCTAssertEqual(b.contactsStore.contacts.first?.id, fingerprintHex)
        XCTAssertEqual(b.contactsStore.contacts.first?.emoji, emoji)

        let aComplete = try await a.coordinator.completeIncoming(responseWire)
        XCTAssertEqual(aComplete.emoji.count, 8)

        await vm.confirmMatch(name: "")
        XCTAssertEqual(vm.openThreadPeerId, peerId)
    }

    // MARK: - 表 W

    /// W1 接受入口贴回应:不分角色,照样完成。
    func testW01RedeemerEntryAcceptsAResponseAndGoesToConfirm() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "alice")
        let response = try await b.coordinator.acceptIncoming(invite.inviteWire, myDisplayName: "bob")

        let vm = makeVM(.redeemer, a)
        await vm.start()
        await vm.submitWire(response.headerWire)

        guard case .confirm = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        XCTAssertEqual(vm.ui.stepIndex, 2)
        XCTAssertEqual(vm.ui.stepTitles, showFirst)
        XCTAssertTrue(a.invites.records.isEmpty, "完成的那份邀请离开配对中")
    }

    /// W2 发起入口「下一步」后贴别人的邀请:走接受;原邀请仍在。
    func testW02InitiatorEntryPastingSomeoneElsesInviteAcceptsIt() async throws {
        let a = Rig(), c = Rig()
        let vm = makeVM(.initiator, a)
        await vm.start()
        let original = try XCTUnwrap(vm.inviteId)
        await vm.handOffDone(faceToFace: false)

        let cInvite = try await c.coordinator.startInvite(myDisplayName: "carol")
        await vm.submitWire(cInvite.inviteWire)

        guard case let .show(_, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertEqual(vm.ui.stepTitles, recvFirst)
        XCTAssertEqual(vm.ui.stepIndex, 1)
        XCTAssertEqual(a.invites.records.map(\.pairingId), [original], "原来那份邀请仍留在配对中")
    }

    // MARK: - 出示幕接收来件

    /// 出示幕上贴进对方的邀请:变成我的回执(出示幕·回执,第 2 步);我手里从没交出去的邀请当场丢弃。
    func testShowStageReceivingAnInviteBecomesMyReplyAndDiscardsTheUnsharedInvite() async throws {
        let a = Rig(), c = Rig()
        var discardable: String?
        let vm = makeVM(.initiator, a, onDiscardable: { discardable = $0 })
        await vm.start()
        let mine = try XCTUnwrap(vm.inviteId)
        XCTAssertEqual(discardable, mine)

        let cInvite = try await c.coordinator.startInvite(myDisplayName: "carol")
        await vm.submitWire(cInvite.inviteWire)

        guard case let .show(_, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertEqual(vm.ui.stepIndex, 1)
        XCTAssertEqual(vm.ui.stepTitles, recvFirst)
        XCTAssertNil(vm.inviteId)
        XCTAssertNil(a.invites.record(id: mine), "从没分享的邀请被丢弃")
        XCTAssertNil(discardable)
    }

    /// 分享面板弹出过的邀请不丢(可能已经发出去了)。
    func testShowStageReceivingAnInviteKeepsAnInviteWhoseSheetWasPresented() async throws {
        let a = Rig(), c = Rig()
        let vm = makeVM(.initiator, a)
        await vm.start()
        let mine = try XCTUnwrap(vm.inviteId)
        await vm.shareSheetPresented()

        let cInvite = try await c.coordinator.startInvite(myDisplayName: "carol")
        await vm.submitWire(cInvite.inviteWire)

        guard case let .show(_, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertNotNil(a.invites.record(id: mine))
    }

    /// 出示幕上贴进对方发回的、对应我这份邀请的回执:直接完成到核对。
    func testShowStageReceivingTheReplyToMyInviteCompletes() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.initiator, a, name: "alice")
        await vm.start()
        guard case let .show(wire, _) = vm.ui.stage else { return XCTFail("expected show") }
        let reply = try await b.coordinator.acceptIncoming(wire, myDisplayName: "bob")

        await vm.submitWire(reply.headerWire)

        guard case .confirm = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        XCTAssertEqual(vm.ui.stepIndex, 2)
    }

    /// 出示幕上收不下的东西:与接收幕同文案,以一次性提示给出,出示幕不变。
    func testShowStageRejectionsSurfaceTheSameCopyAndKeepTheStage() async throws {
        let a = Rig()
        let vm = makeVM(.initiator, a)
        await vm.start()
        let stage = vm.ui.stage
        let own = try XCTUnwrap(a.invites.records.first).inviteWire

        await vm.submitWire("hello")
        XCTAssertEqual(vm.actionError, L10n.pairingErrorNotPairing)
        vm.actionError = nil
        await vm.submitWire("  ")
        XCTAssertEqual(vm.actionError, L10n.pairingErrorNotPairing)
        vm.actionError = nil
        await vm.submitWire(own)
        XCTAssertEqual(vm.actionError, L10n.pairingErrorOwnCode)
        XCTAssertEqual(vm.ui.stage, stage)
    }

    func testShowStageSessionMessageFailureSurfacesTheIntakeCopy() async throws {
        let a = Rig()
        let vm = makeVM(.initiator, a, route: { _ in .failed(.noContacts) })
        await vm.start()
        await vm.submitWire(sessionWire)
        XCTAssertEqual(vm.actionError, IntakeFailure.noContacts.message)
    }

    /// 回执幕(我正在出示发回去的回执)不接收来件。
    func testResponseShowStageIgnoresIncomingWires() async throws {
        let a = Rig(), c = Rig(), d = Rig()
        let vm = makeVM(.redeemer, a)
        await vm.start()
        await vm.submitWire(try await c.coordinator.startInvite(myDisplayName: "carol").inviteWire)
        let stage = vm.ui.stage
        await vm.submitWire(try await d.coordinator.startInvite(myDisplayName: "dave").inviteWire)
        XCTAssertEqual(vm.ui.stage, stage)
        XCTAssertNil(vm.actionError)
    }

    /// otherAwaitingInvites:待完成邀请里不含正在出示的这份。
    func testOtherAwaitingInvitesExcludesTheCurrentInvite() async throws {
        let a = Rig()
        let older = try await a.coordinator.startInvite(myDisplayName: "alice")
        try await a.coordinator.markInviteShared(pairingId: older.pairingId)
        let vm = makeVM(.initiator, a)
        XCTAssertEqual(vm.otherAwaitingInvites, 0)
        await vm.start()
        XCTAssertNotEqual(vm.inviteId, older.pairingId)
        XCTAssertEqual(vm.otherAwaitingInvites, 1)
        await vm.shareSheetPresented()
        XCTAssertEqual(vm.otherAwaitingInvites, 1, "当前这份即使弹过面板也不计入")
    }

    /// W3 四种不带联系人的拒绝。
    func testW03ClassifiedRejectionsAreHintsWithFixedCopy() async throws {
        let a = Rig(), c = Rig()
        let own = try await a.coordinator.startInvite(myDisplayName: "alice")
        let cInvite = try await c.coordinator.startInvite(myDisplayName: "carol")
        let cResponse = try await Rig().coordinator.acceptIncoming(cInvite.inviteWire, myDisplayName: "dave")
        let cases: [(String, String)] = [
            (cResponse.headerWire, L10n.pairingErrorNoMatchingInvite),
            ("乱码 🔒 不是配对码", L10n.pairingErrorNotPairing),
            (own.inviteWire, L10n.pairingErrorOwnCode),
            ("hello", L10n.pairingErrorNotPairing),
        ]
        for (wire, text) in cases {
            let vm = makeVM(.redeemer, a)
            await vm.start()
            await vm.submitWire(wire)
            XCTAssertEqual(notice(vm), ReceiveNotice(text: text, isHint: true, contactId: nil), "for \(wire.prefix(8))")
        }
        XCTAssertTrue(a.contactsStore.contacts.isEmpty)
        XCTAssertEqual(a.sessions.writes, 0)
        XCTAssertEqual(a.invites.records.count, 1)
    }

    /// W4 握手真失败:既有失败常量,标为失败(不是分类提示)。
    func testW04HandshakeFailureShowsTheExistingFailureCopyAsFailure() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "alice")
        let response = try await b.coordinator.acceptIncoming(invite.inviteWire, myDisplayName: "bob")
        let payload = try PairingTransport.pairingPayload(response.headerWire)
        let broken = PairingTransport.headerToWire(payload.prefix(payload.count / 2))

        let vm = makeVM(.redeemer, a)
        await vm.start()
        await vm.submitWire(broken)

        XCTAssertEqual(notice(vm), ReceiveNotice(text: L10n.pairingErrorSubmit, isHint: false, contactId: nil))
        XCTAssertEqual(a.invites.records.count, 1)
    }

    /// W5 恢复未分享的邀请:出示幕,暗号逐字相同。
    func testW05ResumeUnsharedInviteShowsTheStoredInvite() async throws {
        let a = Rig()
        let record = try await a.coordinator.startInvite(myDisplayName: "alice")

        let vm = makeVM(.resumeInvite(pairingId: record.pairingId), a)
        await vm.start()

        XCTAssertEqual(vm.ui.stage, .show(wire: record.inviteWire, isResponse: false))
        XCTAssertEqual(vm.ui.stepIndex, 0)
        XCTAssertEqual(vm.ui.stepTitles, showFirst)
        XCTAssertEqual(vm.inviteId, record.pairingId)
        XCTAssertTrue(vm.canResendInvite)
    }

    /// W6 恢复已分享的邀请 / 迁移来的邀请:接收幕(第 2 步)。
    func testW06ResumeSharedOrMigratedInviteLandsOnReceive() async throws {
        let a = Rig()
        let record = try await a.coordinator.startInvite(myDisplayName: "alice")
        try await a.coordinator.markInviteShared(pairingId: record.pairingId)
        let shared = makeVM(.resumeInvite(pairingId: record.pairingId), a)
        await shared.start()
        XCTAssertNil(notice(shared))
        XCTAssertEqual(shared.ui.stepIndex, 1)
        XCTAssertEqual(shared.ui.stepTitles, showFirst)

        try a.invites.append(PendingPairingRecord(pairingId: "legacy", pairingNonceB64: "AAAA", createdAtMillis: 1, inviteWire: ""))
        let migrated = makeVM(.resumeInvite(pairingId: "legacy"), a)
        await migrated.start()
        XCTAssertNil(notice(migrated))
        XCTAssertEqual(migrated.ui.stepIndex, 1)
        XCTAssertFalse(migrated.canResendInvite, "迁移来的邀请没有可再发的文本")
        migrated.backToShow()
        guard case .receive = migrated.ui.stage else { return XCTFail("没有暗号文本时「再发一次暗号」不会回到出示幕") }
    }

    /// W7 接收幕「再发一次暗号」:回出示幕,同一份暗号。
    func testW07BackToShowReturnsToTheSameInvite() async throws {
        let a = Rig()
        let record = try await a.coordinator.startInvite(myDisplayName: "alice")
        try await a.coordinator.markInviteShared(pairingId: record.pairingId)
        let vm = makeVM(.resumeInvite(pairingId: record.pairingId), a)
        await vm.start()

        vm.backToShow()

        XCTAssertEqual(vm.ui.stage, .show(wire: record.inviteWire, isResponse: false))
        XCTAssertEqual(vm.ui.stepIndex, 0)
    }

    /// W8 恢复回应:出示幕显示已存回应,「下一步」进对印(emoji 取联系人已存的)。
    func testW08ResumeResponseShowsStoredResponseThenConfirm() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "alice")
        let accepted = try await b.coordinator.acceptIncoming(invite.inviteWire, myDisplayName: "bob")
        let fp = accepted.contact.fingerprintHex

        let vm = makeVM(.resumeResponse(fingerprintHex: fp), b)
        await vm.start()

        XCTAssertEqual(vm.ui.stage, .show(wire: accepted.headerWire, isResponse: true))
        XCTAssertEqual(vm.ui.stepTitles, recvFirst)
        await vm.handOffDone(faceToFace: true)
        guard case let .confirm(emoji, fingerprintHex, _, _) = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        XCTAssertEqual(emoji, accepted.emoji)
        XCTAssertEqual(fingerprintHex, fp)
        XCTAssertEqual(vm.ui.stepIndex, 2)
    }

    /// W9 交出去了(handOffDone):出示邀请 / 出示回应各标对应的记录。
    func testW09HandOffDoneMarksTheInviteOrTheResponseBeingShown() async throws {
        let a = Rig(), b = Rig()
        let inviteVM = makeVM(.initiator, a)
        await inviteVM.start()
        let inviteId = try XCTUnwrap(inviteVM.inviteId)
        XCTAssertNil(a.invites.record(id: inviteId)?.lastSharedAtMillis)
        await inviteVM.handOffDone(faceToFace: false)
        XCTAssertNotNil(a.invites.record(id: inviteId)?.lastSharedAtMillis)

        let accepted = try await b.coordinator.acceptIncoming(
            try await Rig().coordinator.startInvite(myDisplayName: "x").inviteWire, myDisplayName: "bob")
        let fp = accepted.contact.fingerprintHex
        XCTAssertNil(b.responses.record(for: fp)?.lastSharedAtMillis)
        let responseVM = makeVM(.resumeResponse(fingerprintHex: fp), b)
        await responseVM.start()
        await responseVM.handOffDone(faceToFace: false)
        XCTAssertNotNil(b.responses.record(for: fp)?.lastSharedAtMillis)
        XCTAssertNil(b.invites.records.first, "回应的标记不碰邀请")
    }

    /// W10 备注输入 27 字节:落盘 ≤ 24 字节且不切坏字素;被拒绝的输入不落盘。
    func testW10NoteInputIsClampedByGraphemeAndRejectedInputIsNotWritten() async throws {
        let a = Rig()
        let vm = makeVM(.initiator, a)
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)

        await vm.setNote("一二三四五六七八九")
        XCTAssertEqual(a.invites.record(id: id)?.note, "一二三四五六七八")
        XCTAssertEqual(vm.note, "一二三四五六七八")

        await vm.setNote("一二三四五六七😀")
        XCTAssertEqual(a.invites.record(id: id)?.note, "一二三四五六七", "不把 4 字节的表情切坏")
        XCTAssertLessThanOrEqual(a.invites.record(id: id)?.note?.utf8.count ?? 0, 24)

        await vm.setNote("阿\t青")
        XCTAssertEqual(a.invites.record(id: id)?.note, "一二三四五六七", "含控制字符:规范化拒绝,不写盘")
        XCTAssertEqual(vm.note, "一二三四五六七", "界面上的备注也不接受它")

        await vm.setNote("")
        XCTAssertNil(a.invites.record(id: id)?.note, "清空备注是合法的")
    }

    /// W11 删除邀请:记录没了,向导发出关闭信号。
    func testW11DeleteInviteRemovesTheRecordAndSignalsClose() async throws {
        let a = Rig()
        var held: [String?] = []
        let vm = makeVM(.initiator, a, onHeld: { held.append($0) })
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)
        XCTAssertFalse(vm.shouldClose)

        await vm.deleteInvite(pairingId: id)

        XCTAssertNil(a.invites.record(id: id))
        XCTAssertTrue(vm.shouldClose)
        XCTAssertNil(vm.inviteId)
        XCTAssertEqual(held.last, .some(nil), "持有的邀请 id 随删除一并放掉")
    }

    /// E:删除失败要让用户看得到;记录与向导都留着。
    func testDeleteInviteFailureIsReportedAndKeepsTheWizardOpen() async throws {
        let a = Rig(keyMaterial: ThrowingKeyMaterial())
        let vm = makeVM(.initiator, a)
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)

        await vm.deleteInvite(pairingId: id)

        XCTAssertNotNil(a.invites.record(id: id), "作废抛错:记录保留")
        XCTAssertFalse(vm.shouldClose)
        XCTAssertEqual(vm.actionError, L10n.commonDeleteFailed)
        XCTAssertEqual(vm.inviteId, id)
    }

    /// W12 空白提交。
    func testW12BlankSubmitIsTheNotPairingWireHint() async throws {
        let vm = makeVM(.redeemer, Rig())
        await vm.start()

        await vm.submitWire("   \n")

        XCTAssertEqual(notice(vm), ReceiveNotice(text: L10n.pairingErrorNotPairing, isHint: true, contactId: nil))
        XCTAssertEqual(vm.ui.stepIndex, 0)

        await vm.retry()
        XCTAssertNil(notice(vm), "retry 清掉提示")
    }

    /// W13 同一邀请连续提交两次(第一次未返回):编排层只走一遍。
    func testW13ConcurrentSubmissionsRunTheHandshakeOnce() async throws {
        let a = Rig()
        let wire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire

        // 基线:一次提交时编排层做了多少次联系人查询。
        let baseline = Rig()
        let one = makeVM(.redeemer, baseline)
        await one.start()
        await one.submitWire(wire)
        let singleRunFinds = baseline.contacts.finds
        XCTAssertGreaterThan(singleRunFinds, 0)

        let b = Rig()
        let vm = makeVM(.redeemer, b)
        await vm.start()
        let t1 = Task { await vm.submitWire(wire) }
        let t2 = Task { await vm.submitWire(wire) }
        _ = await (t1.value, t2.value)

        XCTAssertEqual(b.sessions.writes, 1)
        XCTAssertEqual(b.contacts.finds, singleRunFinds, "第二次提交在第一次返回前被忽略,没进编排层")
        guard case let .show(shown, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertEqual(shown, b.responses.records.first?.responseWire)
    }

    /// W13b 提交进行中再来一次(空白 / 乱码):直接被忽略,界面状态(stage / 提示)不被改写,而不只是编排层没多跑。
    func testW13bSecondSubmitWhileInFlightDoesNotRewriteTheUi() async throws {
        let a = Rig()
        let wire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let b = Rig()
        let vm = makeVM(.redeemer, b)
        await vm.start()

        let first = Task { await vm.submitWire(wire) }
        await Task.yield()  // 第一次提交跑到编排层的第一个挂起点(`submitting` 已置位)
        let during = vm.ui
        XCTAssertEqual(during.stage, .receive(notice: nil), "第一次提交还在途中,界面仍在接收幕且没有提示")

        await vm.submitWire("   \n")            // 空白:若没被忽略会写出「不是配对暗号」的提示
        XCTAssertEqual(vm.ui, during, "空白的第二次提交返回时界面没被改写")
        await vm.submitWire("乱码,不是暗号")      // 乱码:若没被忽略会写出分类提示或握手失败提示
        XCTAssertEqual(vm.ui, during, "乱码的第二次提交返回时界面没被改写")

        await first.value
        guard case let .show(_, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse, "第一次提交照常完成,走到出示回应")
    }

    /// 4b 发起入口里贴了别人的邀请走到「接受」:宿主不再替发起向导留着原邀请 id(向导重建应回到回应页,不是旧邀请)。
    func testAcceptingSomeoneElsesInviteInTheInitiatorEntryReleasesTheHeldInvite() async throws {
        let a = Rig(), c = Rig()
        var held: String? = "unset"
        let vm = makeVM(.initiator, a, onHeld: { held = $0 })
        await vm.start()
        let original = try XCTUnwrap(vm.inviteId)
        XCTAssertEqual(held, original)
        await vm.handOffDone(faceToFace: false)

        let cInvite = try await c.coordinator.startInvite(myDisplayName: "carol")
        await vm.submitWire(cInvite.inviteWire)

        guard case let .show(_, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertNil(held, "走到接受后释放宿主持有的邀请 id")
        XCTAssertEqual(a.invites.records.map(\.pairingId), [original], "原邀请本身仍在配对中,只是不再被向导持有")

        // 重建的发起向导不会回到旧邀请:新出一份(原邀请已有分享/备注与否不论,这里它是「空邀请」会被复用,id 相同也可)
        let rebuilt = makeVM(.initiator, a, held: held)
        await rebuilt.start()
        guard case let .show(_, rebuiltIsResponse) = rebuilt.ui.stage else { return XCTFail("expected show") }
        XCTAssertFalse(rebuilt.ui.stage == vm.ui.stage, "重建后不是回应页")
        XCTAssertFalse(rebuiltIsResponse)
    }

    /// W14 已配对过:留在接收幕,带联系人指纹。
    func testW14AlreadyPairedKeepsReceiveWithTheContactFingerprint() async throws {
        let a = Rig(), b = Rig()
        let wire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let accepted = try await b.coordinator.acceptIncoming(wire, myDisplayName: "bob")
        let fp = accepted.contact.fingerprintHex
        try await b.coordinator.forgetPeer(fingerprintHex: fp) // 收到对方第一条消息

        let vm = makeVM(.redeemer, b)
        await vm.start()
        await vm.submitWire(wire)

        XCTAssertEqual(notice(vm), ReceiveNotice(text: L10n.pairingErrorAlreadyPaired, isHint: true, contactId: fp))
        XCTAssertEqual(b.sessions.writes, 1)
    }

    /// 互发邀请:我手握自己的邀请,又接受过对方的(联系人带邀请摘要),对方的邀请再贴一次 → 互发提示 + 删邀请动作。
    func testMutualInviteOnShowStageOffersDeleteOfHeldInviteOnly() async throws {
        let a = Rig(), b = Rig()
        let theirs = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let vm = makeVM(.initiator, b)
        await vm.start()
        let held = try XCTUnwrap(vm.inviteId)
        let accepted = try await b.coordinator.acceptIncoming(theirs, myDisplayName: "bob")
        let fp = accepted.contact.fingerprintHex
        try await b.coordinator.forgetPeer(fingerprintHex: fp)

        await vm.submitWire(theirs)

        XCTAssertEqual(vm.mutualInviteNotice, L10n.pairingMutualInvite)
        XCTAssertNil(vm.actionError)
        await vm.deleteInvite(pairingId: held)
        XCTAssertTrue(vm.shouldClose)
        XCTAssertNil(b.invites.record(id: held))
        b.contactsStore.reload()
        XCTAssertTrue(b.contactsStore.contacts.contains { $0.id == fp }, "只删邀请,不动联系人")
    }

    func testMutualInviteOnReceiveStageCarriesDeleteAction() async throws {
        let a = Rig(), b = Rig()
        let theirs = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let vm = makeVM(.initiator, b)
        await vm.start()
        await vm.handOffDone(faceToFace: false)
        let accepted = try await b.coordinator.acceptIncoming(theirs, myDisplayName: "bob")
        try await b.coordinator.forgetPeer(fingerprintHex: accepted.contact.fingerprintHex)

        await vm.submitWire(theirs)

        XCTAssertEqual(notice(vm), ReceiveNotice(text: L10n.pairingMutualInvite, isHint: true, deleteInviteId: vm.inviteId))
        XCTAssertNotNil(vm.inviteId)
    }

    /// 互发邀请的另一半:对方用我的邀请回了回执,而我早已接受过他的邀请 → 贴回执同样是互发提示。
    func testMutualInviteWhenPeerReplyIsPastedWhileHoldingMyInvite() async throws {
        let a = Rig(), b = Rig()
        let theirs = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let vm = makeVM(.initiator, b)
        await vm.start()
        let held = try XCTUnwrap(vm.inviteId)
        let mine = try XCTUnwrap(b.invites.record(id: held)).inviteWire
        let accepted = try await b.coordinator.acceptIncoming(theirs, myDisplayName: "bob")
        try await b.coordinator.forgetPeer(fingerprintHex: accepted.contact.fingerprintHex)
        let reply = try await a.coordinator.acceptIncoming(mine, myDisplayName: "alice").headerWire

        await vm.submitWire(reply)

        XCTAssertEqual(vm.mutualInviteNotice, L10n.pairingMutualInvite)
        await vm.deleteInvite(pairingId: held)
        XCTAssertNil(b.invites.record(id: held))
    }

    /// 粘贴条进来的互发回执:向导是新开的、不握邀请;回执对上的是我早先发出的那份 → 同样的互发提示,
    /// 「删掉这条邀请」删的正是那一份,联系人、会话都不动。
    func testMutualReplyInFreshWizardOffersDeleteOfMatchedInvite() async throws {
        let a = Rig(), b = Rig()
        let theirs = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let mine = try await b.coordinator.startInvite(myDisplayName: "bob")
        let accepted = try await b.coordinator.acceptIncoming(theirs, myDisplayName: "bob")
        let fp = accepted.contact.fingerprintHex
        try await b.coordinator.forgetPeer(fingerprintHex: fp)
        let reply = try await a.coordinator.acceptIncoming(mine.inviteWire, myDisplayName: "alice").headerWire
        let writesBefore = b.sessions.writes

        let vm = makeVM(.incoming(wire: reply), b)
        await vm.start()

        XCTAssertNil(vm.inviteId)
        XCTAssertEqual(notice(vm), ReceiveNotice(text: L10n.pairingMutualInvite, isHint: true, deleteInviteId: mine.pairingId))
        await vm.deleteInvite(pairingId: mine.pairingId)
        XCTAssertTrue(vm.shouldClose)
        XCTAssertNil(b.invites.record(id: mine.pairingId))
        b.contactsStore.reload()
        XCTAssertTrue(b.contactsStore.contacts.contains { $0.id == fp }, "只删邀请,不动联系人")
        XCTAssertEqual(b.sessions.writes, writesBefore, "会话不动")
    }

    func testAlreadyPairedWithoutHeldInviteStaysPlain() async throws {
        let a = Rig(), b = Rig()
        let theirs = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let accepted = try await b.coordinator.acceptIncoming(theirs, myDisplayName: "bob")
        try await b.coordinator.forgetPeer(fingerprintHex: accepted.contact.fingerprintHex)
        let vm = makeVM(.redeemer, b)
        await vm.start()
        await vm.submitWire(theirs)
        XCTAssertEqual(notice(vm)?.text, L10n.pairingErrorAlreadyPaired)
        XCTAssertNil(notice(vm)?.deleteInviteId)
    }

    func testClosingWizardKeepsRecord() async throws {
        let a = Rig()
        var vm: PairingWizardViewModel? = makeVM(.initiator, a)
        await vm!.start()
        let id = try XCTUnwrap(vm?.inviteId)
        vm = nil
        XCTAssertNotNil(a.invites.record(id: id))
    }

    /// 宿主在向导关闭时做的事(`MixinAppModel.wizard` 的 didSet):把 VM 报上来的「可丢弃邀请」交给协调器丢弃。
    private func closeWizard(_ discardable: String?, _ rig: Rig) async throws {
        if let id = discardable { try await rig.coordinator.discardUnsharedInvite(pairingId: id) }
    }

    /// 没发出去(含写了备注)的邀请:关闭向导即丢弃,不留成「配对中」里看不见的记录。
    func testClosingWithANeverSharedInviteDiscardsIt() async throws {
        let a = Rig()
        var discardable: String?
        let vm = makeVM(.initiator, a, onDiscardable: { discardable = $0 })
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)
        await vm.setNote("老周")
        XCTAssertEqual(discardable, id)

        try await closeWizard(discardable, a)

        XCTAssertNil(a.invites.record(id: id))
    }

    /// 打开过分享面板就不再丢弃(有的面板不回报完成,用户可能已经发出去了);也不因此标已分享。
    func testPresentingTheShareSheetThenClosingKeepsTheInvite() async throws {
        let a = Rig()
        var discardable: String?
        let vm = makeVM(.initiator, a, onDiscardable: { discardable = $0 })
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)

        await vm.shareSheetPresented()
        XCTAssertNil(discardable)
        try await closeWizard(discardable, a)

        let record = try XCTUnwrap(a.invites.record(id: id))
        XCTAssertNil(record.lastSharedAtMillis, "只是打开了面板,不算已分享")
        XCTAssertNotNil(record.sheetPresentedAtMillis, "记录上记一笔,重开的向导才认得")
        if case .show = vm.ui.stage {} else { XCTFail("仍停在出示幕,got \(vm.ui.stage)") }
    }

    /// 弹过分享面板、面板没回报(MIUI 类):之后重开向导什么都不做就关,这份配对码也不丢;发起入口也不复用它。
    func testReopeningAfterTheShareSheetWasPresentedNeitherDiscardsNorReuses() async throws {
        let a = Rig()
        let record = try await a.coordinator.startInvite(myDisplayName: "")
        try await a.coordinator.markInvitePresented(pairingId: record.pairingId)

        var reported: [String?] = []
        let resumed = makeVM(.resumeInvite(pairingId: record.pairingId), a, onDiscardable: { reported.append($0) })
        await resumed.start()
        XCTAssertNil(reported.last ?? nil)
        try await a.coordinator.discardUnsharedInvite(pairingId: record.pairingId)
        XCTAssertNotNil(a.invites.record(id: record.pairingId))

        let fresh = makeVM(.initiator, a)
        await fresh.start()
        XCTAssertNotEqual(fresh.inviteId, record.pairingId)
    }

    /// 复制 / 对方已扫码(handOffDone)标了已分享:不再可丢弃;即便宿主拿着旧 id 去丢,协调器重读记录也不删。
    func testHandOffDoneMakesTheInviteNonDiscardable() async throws {
        let a = Rig()
        var discardable: String?
        let vm = makeVM(.initiator, a, onDiscardable: { discardable = $0 })
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)
        let stale = discardable

        await vm.handOffDone(faceToFace: false)
        XCTAssertNil(discardable)
        try await closeWizard(stale, a)

        XCTAssertNotNil(a.invites.record(id: id))
    }

    /// 恢复一份已分享的邀请:从一开始就不可丢弃。
    func testResumingASharedInviteIsNeverDiscardable() async throws {
        let a = Rig()
        let record = try await a.coordinator.startInvite(myDisplayName: "")
        try await a.coordinator.markInviteShared(pairingId: record.pairingId)
        var reported: [String?] = []
        let vm = makeVM(.resumeInvite(pairingId: record.pairingId), a, onDiscardable: { reported.append($0) })
        await vm.start()
        XCTAssertNil(reported.last ?? nil)
    }

    // MARK: - Task 6 的专项

    /// B:重贴同一份邀请走「返回已存回应」分支时,不得把已对印的联系人降回未对印。
    func testRepastingAnAcceptedInviteKeepsTheContactVerified() async throws {
        let a = Rig(), b = Rig()
        let wire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let first = makeVM(.redeemer, b)
        await first.start()
        await first.submitWire(wire)
        await first.handOffDone(faceToFace: true)
        await first.confirmMatch(name: "")
        guard case let .confirm(_, fp, _, _) = first.ui.stage else { return XCTFail("expected confirm") }
        XCTAssertEqual(b.contactsStore.contacts.first { $0.id == fp }?.isVerified, true)
        XCTAssertNotNil(b.responses.record(for: fp), "回应记录仍在(尚未收到对方消息)")

        let second = makeVM(.redeemer, b)
        await second.start()
        await second.submitWire(wire)

        guard case let .show(_, isResponse) = second.ui.stage else { return XCTFail("expected show, got \(second.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertEqual(b.contactsStore.contacts.first { $0.id == fp }?.isVerified, true, "内存缓存")
        XCTAssertEqual(try b.sync.readContacts().first { $0.id == fp }?.isVerified, true, "App Group 镜像")
    }

    /// C:对印幕拒绝(指纹不一致)后,为这位联系人留的回应记录也要清掉。
    func testRejectMismatchAlsoForgetsTheStoredResponse() async throws {
        let a = Rig(), b = Rig()
        let wire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        var wiped: [String] = []
        let vm = makeVM(.redeemer, b, onWipeThread: { wiped.append($0) })
        await vm.start()
        await vm.submitWire(wire)
        await vm.handOffDone(faceToFace: true)
        guard case let .confirm(_, fp, peerId, _) = vm.ui.stage else { return XCTFail("expected confirm") }
        XCTAssertNotNil(b.responses.record(for: fp))
        try b.keychain.write(key: "sess_\(peerId)", data: Data([0xAA]))

        await vm.rejectMismatch()

        guard case let .failed(message) = vm.ui.stage else { return XCTFail("expected failed, got \(vm.ui.stage)") }
        XCTAssertEqual(message, L10n.pairingMismatchDeleted)
        XCTAssertTrue(b.contactsStore.contacts.isEmpty)
        XCTAssertNil(b.keychain.items["sess_\(peerId)"])
        XCTAssertEqual(wiped, [peerId])
        XCTAssertNil(b.responses.record(for: fp), "回应记录不残留")
    }

    /// M10:对印拒绝里删联系人写失败 → 可见提示「删除失败，请重试。」,不进终态页,会话/回应原样保留,可再点;
    /// 修好后再点一次走完整的拒绝流程。
    func testM10RejectMismatchContactDeleteFailureIsVisibleAndRetryable() async throws {
        let a = Rig(), b = Rig()
        let wire = try await a.coordinator.startInvite(myDisplayName: "alice").inviteWire
        let failing = FailingRemoveContactsStore(sync: b.sync)
        var wiped: [String] = []
        let vm = makeVM(.redeemer, b, contactsStore: failing, onWipeThread: { wiped.append($0) })
        await vm.start()
        await vm.submitWire(wire)
        await vm.handOffDone(faceToFace: true)
        guard case let .confirm(_, fp, peerId, _) = vm.ui.stage else { return XCTFail("expected confirm") }
        try b.keychain.write(key: "sess_\(peerId)", data: Data([0xAA]))
        failing.failRemove = true

        await vm.rejectMismatch()

        XCTAssertEqual(vm.actionError, L10n.commonDeleteFailed)
        guard case .confirm = vm.ui.stage else { return XCTFail("must stay on confirm, got \(vm.ui.stage)") }
        XCTAssertTrue(wiped.isEmpty)
        XCTAssertNotNil(b.keychain.items["sess_\(peerId)"], "联系人没删成,会话不能先删")
        XCTAssertNotNil(b.responses.record(for: fp))
        XCTAssertTrue(failing.contacts.contains { $0.id == fp }, "内存缓存与镜像一致,联系人还在")

        failing.failRemove = false
        vm.actionError = nil
        await vm.rejectMismatch()

        guard case .failed = vm.ui.stage else { return XCTFail("expected failed, got \(vm.ui.stage)") }
        XCTAssertNil(vm.actionError)
        XCTAssertEqual(wiped, [peerId])
        XCTAssertTrue(failing.contacts.isEmpty)
    }

    func testRetryFromTerminalFailureRestartsTheWholeWizard() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.initiator, a)
        await vm.start()
        guard case let .show(firstWire, _) = vm.ui.stage else { return XCTFail("expected show") }
        await vm.handOffDone(faceToFace: false)
        let bAccept = try await b.coordinator.acceptIncoming(firstWire, myDisplayName: "bob")
        await vm.submitWire(bAccept.headerWire)
        guard case .confirm = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        await vm.rejectMismatch()
        guard case .failed = vm.ui.stage else { return XCTFail("expected failed, got \(vm.ui.stage)") }

        await vm.retry()

        XCTAssertEqual(vm.ui.stepIndex, 0)
        guard case let .show(secondWire, isResponse) = vm.ui.stage else { return XCTFail("expected show after retry, got \(vm.ui.stage)") }
        XCTAssertFalse(isResponse)
        XCTAssertNotEqual(secondWire, firstWire, "完成的邀请已删,重来要出一份新的")
    }

    /// F:恢复的配对已被删 → 「已不存在」终态,只能返回;重试不会把它再变回同一页(也不会冲掉它)。
    func testResumingAMissingRecordShowsTheGonePageThatRetryCannotLoop() async throws {
        let a = Rig()
        let gone = L10n.pairingErrorGone

        let invite = makeVM(.resumeInvite(pairingId: "nope"), a)
        await invite.start()
        XCTAssertEqual(invite.ui.stage, .gone(gone))
        await invite.retry()
        XCTAssertEqual(invite.ui.stage, .gone(gone), "retry 对终态页不起作用")

        let response = makeVM(.resumeResponse(fingerprintHex: "ffff"), a)
        await response.start()
        XCTAssertEqual(response.ui.stage, .gone(gone))
        XCTAssertEqual(response.ui.stepTitles, recvFirst)
    }

    /// F:恢复回应时联系人已不在(只剩回应记录)也算不存在。
    func testResumeResponseWithoutItsContactIsGone() async throws {
        let a = Rig()
        try a.responses.put(PairingResponseRecord(fingerprintHex: "aa", responseWire: "r", inviteDigest: "d", createdAtMillis: 1, lastSharedAtMillis: nil))
        let vm = makeVM(.resumeResponse(fingerprintHex: "aa"), a)
        await vm.start()
        XCTAssertEqual(vm.ui.stage, .gone(L10n.pairingErrorGone))
    }

    /// G:发起方向导被系统重建后,恢复原来那份邀请,不再出新的(即使它已写了备注、不满足「空邀请复用」)。
    func testRebuiltInitiatorWizardResumesTheHeldInviteInsteadOfMintingAnother() async throws {
        let a = Rig()
        var held: String?
        let first = makeVM(.initiator, a, onHeld: { held = $0 })
        await first.start()
        let id = try XCTUnwrap(first.inviteId)
        XCTAssertEqual(held, id, "向导把持有的邀请 id 报给宿主")
        await first.setNote("老周")
        guard case let .show(wire, _) = first.ui.stage else { return XCTFail("expected show") }

        let rebuilt = makeVM(.initiator, a, held: held)
        await rebuilt.start()

        XCTAssertEqual(rebuilt.ui.stage, .show(wire: wire, isResponse: false))
        XCTAssertEqual(rebuilt.inviteId, id)
        XCTAssertEqual(rebuilt.note, "老周")
        XCTAssertEqual(a.invites.records.count, 1, "没有攒出孤儿邀请")
    }

    /// G:持有的邀请已经没了(被删 / 已完成),就按发起入口出一份新的。
    func testHeldInviteThatIsGoneFallsBackToAFreshInvite() async throws {
        let a = Rig()
        let first = makeVM(.initiator, a)
        await first.start()
        let id = try XCTUnwrap(first.inviteId)
        try await a.coordinator.deleteInvite(pairingId: id)

        let rebuilt = makeVM(.initiator, a, held: id)
        await rebuilt.start()

        guard case .show = rebuilt.ui.stage else { return XCTFail("expected show, got \(rebuilt.ui.stage)") }
        XCTAssertNotEqual(rebuilt.inviteId, id)
        XCTAssertEqual(a.invites.records.count, 1)
    }

    /// G:邀请一旦完成(进入对印),宿主不再替它留着 id。
    func testHeldInviteIsReleasedOnceThePairingCompletes() async throws {
        let a = Rig(), b = Rig()
        var held: String? = "unset"
        let vm = makeVM(.initiator, a, onHeld: { held = $0 })
        await vm.start()
        XCTAssertNotNil(held)
        guard case let .show(wire, _) = vm.ui.stage else { return XCTFail("expected show") }
        await vm.handOffDone(faceToFace: false)
        let response = try await b.coordinator.acceptIncoming(wire, myDisplayName: "bob")

        await vm.submitWire(response.headerWire)

        XCTAssertNil(held)
        XCTAssertNil(vm.inviteId)
    }

    // MARK: - start 幂等 / 自动扫码一次性

    func testStartIsIdempotentAndDoesNotClobberStage() async throws {
        let vm = makeVM(.redeemer, Rig())
        await vm.start()
        await vm.submitWire("   ")
        let before = vm.ui

        await vm.start()

        XCTAssertEqual(vm.ui, before, "重复 start 不得把接收幕的提示/进度冲掉")
    }

    func testStartOnAResumeEntryIsIdempotentToo() async throws {
        let a = Rig()
        let record = try await a.coordinator.startInvite(myDisplayName: "alice")
        let vm = makeVM(.resumeInvite(pairingId: record.pairingId), a)
        await vm.start()
        await vm.handOffDone(faceToFace: false)
        let before = vm.ui

        await vm.start()

        XCTAssertEqual(vm.ui, before)
    }

    // MARK: - 添加联系人向导(handoff + i18n Task 6)

    /// 走到对印幕(发起方 a,对方 b),返回指纹。
    private func reachConfirm(_ vm: PairingWizardViewModel, _ b: Rig) async throws -> String {
        await vm.start()
        guard case let .show(wire, _) = vm.ui.stage else { XCTFail("expected show"); return "" }
        await vm.handOffDone(faceToFace: false)
        let bAccept = try await b.coordinator.acceptIncoming(wire, myDisplayName: "")
        await vm.submitWire(bAccept.headerWire)
        guard case let .confirm(_, fingerprintHex, _, _) = vm.ui.stage else {
            XCTFail("expected confirm, got \(vm.ui.stage)"); return ""
        }
        return fingerprintHex
    }

    private let sessionWire = encodeWire(ciphertext: Data([0xCC, 0xC8, 0x00, 0x00]))

    func testIncomingEntryWithInviteGoesStraightToShowMyCode() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "")
        let vm = makeVM(.incoming(wire: PairingShareText.compose(wire: invite.inviteWire, site: ConfigRepository.shared.current().shareSite, isResponse: false)), b)

        await vm.start()

        guard case let .show(_, isResponse) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertTrue(isResponse)
        XCTAssertEqual(vm.ui.stepIndex, 1)
        XCTAssertEqual(vm.ui.stepTitles, recvFirst)
    }

    func testIncomingEntryWithResponseCompletesToVerify() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "")
        let response = try await b.coordinator.acceptIncoming(invite.inviteWire, myDisplayName: "")
        let vm = makeVM(.incoming(wire: response.headerWire), a)

        await vm.start()

        guard case .confirm = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        XCTAssertEqual(vm.ui.stepIndex, 2)
        XCTAssertTrue(a.invites.records.isEmpty)
    }

    func testSubmitSessionMessageRoutesToThread() async throws {
        let a = Rig()
        var routed: [String] = []
        let vm = makeVM(.redeemer, a, route: { raw in
            routed.append(raw)
            return .openThread(peerId: "alice", messageId: "m1")
        })
        await vm.start()

        await vm.submitWire("他发来的：\n" + sessionWire)

        XCTAssertEqual(routed.count, 1)
        XCTAssertEqual(vm.openThreadPeerId, "alice")
        XCTAssertEqual(vm.openThreadHighlightId, "m1")
        XCTAssertEqual(a.contacts.finds, 0, "会话消息不进配对编排层")
        XCTAssertEqual(a.sessions.writes, 0)
    }

    func testSubmitSessionMessageFailureShowsIntakeNotice() async throws {
        let vm = makeVM(.redeemer, Rig(), route: { _ in .failed(.cannotOpen) })
        await vm.start()

        await vm.submitWire(sessionWire)

        XCTAssertEqual(notice(vm)?.text, IntakeFailure.cannotOpen.message)
        XCTAssertEqual(notice(vm)?.isHint, true)
        XCTAssertNil(vm.openThreadPeerId)
    }

    func testHandOffDoneOnInviteMarksSharedAndAdvancesToReceive() async throws {
        let a = Rig()
        let vm = makeVM(.initiator, a)
        await vm.start()
        let id = try XCTUnwrap(vm.inviteId)

        await vm.handOffDone(faceToFace: false)

        XCTAssertNotNil(a.invites.record(id: id)?.lastSharedAtMillis)
        XCTAssertNil(notice(vm))
        XCTAssertEqual(vm.ui.stepIndex, 1)
        XCTAssertTrue(vm.isAwaitingPeerCode)
    }

    func testHandOffDoneOnResponseMarksSharedAndAdvancesToVerify() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "")
        let vm = makeVM(.redeemer, b)
        await vm.start()
        XCTAssertFalse(vm.isAwaitingPeerCode, "接受方没有持有我的配对码")
        await vm.submitWire(invite.inviteWire)
        let fp = try XCTUnwrap(b.responses.records.first?.fingerprintHex)
        XCTAssertNil(b.responses.record(for: fp)?.lastSharedAtMillis)

        await vm.handOffDone(faceToFace: true)

        XCTAssertNotNil(b.responses.record(for: fp)?.lastSharedAtMillis)
        guard case .confirm = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        XCTAssertEqual(vm.ui.stepIndex, 2)
    }

    // MARK: - Task 14:接受方远程发回 → 直达对话,核对延后

    /// 接受方走到回执出示幕(b 接受 a 的邀请)。
    private func reachResponseShow(_ b: Rig, _ a: Rig) async throws -> PairingWizardViewModel {
        let invite = try await a.coordinator.startInvite(myDisplayName: "alice")
        let vm = makeVM(.redeemer, b)
        await vm.start()
        await vm.submitWire(invite.inviteWire)
        guard case .show(_, true) = vm.ui.stage else { XCTFail("expected response show, got \(vm.ui.stage)"); return vm }
        return vm
    }

    func testRemoteHandOffOnResponseOpensThreadAndNeverConfirms() async throws {
        let a = Rig(), b = Rig()
        let vm = try await reachResponseShow(b, a)
        let fp = try XCTUnwrap(b.responses.records.first?.fingerprintHex)

        await vm.handOffDone(faceToFace: false)

        XCTAssertEqual(vm.openThreadPeerId, fp)
        if case .confirm = vm.ui.stage { XCTFail("remote hand-off must not enter confirm") }
        XCTAssertNotNil(b.responses.record(for: fp)?.lastSharedAtMillis)
        let contact = try XCTUnwrap(b.contactsStore.contacts.first { $0.id == fp })
        XCTAssertFalse(contact.isVerified, "deferring never marks verified")
        XCTAssertNotNil(contact.acceptedInviteDigest, "pairing persisted before navigation")
    }

    func testFaceToFaceHandOffOnResponseStillEntersConfirm() async throws {
        let a = Rig(), b = Rig()
        let vm = try await reachResponseShow(b, a)

        await vm.handOffDone(faceToFace: true)

        guard case .confirm = vm.ui.stage else { return XCTFail("expected confirm, got \(vm.ui.stage)") }
        XCTAssertNil(vm.openThreadPeerId)
    }

    func testRemoteHandOffEmitsOpenThreadOnlyOnce() async throws {
        let a = Rig(), b = Rig()
        let vm = try await reachResponseShow(b, a)
        await vm.handOffDone(faceToFace: false)
        vm.openThreadPeerId = nil   // the view consumed the one-shot signal

        await vm.handOffDone(faceToFace: false)
        await vm.handOffDone(faceToFace: true)

        XCTAssertNil(vm.openThreadPeerId, "a second trigger is a no-op")
        if case .confirm = vm.ui.stage { XCTFail("second trigger must not enter confirm") }
    }

    func testSheetPresentedThenBackgroundThenActiveOpensThreadOnce() async throws {
        let a = Rig(), b = Rig()
        let vm = try await reachResponseShow(b, a)
        await vm.shareSheetPresented()
        await vm.sceneChanged(background: true, active: false)
        XCTAssertNil(vm.openThreadPeerId, "not until the app is back")
        await vm.sceneChanged(background: false, active: true)
        let fp = try XCTUnwrap(b.responses.records.first?.fingerprintHex)
        XCTAssertEqual(vm.openThreadPeerId, fp)
        vm.openThreadPeerId = nil
        await vm.sceneChanged(background: true, active: false)
        await vm.sceneChanged(background: false, active: true)
        XCTAssertNil(vm.openThreadPeerId)
    }

    func testBackgroundWithoutSheetOrAfterSheetClosedDoesNothing() async throws {
        let a = Rig(), b = Rig()
        let vm = try await reachResponseShow(b, a)
        await vm.sceneChanged(background: true, active: false)
        await vm.sceneChanged(background: false, active: true)
        XCTAssertNil(vm.openThreadPeerId)
        await vm.shareSheetPresented()
        vm.shareSheetClosed()
        await vm.sceneChanged(background: true, active: false)
        await vm.sceneChanged(background: false, active: true)
        XCTAssertNil(vm.openThreadPeerId)
    }

    func testConcurrentRemoteTriggersEmitOnce() async throws {
        let a = Rig(), b = Rig()
        let vm = try await reachResponseShow(b, a)
        var emitted = 0
        let cancellable = vm.$openThreadPeerId.dropFirst().sink { if $0 != nil { emitted += 1 } }
        async let first: Void = vm.handOffDone(faceToFace: false)
        async let second: Void = vm.handOffDone(faceToFace: false)
        _ = await (first, second)
        cancellable.cancel()
        XCTAssertEqual(emitted, 1)
    }

    func testShareTextCarriesHeader() async throws {
        let a = Rig()
        let vm = makeVM(.initiator, a)
        XCTAssertNil(vm.shareText)
        await vm.start()
        let record = try XCTUnwrap(a.invites.records.first)

        XCTAssertEqual(vm.shareText, PairingShareText.compose(wire: record.inviteWire, site: ConfigRepository.shared.current().shareSite, isResponse: false))
        await vm.handOffDone(faceToFace: false)
        XCTAssertNil(vm.shareText, "离开出示幕就没有可分享的文本")
    }

    func testConfirmMatchWithNameRenamesAndVerifies() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.initiator, a)
        let fp = try await reachConfirm(vm, b)

        await vm.confirmMatch(name: "  老王  ")

        let contact = a.contactsStore.contacts.first { $0.id == fp }
        XCTAssertEqual(contact?.displayName, "老王")
        XCTAssertEqual(contact?.isVerified, true)
        XCTAssertEqual(vm.openThreadPeerId, fp)
    }

    func testConfirmMatchWithBlankNameKeepsDefaultName() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.initiator, a)
        let fp = try await reachConfirm(vm, b)

        await vm.confirmMatch(name: "  ")

        let contact = a.contactsStore.contacts.first { $0.id == fp }
        XCTAssertEqual(contact?.displayName, L10n.contactsDefaultName(String(fp.prefix(6))))
        XCTAssertEqual(contact?.isVerified, true)
    }

    func testVerifyLaterKeepsUnverifiedAndOpensThread() async throws {
        let a = Rig(), b = Rig()
        let vm = makeVM(.initiator, a)
        let fp = try await reachConfirm(vm, b)

        await vm.verifyLater(name: "阿青")

        let contact = a.contactsStore.contacts.first { $0.id == fp }
        XCTAssertEqual(contact?.displayName, "阿青")
        XCTAssertEqual(contact?.isVerified, false)
        XCTAssertEqual(vm.openThreadPeerId, fp)
    }

    // MARK: - Ask name

    func testInitiatorAsksNameBeforeMintingThenMintsOnceWithSavedName() async throws {
        let a = Rig(), b = Rig()
        let profile = Profile(name: "", done: false)
        let vm = makeVM(.initiator, a, profile: profile)
        await vm.start()
        XCTAssertEqual(vm.ui.stage, .askName)
        XCTAssertTrue(a.invites.records.isEmpty, "no invite minted while asking")

        await vm.submitName("阿青")
        XCTAssertEqual(profile.saves, ["阿青"])
        XCTAssertTrue(profile.done)
        guard case let .show(wire, false) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        XCTAssertEqual(a.invites.records.count, 1)
        let acc = try await b.coordinator.acceptIncoming(wire, myDisplayName: "bob")
        XCTAssertEqual(acc.contact.displayName, "阿青")
    }

    func testInitiatorSkipNameMintsWithoutNameAndMarksDone() async {
        let profile = Profile(name: "", done: false)
        let vm = makeVM(.initiator, Rig(), profile: profile)
        await vm.start()
        XCTAssertEqual(vm.ui.stage, .askName)
        await vm.skipName()
        XCTAssertEqual(profile.saves, [nil])
        XCTAssertTrue(profile.done)
        XCTAssertEqual(profile.name, "")
        guard case .show(_, false) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
    }

    func testBlankNameInputCountsAsSkip() async {
        let profile = Profile(name: "", done: false)
        let vm = makeVM(.initiator, Rig(), profile: profile)
        await vm.start()
        await vm.submitName("   ")
        XCTAssertEqual(profile.saves, [nil])
        XCTAssertTrue(profile.done)
    }

    func testInitiatorDoesNotAskWhenNameSetOrAlreadyAnswered() async {
        let named = makeVM(.initiator, Rig(), profile: Profile(name: "阿青", done: false))
        await named.start()
        guard case .show = named.ui.stage else { return XCTFail("named: \(named.ui.stage)") }
        let answered = makeVM(.initiator, Rig(), profile: Profile(name: "", done: true))
        await answered.start()
        guard case .show = answered.ui.stage else { return XCTFail("answered: \(answered.ui.stage)") }
    }

    func testIncomingInviteOnReceiveAsksNameHoldsWireThenAcceptsOnce() async throws {
        let a = Rig(), b = Rig()
        let invite = try await a.coordinator.startInvite(myDisplayName: "alice")
        let profile = Profile(name: "", done: false)
        let vm = makeVM(.redeemer, b, profile: profile)
        await vm.start()
        await vm.submitWire(invite.inviteWire)
        XCTAssertEqual(vm.ui.stage, .askName)
        XCTAssertTrue(b.contactsStore.contacts.isEmpty, "nothing accepted while asking")

        // A second wire while asking is ignored.
        await vm.submitWire(invite.inviteWire)
        XCTAssertEqual(vm.ui.stage, .askName)

        await vm.submitName("小李")
        guard case let .show(reply, true) = vm.ui.stage else { return XCTFail("expected reply, got \(vm.ui.stage)") }
        XCTAssertEqual(profile.saves, ["小李"])
        XCTAssertEqual(b.contactsStore.contacts.count, 1)
        let done = try await a.coordinator.completeIncoming(reply)
        XCTAssertEqual(done.contact.displayName, "小李", "the reply carries the freshly saved name")
    }

    func testIncomingInviteOnInviteShowStageAsksNameAndSkipResumesAccept() async throws {
        let a = Rig(), b = Rig()
        let theirs = try await a.coordinator.startInvite(myDisplayName: "alice")
        let profile = Profile(name: "", done: true)
        let vm = makeVM(.initiator, b, profile: profile)
        await vm.start()
        guard case .show(_, false) = vm.ui.stage else { return XCTFail("expected show, got \(vm.ui.stage)") }
        profile.done = false // answered state reset (e.g. wipe) while the wizard is up
        await vm.submitWire(theirs.inviteWire)
        XCTAssertEqual(vm.ui.stage, .askName)
        await vm.skipName()
        guard case .show(_, true) = vm.ui.stage else { return XCTFail("expected response show, got \(vm.ui.stage)") }
        XCTAssertEqual(profile.saves, [nil])
    }

    func testRepliesAndMessagesNeverAskName() async throws {
        let a = Rig(), b = Rig()
        let profile = Profile(name: "", done: false)
        let vmA = makeVM(.initiator, a, name: "alice")
        await vmA.start()
        guard case let .show(inviteWire, _) = vmA.ui.stage else { return XCTFail() }
        let acc = try await b.coordinator.acceptIncoming(inviteWire, myDisplayName: "bob")
        // A's wizard gets the reply while A's profile claims "not answered"; reply must not ask.
        let vm = makeVM(.resumeInvite(pairingId: vmA.inviteId ?? ""), a, profile: profile)
        await vm.start()
        await vm.submitWire(acc.headerWire)
        XCTAssertNotEqual(vm.ui.stage, .askName)
        XCTAssertTrue(profile.saves.isEmpty)
    }

    func testStepTitlesUseNewVocabulary() async throws {
        let initiator = makeVM(.initiator, Rig())
        XCTAssertEqual(initiator.ui.stepTitles, [L10n.pairingStepSend, L10n.pairingStepEnter, L10n.pairingStepVerify])
        let redeemer = makeVM(.redeemer, Rig())
        XCTAssertEqual(redeemer.ui.stepTitles, [L10n.pairingStepEnter, L10n.pairingStepSend, L10n.pairingStepVerify])
        let incoming = makeVM(.incoming(wire: "🔒"), Rig())
        XCTAssertEqual(incoming.ui.stepTitles, redeemer.ui.stepTitles)
    }
}

@MainActor
final class WizardEntryTests: XCTestCase {
    func testEntriesAreIdentifiableAndDistinct() {
        let all: [WizardEntry] = [.initiator, .redeemer,
                                  .resumeInvite(pairingId: "p"), .resumeResponse(fingerprintHex: "f"),
                                  .incoming(wire: "🔒a"), .incoming(wire: "🔒b")]
        XCTAssertEqual(Set(all.map(\.id)).count, all.count)
    }
}
