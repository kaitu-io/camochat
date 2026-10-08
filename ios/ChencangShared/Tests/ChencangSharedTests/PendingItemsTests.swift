import XCTest
@testable import ChencangShared

final class PendingItemsTests: XCTestCase {
    private let T: Int64 = 10_000_000_000_000
    private let min: Int64 = 60_000
    private let day: Int64 = 86_400_000

    private func invite(_ id: String, ago: Int64, shared: Bool, wire: String = "w") -> PendingPairingRecord {
        PendingPairingRecord(pairingId: id, pairingNonceB64: "n", createdAtMillis: T - ago,
                             inviteWire: wire, note: nil, lastSharedAtMillis: shared ? T - ago : nil)
    }
    private func resp(_ fp: String, ago: Int64, shared: Bool) -> PairingResponseRecord {
        PairingResponseRecord(fingerprintHex: fp, responseWire: "rw", inviteDigest: "d-\(fp)",
                              createdAtMillis: T - ago, lastSharedAtMillis: shared ? T - ago : nil)
    }
    private func tableK() -> (inv: [PendingPairingRecord], res: [PairingResponseRecord]) {
        ([invite("A", ago: 5 * min, shared: false),
          invite("B", ago: 3 * day, shared: true),
          invite("C", ago: 31 * day, shared: false)],
         [resp("R", ago: 1 * min, shared: false),
          resp("R2", ago: 1 * min, shared: true),
          resp("R3", ago: 1 * min, shared: false)])
    }

    func testTableK() {
        let (inv, res) = tableK()
        let items = pendingItems(invites: inv, responses: res, contactIds: ["R", "R2"], nowMillis: T)
        XCTAssertEqual(items.map(\.id), ["R", "B"], "未分享的邀请 A、C 不出现")
        XCTAssertEqual(items.map(\.kind), [.responseUnsent, .inviteAwaiting])
        XCTAssertEqual(items.map(\.isStale), [false, false])
        XCTAssertEqual(pendingBadgeCount(items), 1)
    }

    func testUnsharedInviteIsHiddenAndNotCounted() {
        let items = pendingItems(invites: [invite("A", ago: 5 * min, shared: false)], responses: [],
                                 contactIds: [], nowMillis: T)
        XCTAssertTrue(items.isEmpty)
        XCTAssertEqual(pendingBadgeCount(items), 0)
    }

    func testSheetPresentedOnlyInviteIsListedAndAgreesWithAwaitingInvites() {
        var rec = invite("S", ago: 1 * min, shared: false)
        rec.sheetPresentedAtMillis = T - 1 * min
        let items = pendingItems(invites: [rec], responses: [], contactIds: [], nowMillis: T)
        XCTAssertEqual(items.map(\.id), ["S"])
        XCTAssertEqual(items.first?.kind, .inviteAwaiting)
        XCTAssertEqual(pendingBadgeCount(items), 0)
        XCTAssertGreaterThanOrEqual(items.count, awaitingInvites([rec], nowMillis: T).count)
    }

    func testSharedInviteAppearsAsAwaiting() {
        let items = pendingItems(invites: [invite("A", ago: 5 * min, shared: true)], responses: [],
                                 contactIds: [], nowMillis: T)
        XCTAssertEqual(items.map(\.kind), [.inviteAwaiting])
        XCTAssertEqual(pendingBadgeCount(items), 0)
    }

    @MainActor
    func testBadgeDropsAfterMarkShared() throws {
        // 回应:真正经 PairingResponseStore.markShared 后角标 1 → 0。
        let responses = PairingResponseStore(defaults: FakeAppGroupDefaults())
        try responses.put(PairingResponseRecord(fingerprintHex: "R", responseWire: "rw", inviteDigest: "d",
                                                createdAtMillis: T - 1 * min, lastSharedAtMillis: nil))
        XCTAssertEqual(pendingBadgeCount(pendingItems(invites: [], responses: responses.records,
                                                      contactIds: ["R"], nowMillis: T)), 1)
        try responses.markShared(fingerprintHex: "R", at: T)
        XCTAssertEqual(pendingBadgeCount(pendingItems(invites: [], responses: responses.records,
                                                      contactIds: ["R"], nowMillis: T)), 0)

        // 邀请:经 PendingInviteStore.update 标记已分享后同理。
        let invites = PendingInviteStore(defaults: FakeAppGroupDefaults())
        try invites.append(invite("A", ago: 5 * min, shared: false))
        XCTAssertTrue(pendingItems(invites: invites.records, responses: [], contactIds: [], nowMillis: T).isEmpty)
        try invites.update(id: "A") { $0.lastSharedAtMillis = T }
        let shared = pendingItems(invites: invites.records, responses: [], contactIds: [], nowMillis: T)
        XCTAssertEqual(shared.map(\.kind), [.inviteAwaiting])
        XCTAssertEqual(pendingBadgeCount(shared), 0)
    }

    func testEqualTimestampsOrderByIdRegardlessOfInputOrder() {
        let a = invite("a", ago: 5 * min, shared: true)
        let b = invite("b", ago: 5 * min, shared: true)
        for input in [[a, b], [b, a]] {
            let items = pendingItems(invites: input, responses: [], contactIds: [], nowMillis: T)
            XCTAssertEqual(items.map(\.id), ["a", "b"])
        }
    }

    func testExactly30DaysIsNotStale() {
        let items = pendingItems(invites: [invite("X", ago: 30 * day, shared: true)], responses: [],
                                 contactIds: [], nowMillis: T)
        XCTAssertFalse(items[0].isStale)
        let over = pendingItems(invites: [invite("X", ago: 30 * day + 1, shared: true)], responses: [],
                                contactIds: [], nowMillis: T)
        XCTAssertTrue(over[0].isStale)
    }

    func testMigratedInviteCannotResend() {
        let items = pendingItems(invites: [invite("M", ago: 1 * min, shared: true, wire: "")], responses: [],
                                 contactIds: [], nowMillis: T)
        XCTAssertFalse(items[0].canResend)
        XCTAssertEqual(items[0].kind, .inviteAwaiting)
    }

    func testUnsentResponseFingerprints() {
        let (_, res) = tableK()
        XCTAssertEqual(unsentResponseFingerprints(responses: res, contactIds: ["R", "R2"]), ["R"])
    }
}
