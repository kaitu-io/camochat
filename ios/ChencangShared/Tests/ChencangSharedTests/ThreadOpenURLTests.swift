import XCTest
@testable import ChencangShared

final class ThreadOpenURLTests: XCTestCase {
    func testRoundTripWithHighlight() {
        let url = ThreadOpenURL.make(peerId: "abc-123", highlight: "msg-9")
        let parsed = ThreadOpenURL.parse(url: url)
        XCTAssertEqual(parsed?.peerId, "abc-123")
        XCTAssertEqual(parsed?.highlight, "msg-9")
    }

    func testRoundTripWithoutHighlight() {
        let url = ThreadOpenURL.make(peerId: "abc-123", highlight: nil)
        let parsed = ThreadOpenURL.parse(url: url)
        XCTAssertEqual(parsed?.peerId, "abc-123")
        XCTAssertNil(parsed?.highlight)
    }

    func testRoundTripWithSpecialCharactersInPeerId() {
        let url = ThreadOpenURL.make(peerId: "a/b c&d=e", highlight: "h/i j")
        let parsed = ThreadOpenURL.parse(url: url)
        XCTAssertEqual(parsed?.peerId, "a/b c&d=e")
        XCTAssertEqual(parsed?.highlight, "h/i j")
    }

    func testMissingPeerReturnsNil() {
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://thread")!))
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://thread?highlight=h1")!))
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://thread?peer=")!))
    }

    func testOtherHostReturnsNil() {
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://record?peer=abc")!))
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://pairing?peer=abc")!))
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "https://site.test/i/xyz")!))
    }

    func testDoesNotSwallowRecordOrPairingURLs() {
        // These must round-trip through their own parsers untouched by ThreadOpenURL.
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://record?peer=abc-123")!))
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://pairing/xyz")!))
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "camo://pairing?code=xyz")!))
    }

    func testRejectsLegacyChencangScheme() {
        XCTAssertNil(ThreadOpenURL.parse(url: URL(string: "chencang://thread?peer=abc")!))
    }
}
