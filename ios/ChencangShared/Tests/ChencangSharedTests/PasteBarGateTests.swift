import XCTest
@testable import ChencangShared

final class PasteBarGateTests: XCTestCase {
    func testShowsOnFirstInstallWithTextAndNoConsumedRecord() {
        XCTAssertTrue(PasteBarGate.shouldShow(hasStrings: true, changeCount: 5, consumed: nil))
    }

    func testHiddenWithoutStrings() {
        XCTAssertFalse(PasteBarGate.shouldShow(hasStrings: false, changeCount: 5, consumed: nil))
        XCTAssertFalse(PasteBarGate.shouldShow(hasStrings: false, changeCount: 5, consumed: 4))
    }

    func testHiddenWhenChangeCountAlreadyConsumed() {
        XCTAssertFalse(PasteBarGate.shouldShow(hasStrings: true, changeCount: 5, consumed: 5))
    }

    func testShowsWhenChangeCountMovedPastConsumed() {
        XCTAssertTrue(PasteBarGate.shouldShow(hasStrings: true, changeCount: 6, consumed: 5))
    }

    func testShowsAfterCounterResetBelowConsumed() {
        // 重启后 changeCount 归零重计:与旧值不等就显示(宁多提示一次,不漏)。
        XCTAssertTrue(PasteBarGate.shouldShow(hasStrings: true, changeCount: 1, consumed: 5))
    }

    func testStoreRoundTripOnIndependentSuite() throws {
        let suite = "PasteBarGateTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = PasteBarConsumedStore(defaults: defaults)
        XCTAssertNil(store.consumed)
        store.consumed = 42
        XCTAssertEqual(PasteBarConsumedStore(defaults: defaults).consumed, 42)
        XCTAssertEqual(PasteBarConsumedStore.key, "cc.pasteBar.consumedChangeCount.v1")
        store.consumed = 0
        XCTAssertEqual(store.consumed, 0)
    }
}
