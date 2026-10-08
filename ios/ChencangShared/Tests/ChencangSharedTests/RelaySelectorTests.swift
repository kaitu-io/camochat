import XCTest
@testable import ChencangShared

final class RelaySelectorTests: XCTestCase {
    private let a = "https://a.test", b = "https://b.test", c = "https://c.test"
    private func defaults() -> UserDefaults { UserDefaults(suiteName: "rs-\(UUID().uuidString)")! }

    func testFollowsConfigOrderByDefault() {
        XCTAssertEqual(RelaySelector(relays: { [self.a, self.b, self.c] }, defaults: defaults()).ordered(), [a, b, c])
    }

    func testStickyGoodRelayFirst() {
        let s = RelaySelector(relays: { [self.a, self.b, self.c] }, defaults: defaults())
        s.markGood(c)
        XCTAssertEqual(s.ordered(), [c, a, b])
    }

    func testStaleStickyIgnoredWhenRemovedFromConfig() {
        var list = [a, b, c]
        let s = RelaySelector(relays: { list }, defaults: defaults())
        s.markGood(c)
        list = [a, b]
        XCTAssertEqual(s.ordered(), [a, b])
    }

    func testStickySurvivesNewSelectorOnSameDefaults() {
        let d = defaults()
        RelaySelector(relays: { [self.a, self.b] }, defaults: d).markGood(b)
        XCTAssertEqual(RelaySelector(relays: { [self.a, self.b] }, defaults: d).ordered(), [b, a])
    }
}
