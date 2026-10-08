import XCTest
@testable import ChencangShared

final class AwaitingInvitesTests: XCTestCase {
    private let now: Int64 = 1_000_000_000_000
    private let day: Int64 = 24 * 60 * 60 * 1000

    private func rec(_ id: String, created: Int64? = nil, shared: Int64? = nil, sheet: Int64? = nil) -> PendingPairingRecord {
        PendingPairingRecord(
            pairingId: id, pairingNonceB64: "n", createdAtMillis: created ?? now - day,
            lastSharedAtMillis: shared, sheetPresentedAtMillis: sheet)
    }

    func testNeitherSharedNorSheetOpenedDoesNotCount() {
        XCTAssertEqual(awaitingInvites([rec("a")], nowMillis: now).map(\.pairingId), [])
    }

    func testSharedCounts() {
        XCTAssertEqual(awaitingInvites([rec("a", shared: now)], nowMillis: now).map(\.pairingId), ["a"])
    }

    func testOnlySheetOpenedCounts() {
        XCTAssertEqual(awaitingInvites([rec("a", sheet: now)], nowMillis: now).map(\.pairingId), ["a"])
    }

    func testEightDaysOldDoesNotCountSixDaysDoes() {
        let old = rec("old", created: now - 8 * day, shared: now - 8 * day)
        let fresh = rec("fresh", created: now - 6 * day, shared: now - 6 * day)
        XCTAssertEqual(awaitingInvites([old, fresh], nowMillis: now).map(\.pairingId), ["fresh"])
    }
}
