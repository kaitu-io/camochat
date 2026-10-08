import XCTest
import Chencang
@testable import ChencangShared

final class MediaShareTextTests: XCTestCase {
    private let site = "https://site.test/"
    // 文字分享首行跟随发件人系统语言(spec §4.1),后接换行 + wire;首行不含任何加密内容或明文。
    func testTextFirstLineUsesLocalizedHeader() {
        XCTAssertEqual(MediaShareText.forText("🔒ABC", site: site),
                       L10n.cardShareHeaderText("https://site.test/m/") + "\n🔒ABC")
        XCTAssertTrue(MediaShareText.forText("🔒ABC", site: site).hasPrefix("🔒 "))
    }

    func testMediaFirstLineCarriesBlobLink() throws {
        let blobId = "BBBBBBBBBBBBBBBBBBBBBB"
        let item = MediaItem(index: 0, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 1,
                             blobSecret: Data(), blobId: blobId, state: .sealed)
        let wire = encodeWire(ciphertext: Data((0..<48).map { UInt8($0) }))
        let text = MediaShareText.compose(kind: .image, items: [item, item], wire: wire, site: site)
        let firstLine = try XCTUnwrap(text.split(separator: "\n").first.map(String.init))
        XCTAssertEqual(firstLine, L10n.cardShareHeaderMedia(L10n.mediaPhotoCount(2), link: "https://site.test/m/\(blobId)"))
        XCTAssertTrue(firstLine.contains("https://site.test/m/\(blobId)"))
        XCTAssertEqual(WireLocator.extract(text), wire)
    }
}
