import XCTest
@testable import ChencangShared

@MainActor
final class TabListViewModelsTests: XCTestCase {
    // MARK: 空状态判定(纯函数)

    func testNotLoadedNeverDrawsEmptyState() {
        XCTAssertNil(conversationEmptyState(loaded: false, hasRows: false, hasContacts: false, hasPending: false))
        XCTAssertNil(conversationEmptyState(loaded: false, hasRows: false, hasContacts: true, hasPending: false))
    }

    func testHasRowsNeverDrawsEmptyState() {
        XCTAssertNil(conversationEmptyState(loaded: true, hasRows: true, hasContacts: true, hasPending: true))
    }

    func testNoContactsNoPendingIsStartPairing() {
        XCTAssertEqual(conversationEmptyState(loaded: true, hasRows: false, hasContacts: false, hasPending: false), .startPairing)
    }

    func testContactsOrPendingWithoutMessagesIsGoToContacts() {
        XCTAssertEqual(conversationEmptyState(loaded: true, hasRows: false, hasContacts: true, hasPending: false), .goToContacts)
        XCTAssertEqual(conversationEmptyState(loaded: true, hasRows: false, hasContacts: false, hasPending: true), .goToContacts)
    }

    // MARK: 视图模型

    private struct Env {
        let contacts: ContactsStore
        let invites: PendingInviteStore
        let responses: PairingResponseStore
        let chat: ChatStore
    }

    private func env() throws -> Env {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tab-vm-\(UUID().uuidString)")
        return Env(
            contacts: ContactsStore(sync: AppGroupSync(defaults: FakeAppGroupDefaults())),
            invites: PendingInviteStore(defaults: FakeAppGroupDefaults()),
            responses: PairingResponseStore(defaults: FakeAppGroupDefaults()),
            chat: try ChatStore(directory: dir))
    }

    func testConversationVMEmptyStateFollowsData() async throws {
        let e = try env()
        let vm = ConversationListViewModel(contactsStore: e.contacts, chatStore: e.chat, inviteStore: e.invites, responseStore: e.responses)
        XCTAssertEqual(vm.emptyState, .startPairing)

        // 没有消息的联系人也出行,空状态随之不画。
        try await e.contacts.add(AppGroupContact(id: "aa", displayName: "阿青", isVerified: false))
        XCTAssertNil(vm.emptyState)
        XCTAssertEqual(vm.rows.map(\.id), ["aa"])
        XCTAssertEqual(vm.rows.first?.preview, .noMessages)

        try e.chat.append(ChatMessage(id: "m1", peerId: "aa", direction: .incoming, body: "hi", timestamp: Date(), status: .received))
        XCTAssertNil(vm.emptyState)
        XCTAssertEqual(vm.rows.map(\.id), ["aa"])
        XCTAssertNotNil(vm.rows.first?.last)
    }

    // 回应还没发出去的接受方只在联系人 tab「配对中」,会话 tab 不出行;发出去后出「等对方发来第一条」。
    func testConversationVMExcludesUnsentResponseAccepters() async throws {
        let e = try env()
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        try await e.contacts.add(AppGroupContact(id: "cc", displayName: "未发回应", isVerified: false, acceptedInviteDigest: "d"))
        try e.responses.put(PairingResponseRecord(
            fingerprintHex: "cc", responseWire: "r", inviteDigest: "d", createdAtMillis: nowMs, lastSharedAtMillis: nil))
        let vm = ConversationListViewModel(contactsStore: e.contacts, chatStore: e.chat, inviteStore: e.invites, responseStore: e.responses)
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertEqual(vm.emptyState, .goToContacts, "只有配对中:仍是「去联系人」")

        try e.responses.put(PairingResponseRecord(
            fingerprintHex: "cc", responseWire: "r", inviteDigest: "d", createdAtMillis: nowMs, lastSharedAtMillis: nowMs))
        XCTAssertEqual(vm.rows.map(\.id), ["cc"])
        XCTAssertEqual(vm.rows.first?.preview, .waitingPeer)
    }

    func testConversationVMPendingInviteAloneIsGoToContacts() throws {
        let e = try env()
        try e.invites.append(PendingPairingRecord(pairingId: "p1", pairingNonceB64: "n", createdAtMillis: 1, inviteWire: "w", lastSharedAtMillis: 1))
        let vm = ConversationListViewModel(contactsStore: e.contacts, chatStore: e.chat, inviteStore: e.invites, responseStore: e.responses)
        XCTAssertEqual(vm.emptyState, .goToContacts)
    }

    func testConversationVMAwaitingCountFollowsInvites() throws {
        let e = try env()
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let vm = ConversationListViewModel(contactsStore: e.contacts, chatStore: e.chat, inviteStore: e.invites, responseStore: e.responses)
        XCTAssertEqual(vm.awaitingCount, 0)
        try e.invites.append(PendingPairingRecord(pairingId: "p0", pairingNonceB64: "n", createdAtMillis: nowMs, inviteWire: "w"))
        XCTAssertEqual(vm.awaitingCount, 0, "没分享过也没弹过面板:不算等待")
        try e.invites.append(PendingPairingRecord(pairingId: "p1", pairingNonceB64: "n", createdAtMillis: nowMs, inviteWire: "w", lastSharedAtMillis: nowMs))
        try e.invites.append(PendingPairingRecord(pairingId: "p2", pairingNonceB64: "n", createdAtMillis: nowMs, inviteWire: "w", sheetPresentedAtMillis: nowMs))
        XCTAssertEqual(vm.awaitingCount, 2)
        try e.invites.remove(id: "p1")
        XCTAssertEqual(vm.awaitingCount, 1)
    }

    func testContactVMSortsAndExcludesUnsentResponses() async throws {
        let e = try env()
        try await e.contacts.add(AppGroupContact(id: "bb", displayName: "老周", isVerified: true))
        try await e.contacts.add(AppGroupContact(id: "aa", displayName: "alice", isVerified: false))
        try await e.contacts.add(AppGroupContact(id: "cc", displayName: "未发回应", isVerified: false))
        try e.responses.put(PairingResponseRecord(
            fingerprintHex: "cc", responseWire: "r", inviteDigest: "d", createdAtMillis: Int64(Date().timeIntervalSince1970 * 1000), lastSharedAtMillis: nil))
        let vm = ContactListViewModel(contactsStore: e.contacts, inviteStore: e.invites, responseStore: e.responses, locale: Locale(identifier: "zh_CN"))
        XCTAssertEqual(vm.rows.map(\.id), ["bb", "aa"]) // zh:汉字在拉丁字母之前
        XCTAssertTrue(vm.hasPending)
        XCTAssertFalse(vm.showEmptyHint)
    }

    func testContactVMEmptyHintOnlyWithoutContactsAndPending() async throws {
        let e = try env()
        let vm = ContactListViewModel(contactsStore: e.contacts, inviteStore: e.invites, responseStore: e.responses)
        XCTAssertTrue(vm.showEmptyHint)
        try e.invites.append(PendingPairingRecord(pairingId: "p1", pairingNonceB64: "n", createdAtMillis: 1, inviteWire: "w", lastSharedAtMillis: 1))
        XCTAssertFalse(vm.showEmptyHint)
        XCTAssertTrue(vm.rows.isEmpty)
        try e.invites.remove(id: "p1")
        XCTAssertTrue(vm.showEmptyHint)
    }

    /// 数据保护解除后 `reloadUnreadable()` 读回空会话:`threads` 没变、无发射,但「加载完成」本身要能驱动重算。
    func testConversationVMRecomputesWhenLoadCompletesWithoutThreadChange() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tab-vm-lock-\(UUID().uuidString)")
        _ = try ChatStore(directory: dir)   // 建目录
        try Data("[]".utf8).write(to: dir.appendingPathComponent("alice.json"))   // 读回来是空数组
        final class Gate { var locked = true }
        let gate = Gate()
        let chat = try ChatStore(directory: dir, reader: { url in
            if gate.locked, url.lastPathComponent == "alice.json" {
                throw NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoPermissionError)
            }
            return try Data(contentsOf: url)
        })
        let e = try env()
        let vm = ConversationListViewModel(contactsStore: e.contacts, chatStore: chat, inviteStore: e.invites, responseStore: e.responses)
        XCTAssertFalse(chat.isFullyLoaded)
        XCTAssertNil(vm.emptyState, "未加载完:不画任何空状态")

        gate.locked = false
        XCTAssertEqual(try chat.reloadUnreadable(), ["alice"])
        XCTAssertEqual(vm.emptyState, .startPairing, "加载完成且无数据:即使 threads 没变也要出空状态")
    }
}

/// 「配对中」分区、角标、删除确认与分享(Task 6)。表 K 的数据口径由 `PendingItemsTests` 守;这里守界面模型与 VM。
@MainActor
final class PendingSectionTests: XCTestCase {
    private let T: Int64 = 10_000_000_000_000
    private let minute: Int64 = 60_000
    private let day: Int64 = 86_400_000

    private struct ThrowingIdentity: InbandIdentityProviding {
        @MainActor func loadIdentity() throws -> SecretIdentity { throw PairingError.noIdentity }
    }

    private struct ThrowingKeyMaterial: InviteKeyMaterialInvalidating {
        func invalidate(_ record: PendingPairingRecord) throws { throw NSError(domain: "test", code: 1) }
    }

    private final class NoSessions: PairedSessionPersisting, @unchecked Sendable {
        func put(_ session: Session, peerId: String) async throws {}
        func remove(peerId: String) async {}
    }

    private final class Clock { var now: Int64 = 0 }

    private struct Env {
        let contacts: ContactsStore
        let invites: PendingInviteStore
        let responses: PairingResponseStore
        let coordinator: PairingCoordinator
        let clock: Clock
    }

    private func env(keyMaterial: InviteKeyMaterialInvalidating = NoInviteKeyMaterial()) -> Env {
        let sync = AppGroupSync(defaults: FakeAppGroupDefaults())
        let invites = PendingInviteStore(defaults: FakeAppGroupDefaults())
        let responses = PairingResponseStore(defaults: FakeAppGroupDefaults())
        let clock = Clock()
        clock.now = T
        let coordinator = PairingCoordinator(
            identity: ThrowingIdentity(),
            prekeyProvisioner: PrekeyProvisioner(
                store: SignedPreKeyStore(keychain: FakeKeychain()),
                defaults: UserDefaults(suiteName: "pending-section-\(UUID().uuidString)")!),
            sessionStore: NoSessions(),
            contactStore: AppGroupContactPersister(sync: sync),
            pending: invites, responses: responses, keyMaterial: keyMaterial,
            now: { [clock] in clock.now })
        return Env(contacts: ContactsStore(sync: sync), invites: invites, responses: responses, coordinator: coordinator, clock: clock)
    }

    private func makeVM(_ e: Env) -> ContactListViewModel {
        ContactListViewModel(
            contactsStore: e.contacts, inviteStore: e.invites, responseStore: e.responses,
            now: { [clock = e.clock] in Date(timeIntervalSince1970: Double(clock.now) / 1000) },
            coordinator: { e.coordinator },
            relativeTime: { created, _ in "t\(created)" })
    }

    /// 表 K 的数据:邀请 A(已分享,T−5 分)、B(已分享,T−3 天,备注「发给老周的」)、C(已分享,T−31 天)、
    /// 邀请 U(未分享:不出现)、回应 R(未分享,T−1 分,联系人在)。
    private func seedTableK(_ e: Env) async throws {
        try e.invites.append(PendingPairingRecord(
            pairingId: "A", pairingNonceB64: "n", createdAtMillis: T - 5 * minute, inviteWire: "wireA", lastSharedAtMillis: T - 5 * minute))
        try e.invites.append(PendingPairingRecord(pairingId: "U", pairingNonceB64: "n", createdAtMillis: T - 2 * minute, inviteWire: "wireU"))
        try e.invites.append(PendingPairingRecord(
            pairingId: "B", pairingNonceB64: "n", createdAtMillis: T - 3 * day, inviteWire: "wireB",
            note: "发给老周的", lastSharedAtMillis: T - 3 * day))
        try e.invites.append(PendingPairingRecord(pairingId: "C", pairingNonceB64: "n", createdAtMillis: T - 31 * day, inviteWire: "wireC", lastSharedAtMillis: T - 31 * day))
        try await e.contacts.add(AppGroupContact(id: "aa11", displayName: "联系人 aa11", isVerified: false))
        try e.responses.put(PairingResponseRecord(
            fingerprintHex: "aa11", responseWire: "wireR", inviteDigest: "d", createdAtMillis: T - minute, lastSharedAtMillis: nil))
    }

    // MARK: 行模型(纯函数)

    func testRowsFollowTheStatusTable() async throws {
        let e = env()
        try await seedTableK(e)
        let items = pendingItems(invites: e.invites.records, responses: e.responses.records, contactIds: ["aa11"], nowMillis: T)

        let rows = buildPendingRows(items: items, contacts: e.contacts.contacts, relativeTime: { "t\($0)" })

        XCTAssertEqual(rows.map(\.id), ["aa11", "A", "B", "C"], "未分享的邀请 U 不出现")
        XCTAssertEqual(rows.map(\.title), ["联系人 aa11", L10n.contactsInviteNoNote, "发给老周的", L10n.contactsInviteNoNote])
        XCTAssertEqual(rows.map(\.titleIsPlaceholder), [false, true, false, true])
        XCTAssertEqual(rows.map(\.subtitle), [
            L10n.contactsPendingSubtitle(L10n.contactsPendingResponseUnsent, time: "t\(T - minute)"),
            L10n.contactsPendingSubtitle(L10n.contactsPendingAwaiting, time: "t\(T - 5 * minute)"),
            L10n.contactsPendingSubtitle(L10n.contactsPendingAwaiting, time: "t\(T - 3 * day)"),
            L10n.contactsPendingStale("t\(T - 31 * day)"),
        ])
        XCTAssertEqual(rows.map(\.tone), [.warn, .secondary, .secondary, .tertiary])
        XCTAssertEqual(rows.map(\.resendLabel), [L10n.contactsSendBack, L10n.contactsResend, L10n.contactsResend, L10n.contactsResend])
        XCTAssertEqual(rows.map(\.canDelete), [false, true, true, true], "只有邀请可删除")
        XCTAssertEqual(rows.map(\.isResponse), [true, false, false, false])
        XCTAssertNotNil(rows[0].avatar, "回应行用该联系人的头像")
        XCTAssertNil(rows[1].avatar, "邀请行是空心印占位")
        XCTAssertEqual(rows[1].accessibilityLabel, L10n.contactsPendingRowCd(rows[1].title, subtitle: rows[1].subtitle))
        XCTAssertEqual(rows[0].accessibilityLabel, L10n.contactsPendingRowCd("联系人 aa11", subtitle: rows[0].subtitle))
    }

    func testPendingRowCopyUsesNewVocabulary() async throws {
        let e = env()
        try await seedTableK(e)
        let items = pendingItems(invites: e.invites.records, responses: e.responses.records, contactIds: ["aa11"], nowMillis: T)
        let rows = buildPendingRows(items: items, contacts: e.contacts.contacts, relativeTime: { "t\($0)" })
        XCTAssertTrue(rows[0].subtitle.hasPrefix(L10n.contactsPendingResponseUnsent))
        XCTAssertEqual(rows[1].title, L10n.contactsInviteNoNote)
    }

    func testStaleSubtitleUsesRelativeTime() {
        let stale = PendingItem(id: "s", kind: .inviteAwaiting, note: nil, createdAtMillis: T - 31 * day, isStale: true, canResend: true)
        let rows = buildPendingRows(items: [stale], contacts: [], relativeTime: { "rel\($0)" })
        XCTAssertEqual(rows[0].subtitle, L10n.contactsPendingStale("rel\(T - 31 * day)"))
    }

    func testMigratedInviteHasNoResendAndAwaits() {
        let migrated = PendingItem(id: "m", kind: .inviteAwaiting, note: nil, createdAtMillis: T - minute, isStale: false, canResend: false)
        let rows = buildPendingRows(items: [migrated], contacts: [], relativeTime: { _ in "刚刚" })
        XCTAssertEqual(rows.first?.subtitle, L10n.contactsPendingSubtitle(L10n.contactsPendingAwaiting, time: "刚刚"))
        XCTAssertNil(rows.first?.resendLabel, "没有暗号文本:不显示行尾动作")
    }

    func testRelativeTimeFollowsGivenLocale() {
        let zh = defaultPendingRelativeTime(createdAtMillis: T - 5 * minute, nowMillis: T, locale: Locale(identifier: "zh_CN"))
        XCTAssertTrue(zh.contains("5") && zh.contains("分钟"), "got \(zh)")
        let en = defaultPendingRelativeTime(createdAtMillis: T - 5 * minute, nowMillis: T, locale: Locale(identifier: "en_US"))
        XCTAssertTrue(en.contains("5") && en.contains("minute"), "got \(en)")
        XCTAssertEqual(defaultPendingRelativeTime(createdAtMillis: T - 10_000, nowMillis: T), L10n.contactsJustNow)
    }

    // MARK: 联系人详情的「再发一次回应暗号」

    func testResendableResponseNeedsAStoredAndAlreadySharedRecord() {
        let shared = PairingResponseRecord(fingerprintHex: "aa", responseWire: "r", inviteDigest: "d", createdAtMillis: 1, lastSharedAtMillis: 2)
        let unshared = PairingResponseRecord(fingerprintHex: "bb", responseWire: "r", inviteDigest: "d", createdAtMillis: 1, lastSharedAtMillis: nil)
        XCTAssertEqual(resendableResponse(for: "aa", in: [shared, unshared]), shared)
        XCTAssertNil(resendableResponse(for: "bb", in: [shared, unshared]), "没分享过的在「配对中」,详情页不重复")
        XCTAssertNil(resendableResponse(for: "cc", in: [shared, unshared]))
    }

    // MARK: 视图模型

    func testBadgeAndRowsFollowTableK() async throws {
        let e = env()
        try await seedTableK(e)
        let vm = makeVM(e)

        XCTAssertEqual(vm.pendingRows.map(\.id), ["aa11", "A", "B", "C"])
        XCTAssertEqual(vm.badgeCount, 1, "只有回应 R 计角标;邀请都在等对方,未分享的 U 不出现")
        XCTAssertTrue(vm.rows.isEmpty, "回应没发出去的联系人只在配对中,不在联系人列表重复")

        try await e.coordinator.markResponseShared(fingerprintHex: "aa11")
        XCTAssertEqual(vm.badgeCount, 0)
    }

    func testStaleAndBadgeRecomputeOnRefreshWithoutAnyDataChange() async throws {
        let e = env()
        try await e.contacts.add(AppGroupContact(id: "aa11", displayName: "联系人 aa11", isVerified: false))
        try e.responses.put(PairingResponseRecord(
            fingerprintHex: "aa11", responseWire: "wireR", inviteDigest: "d", createdAtMillis: T, lastSharedAtMillis: nil))
        let vm = makeVM(e)
        XCTAssertEqual(vm.badgeCount, 1)
        XCTAssertEqual(vm.pendingRows.first?.tone, .warn)

        e.clock.now = T + 31 * day
        XCTAssertEqual(vm.badgeCount, 1, "数据没变、没人通知:不自己重算")
        vm.refresh()

        XCTAssertEqual(vm.badgeCount, 0)
        XCTAssertEqual(vm.pendingRows.first?.tone, .tertiary)
        XCTAssertEqual(vm.pendingRows.first?.subtitle, L10n.contactsPendingStale("t\(T)"))
    }

    func testDeleteConfirmationLivesInTheViewModelAndDeletes() async throws {
        let e = env()
        try await seedTableK(e)
        let vm = makeVM(e)
        XCTAssertNil(vm.deleteCandidate)

        vm.requestDelete("A")
        XCTAssertEqual(vm.deleteCandidate, "A", "确认弹窗的状态在 VM 里,旋转 / 重建视图后还在")
        vm.cancelDelete()
        XCTAssertNil(vm.deleteCandidate)
        XCTAssertNotNil(e.invites.record(id: "A"))

        vm.requestDelete("A")
        await vm.confirmDelete()
        XCTAssertNil(vm.deleteCandidate)
        XCTAssertNil(e.invites.record(id: "A"))
        XCTAssertNil(vm.actionError)
        XCTAssertFalse(vm.pendingRows.contains { $0.id == "A" })
    }

    func testDeleteFailureIsVisibleAndKeepsTheRecord() async throws {
        let e = env(keyMaterial: ThrowingKeyMaterial())
        try await seedTableK(e)
        let vm = makeVM(e)

        vm.requestDelete("A")
        await vm.confirmDelete()

        XCTAssertNotNil(e.invites.record(id: "A"))
        XCTAssertEqual(vm.actionError, L10n.commonDeleteFailed)
        XCTAssertNil(vm.deleteCandidate)
    }

    func testItemsToShareRendersCardThenMarksShared() async throws {
        let e = env()
        try await seedTableK(e)
        let vm = makeVM(e)
        let rowA = try XCTUnwrap(vm.pendingRows.first { $0.id == "A" })
        let rowR = try XCTUnwrap(vm.pendingRows.first { $0.id == "aa11" })

        var rendered: [PairingShare] = []
        let itemsA = await vm.itemsToShare(for: rowA) { rendered.append($0); return "card" }
        XCTAssertEqual(itemsA as? [String], ["card"])
        XCTAssertNotNil(e.invites.record(id: "A")?.lastSharedAtMillis, "有东西可发之后才标记已分享")
        let itemsR = await vm.itemsToShare(for: rowR) { rendered.append($0); return "card" }
        XCTAssertEqual(itemsR as? [String], ["card"])
        XCTAssertEqual(rendered, [PairingShare(wire: "wireA", isResponse: false), PairingShare(wire: "wireR", isResponse: true)])
        XCTAssertNotNil(e.responses.record(for: "aa11")?.lastSharedAtMillis)
        XCTAssertEqual(vm.badgeCount, 0)
    }

    func testItemsToShareFallsBackToTextWhenRenderFails() async throws {
        let e = env()
        try await seedTableK(e)
        let vm = makeVM(e)
        let row = try XCTUnwrap(vm.pendingRows.first { $0.id == "B" })
        let items = await vm.itemsToShare(for: row) { _ in nil }
        XCTAssertEqual(items as? [String], [PairingShare(wire: "wireB", isResponse: false).text])
    }

    func testItemsToShareForARowWithoutStoredTextIsNil() async throws {
        let e = env()
        try e.invites.append(PendingPairingRecord(pairingId: "m", pairingNonceB64: "n", createdAtMillis: T, inviteWire: "", lastSharedAtMillis: T))
        let vm = makeVM(e)
        let row = try XCTUnwrap(vm.pendingRows.first)
        let items = await vm.itemsToShare(for: row) { _ in "card" }
        XCTAssertNil(items)
    }
}
