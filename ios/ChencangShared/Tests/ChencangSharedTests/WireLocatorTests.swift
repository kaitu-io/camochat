import XCTest
import Chencang
@testable import ChencangShared

final class WireLocatorTests: XCTestCase {
    private let wire = encodeWire(ciphertext: Data((0..<48).map { UInt8($0) }))
    private let caption = "🔒 陈仓加密图片 · 24小时内有效 https://site.test/m/AAAAAAAAAAAAAAAAAAAAAA"

    func testSingleLineWireUnchanged() {
        XCTAssertEqual(WireLocator.extract(wire), wire)
        XCTAssertEqual(WireLocator.extract("  \(wire)\n"), wire)
    }

    func testTwoLineMediaForm() {
        XCTAssertEqual(WireLocator.extract("\(caption)\n\(wire)"), wire)
    }

    /// 文字分享的两行形态(固定首行 + wire):首行解不开,取到的仍是 wire。
    func testTwoLineTextShareForm() {
        XCTAssertEqual(WireLocator.extract(MediaShareText.forText(wire, site: "https://site.test/")), wire)
        XCTAssertNil(WireLocator.extract(MediaShareText.textFirstLine(site: "https://site.test/")))
    }

    func testCRLFLineBreaks() {
        XCTAssertEqual(WireLocator.extract("\(caption)\r\n\(wire)\r\n"), wire)
    }

    func testCaptionAfterWire() {
        XCTAssertEqual(WireLocator.extract("\(wire)\n\(caption)"), wire)
    }

    func testWeChatNicknameAndQuotes() {
        XCTAssertEqual(WireLocator.extract("张三: \(caption)\n「\(wire)」"), wire)
        XCTAssertEqual(WireLocator.extract("abc\(wire)"), wire)
    }

    func testOnlyCaptionLineIsNotAWire() {
        XCTAssertNil(WireLocator.extract(caption))
    }

    func testNoLockReturnsNil() {
        XCTAssertNil(WireLocator.extract("这只是普通文字\n第二行"))
        XCTAssertNil(WireLocator.extract(""))
    }

    func testTriesLastCandidateFirst() {
        var tried: [String] = []
        _ = WireLocator.extract("🔒A\n🔒B") { tried.append($0); return false }
        XCTAssertEqual(tried, ["🔒B", "🔒A"])
    }
}
