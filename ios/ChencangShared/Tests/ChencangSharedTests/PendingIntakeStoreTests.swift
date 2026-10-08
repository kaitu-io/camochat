import XCTest
@testable import ChencangShared

final class PendingIntakeStoreTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    private final class Clock: @unchecked Sendable {
        var now: Date
        init(_ now: Date) { self.now = now }
    }

    private func makeStore(_ defaults: FakeAppGroupDefaults, _ clock: Clock) -> PendingIntakeStore {
        PendingIntakeStore(defaults: defaults, now: { clock.now })
    }

    func testPutThenTakeReturnsWireOnce() throws {
        let defaults = FakeAppGroupDefaults()
        let store = makeStore(defaults, Clock(t0))
        try store.put(wire: "🔒PAIRINV-1")
        XCTAssertEqual(store.take(), "🔒PAIRINV-1")
        XCTAssertNil(store.take())
        XCTAssertNil(defaults.data(forKey: PendingIntakeStore.key))
    }

    func testTakeIgnoresIntakeOlderThan30Minutes() throws {
        let defaults = FakeAppGroupDefaults()
        let clock = Clock(t0)
        let store = makeStore(defaults, clock)
        try store.put(wire: "🔒PAIRINV-1")
        clock.now = t0.addingTimeInterval(1801)
        XCTAssertNil(store.take())
        XCTAssertNil(defaults.data(forKey: PendingIntakeStore.key))
    }

    func testExactly30MinutesIsStillValid() throws {
        let clock = Clock(t0)
        let store = makeStore(FakeAppGroupDefaults(), clock)
        try store.put(wire: "🔒PAIRINV-1")
        clock.now = t0.addingTimeInterval(1800)
        XCTAssertEqual(store.take(), "🔒PAIRINV-1")
    }

    func testPutOverwritesPrevious() throws {
        let store = makeStore(FakeAppGroupDefaults(), Clock(t0))
        try store.put(wire: "🔒PAIRINV-1")
        try store.put(wire: "🔒PAIRINV-2")
        XCTAssertEqual(store.take(), "🔒PAIRINV-2")
        XCTAssertNil(store.take())
    }

    func testCorruptSlotIsClearedAndIgnored() {
        let defaults = FakeAppGroupDefaults()
        defaults.set(Data("not json".utf8), forKey: PendingIntakeStore.key)
        let store = makeStore(defaults, Clock(t0))
        XCTAssertNil(store.take())
        XCTAssertNil(defaults.data(forKey: PendingIntakeStore.key))
    }
}
