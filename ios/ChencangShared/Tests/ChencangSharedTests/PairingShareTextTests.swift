import XCTest
#if canImport(AppKit)
import SwiftUI
import AppKit
#endif
import Chencang
@testable import ChencangShared

final class PairingShareTextTests: XCTestCase {
    let site = "https://site.test/"
    let wire = PairingTransport.bundleToWire(Data([0xA0, 0x01]))
    let responseWire = PairingTransport.headerToWire(Data([0xA0, 0x01]))

    func testInviteIsHeaderWithPairingLink() {
        let link = PairingLink.make(site: site, wire: wire)
        let text = PairingShareText.compose(wire: wire, site: site, isResponse: false)
        XCTAssertEqual(text, L10n.pairingShareHeaderInvite(link))
        XCTAssertTrue(text.hasSuffix(link))
        XCTAssertFalse(text.contains(wire), "文字版不再附 🔒 乱码")
        XCTAssertFalse(text.contains("//p/"))
    }

    func testResponseIsHeaderWithPairingLink() {
        let link = PairingLink.make(site: site, wire: responseWire)
        let text = PairingShareText.compose(wire: responseWire, site: site, isResponse: true)
        XCTAssertEqual(text, L10n.pairingShareHeaderResponse(link))
        XCTAssertTrue(text.hasSuffix(link))
    }

    func testBothHeadersStartWithLockOnTheFirstLine() {
        XCTAssertTrue(L10n.pairingShareHeaderInvite("https://x/p/#a").components(separatedBy: "\n")[0].hasPrefix("\u{1F512}"))
        XCTAssertTrue(L10n.pairingShareHeaderResponse("https://x/p/#a").components(separatedBy: "\n")[0].hasPrefix("\u{1F512}"))
    }

    func testInviteAndResponseTextsClassifyAsTheirKind() {
        let invite = PairingShareText.compose(wire: wire, site: site, isResponse: false)
        let response = PairingShareText.compose(wire: responseWire, site: site, isResponse: true)
        XCTAssertEqual(IntakeClassifier.classify(invite), .pairingInvite(wire: wire))
        XCTAssertEqual(IntakeClassifier.classify(response), .pairingResponse(wire: responseWire))
    }

    func testHeaderWithoutLinkIsIncomplete() {
        XCTAssertEqual(IntakeClassifier.classify(L10n.pairingShareHeaderInvite("")), .incomplete)
        XCTAssertEqual(IntakeClassifier.classify(L10n.pairingShareHeaderResponse("")), .incomplete)
    }

    func testCardTitleByNameAndKind() {
        XCTAssertEqual(PairingCardText.title(isResponse: false, name: "小明"), L10n.shareCardInviteTitle("小明"))
        XCTAssertEqual(PairingCardText.title(isResponse: true, name: " 小明 "), L10n.shareCardResponseTitle("小明"))
        XCTAssertEqual(PairingCardText.title(isResponse: false, name: "  "), L10n.shareCardInviteTitleAnonymous)
        XCTAssertEqual(PairingCardText.title(isResponse: true, name: ""), L10n.shareCardResponseTitleAnonymous)
    }

    func testCardStepAndPitchByKind() {
        XCTAssertEqual(PairingCardText.step(isResponse: false), L10n.shareCardStepInvite)
        XCTAssertEqual(PairingCardText.step(isResponse: true), L10n.shareCardStepResponse)
        XCTAssertEqual(PairingCardText.pitch(isResponse: false), L10n.shareCardInvitePitch)
        XCTAssertNil(PairingCardText.pitch(isResponse: true))
        XCTAssertEqual(PairingCardText.note(isResponse: false), L10n.shareCardInviteNote)
        XCTAssertEqual(PairingCardText.note(isResponse: true), L10n.shareCardResponseNote)
    }

    #if canImport(AppKit)
    /// 版面放得下:最坏文案(20 字昵称标题)下内容自然高度不超过 480pt,说明文字不被挤掉。
    @MainActor
    func testCardContentFitsInCardHeight() {
        let longName = String(repeating: "陈", count: 20)
        for isResponse in [false, true] {
            let view = PairingCardView(isResponse: isResponse, name: longName, link: "https://x/p/#abc").content
                .frame(width: Moyu.Size.shareCardWidth)
            let size = NSHostingController(rootView: view).sizeThatFits(in: CGSize(width: Moyu.Size.shareCardWidth, height: 10_000))
            XCTAssertLessThanOrEqual(size.height, Moyu.Size.shareCardHeight, "isResponse=\(isResponse) 高度 \(size.height)")
        }
    }
    #endif
}
