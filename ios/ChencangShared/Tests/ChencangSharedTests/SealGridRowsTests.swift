import XCTest
@testable import ChencangShared

/// iOS twin of Android's `EmojiSealGridTest` — same three cases, same fixture.
final class SealGridRowsTests: XCTestCase {

    func testEightEmojisSplitIntoTwoRowsOfFour() {
        let emojis = ["🌊", "🔥", "🌙", "⭐", "🍀", "🌸", "🐚", "🦋"]

        let rows = sealGridRows(emojis)

        XCTAssertEqual(rows, [Array(emojis[0..<4]), Array(emojis[4..<8])])
    }

    func testFewerThanEightFallsBackToASingleRow() {
        let emojis = ["🌊", "🔥", "🌙"]

        XCTAssertEqual(sealGridRows(emojis), [emojis])
    }

    func testEmptyListYieldsNoRows() {
        XCTAssertEqual(sealGridRows([]), [])
    }
}
