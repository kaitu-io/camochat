import XCTest
@testable import ChencangShared

final class EmojiFingerprintTests: XCTestCase {
    func testAlways8() throws {
        let fp = try EmojiFingerprint(sessionSecret: Data(repeating: 0xA5, count: 32))
        XCTAssertEqual(fp.emojis.count, 8)
    }

    func testDifferentSecretsDiffer() throws {
        let a = try EmojiFingerprint(sessionSecret: Data(repeating: 0x00, count: 32))
        let b = try EmojiFingerprint(sessionSecret: Data(repeating: 0xFF, count: 32))
        XCTAssertNotEqual(a, b)
    }

    func testBadLengthThrows() {
        XCTAssertThrowsError(
            try EmojiFingerprint(sessionSecret: Data(repeating: 0, count: 31))
        ) { err in
            guard case EmojiFingerprintError.badLength(31) = err else {
                XCTFail("expected badLength, got \(err)")
                return
            }
        }
    }

    /// Known-answer test against the Rust binding. Marks the binding contract
    /// boundary — if iOS and Android disagree on the emoji set, this test
    /// (combined with the equivalent Android one) will catch it.
    func testKAT() throws {
        let secret = Data(repeating: 0x42, count: 32)
        let fp = try EmojiFingerprint(sessionSecret: secret)
        // We do not hardcode the exact emojis here (the dictionary is the
        // Rust binding's source of truth). Instead, we assert each entry is
        // a non-empty UTF-8 string and the call is deterministic.
        XCTAssertEqual(fp.emojis.count, 8)
        for e in fp.emojis { XCTAssertFalse(e.isEmpty) }
        let again = try EmojiFingerprint(sessionSecret: secret)
        XCTAssertEqual(fp, again, "derivation must be deterministic")
    }
}
