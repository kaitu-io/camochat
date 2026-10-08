import XCTest
@testable import ChencangShared

final class PeerNameTests: XCTestCase {
    func testNilAndBlankAreNil() {
        XCTAssertNil(PeerName.sanitize(nil))
        XCTAssertNil(PeerName.sanitize(""))
        XCTAssertNil(PeerName.sanitize("  "))
    }

    func testNormalNameUnchanged() {
        XCTAssertEqual(PeerName.sanitize("老王"), "老王")
    }

    func testBidiOverrideStripped() {
        XCTAssertEqual(PeerName.sanitize("老王\u{202E}"), "老王")
    }

    func testInvisibleFormatAndSeparatorsStripped() {
        XCTAssertEqual(PeerName.sanitize("a\u{200B}b\u{FEFF}c\u{2028}d\u{2029}e\u{061C}f"), "abcdef")
        XCTAssertEqual(PeerName.sanitize("a\u{0007}b\nc"), "abc")
        XCTAssertNil(PeerName.sanitize("\u{200B}\u{FEFF}"))
    }

    func testSupplementaryPlaneFormatStripped() {
        XCTAssertEqual(PeerName.sanitize("a\u{E0001}b"), "ab")
        // Tag sequence (Scotland flag): tags stripped, plain black flag remains.
        XCTAssertEqual(PeerName.sanitize("\u{1F3F4}\u{E0067}\u{E0062}\u{E0073}\u{E0063}\u{E0074}\u{E007F}"), "\u{1F3F4}")
    }

    func testZwjEmojiSequencePreserved() {
        XCTAssertEqual(PeerName.sanitize("👨\u{200D}👩\u{200D}👧"), "👨\u{200D}👩\u{200D}👧")
    }

    func testTooLongIsClamped() {
        XCTAssertEqual(PeerName.sanitize(String(repeating: "字", count: 100)), String(repeating: "字", count: 8))
    }
}
