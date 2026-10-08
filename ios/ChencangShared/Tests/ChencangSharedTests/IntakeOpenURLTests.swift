import XCTest
@testable import ChencangShared

final class IntakeOpenURLTests: XCTestCase {
    func testMakeProducesExpectedURLs() {
        XCTAssertEqual(IntakeOpenURL.make(.pendingIntake).absoluteString, "camo://intake")
        XCTAssertEqual(IntakeOpenURL.make(.addContact).absoluteString, "camo://add-contact")
        XCTAssertEqual(IntakeOpenURL.make(.home).absoluteString, "camo://home")
    }

    func testRoundTrip() {
        for link in [IntakeOpenURL.pendingIntake, .addContact, .home] {
            XCTAssertEqual(IntakeOpenURL.parse(url: IntakeOpenURL.make(link)), link)
        }
    }

    func testOtherURLsAreNotSwallowed() {
        XCTAssertNil(IntakeOpenURL.parse(url: ThreadOpenURL.make(peerId: "alice", highlight: "m1")))
        XCTAssertNil(IntakeOpenURL.parse(url: URL(string: "camo://pairing/x")!))
        XCTAssertNil(IntakeOpenURL.parse(url: URL(string: "camo://pairing?code=x")!))
        XCTAssertNil(IntakeOpenURL.parse(url: URL(string: "https://site.test/intake")!))
    }

    func testRejectsLegacyChencangScheme() {
        XCTAssertNil(IntakeOpenURL.parse(url: URL(string: "chencang://intake")!))
    }
}
