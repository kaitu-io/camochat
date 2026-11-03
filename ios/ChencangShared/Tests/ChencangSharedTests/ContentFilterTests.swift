import XCTest
@testable import ChencangShared

final class ContentFilterTests: XCTestCase {
    private let filter = ContentFilter(words: ["ass", "傻逼"])

    func testParseSkipsCommentsAndBlanksAndTrims() {
        XCTAssertEqual(ContentFilter.parse("# c\nfoo\n\n 傻逼 \n"), ["foo", "傻逼"])
    }

    func testLatinWholeWordCaseInsensitive() {
        XCTAssertTrue(filter.isFlagged("you ASS!"))
        XCTAssertFalse(filter.isFlagged("my class"))
        XCTAssertFalse(filter.isFlagged("hello"))
    }

    func testCjkSubstring() {
        XCTAssertTrue(filter.isFlagged("你个傻逼啊"))
    }

    func testEmptyNotFlagged() {
        XCTAssertFalse(filter.isFlagged(""))
    }

    func testBundledListFlagsItsFirstWord() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "moderation_wordlist", withExtension: "txt"))
        let raw = try String(contentsOf: url, encoding: .utf8)
        let first = try XCTUnwrap(ContentFilter.parse(raw).first)
        XCTAssertTrue(ContentFilter.shared.isFlagged(first))
    }

    func testWhitespaceRunsNormalized() {
        XCTAssertTrue(ContentFilter(words: ["kill yourself"]).isFlagged("kill   yourself"))
    }

    func testShippedListDoesNotFlagOrdinaryWords() {
        XCTAssertFalse(ContentFilter.shared.isFlagged("个性交流"))
    }
}
