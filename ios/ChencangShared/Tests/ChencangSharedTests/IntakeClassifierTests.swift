import XCTest
import Chencang
@testable import ChencangShared

final class IntakeClassifierTests: XCTestCase {
    let invite = PairingTransport.bundleToWire(Data([0xA0, 0x01]))
    let response = PairingTransport.headerToWire(Data([0xA0, 0x01]))
    let session = encodeWire(ciphertext: Data([0xCC, 0xC8, 0x01, 0x02]))

    func testEmptyAndWhitespaceIsEmpty()            { XCTAssertEqual(IntakeClassifier.classify("  \n"), .empty) }
    func testPlainTextIsNotOurs()                   { XCTAssertEqual(IntakeClassifier.classify("你好 hello"), .notOurs) }
    func testLockWithUndecodableBodyIsIncomplete()  { XCTAssertEqual(IntakeClassifier.classify("🔒abc"), .incomplete) }
    func testSessionWire()                          { XCTAssertEqual(IntakeClassifier.classify(session), .sessionMessage(wire: session)) }
    func testInviteAndResponse() {
        XCTAssertEqual(IntakeClassifier.classify(invite), .pairingInvite(wire: invite))
        XCTAssertEqual(IntakeClassifier.classify(response), .pairingResponse(wire: response))
    }
    func testPairingCodeWithShareHeaderAndNickname() {
        XCTAssertEqual(IntakeClassifier.classify("小明：" + PairingShareText.compose(wire: invite, site: ConfigRepository.shared.current().shareSite, isResponse: false)),
                       .pairingInvite(wire: invite))
    }
    func testNicknamePrefixOnWireLine() {
        XCTAssertEqual(IntakeClassifier.classify("小明：" + invite), .pairingInvite(wire: invite))
    }
    func testTextShareTwoLineFormIsSession() {
        XCTAssertEqual(IntakeClassifier.classify(MediaShareText.forText(session, site: ConfigRepository.shared.current().shareSite)), .sessionMessage(wire: session))
    }
    func testTrailingQuoteStripped() { XCTAssertEqual(IntakeClassifier.classify("「\(invite)」"), .pairingInvite(wire: invite)) }
    func testLocateFindsWireAfterHeader() {
        XCTAssertEqual(PairingTransport.locate(PairingShareText.compose(wire: response, site: ConfigRepository.shared.current().shareSite, isResponse: true)), response)
    }

    // 只复制到首行链接(长按落在链接上):单独一条 /m/ 或 /p/ 链接。
    func testBareShareLinkIsLinkOnly() {
        XCTAssertEqual(IntakeClassifier.classify("https://d3nnqcgewrb4f0.cloudfront.net/m/"), .linkOnly)
        XCTAssertEqual(IntakeClassifier.classify("  https://x.example/p/ \n"), .linkOnly)
        XCTAssertEqual(IntakeClassifier.classify("「https://x.example/m/」"), .linkOnly)
    }
    func testOtherLinksAndTextAreNotLinkOnly() {
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/m/abc"), .notOurs)
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/"), .notOurs)
        XCTAssertEqual(IntakeClassifier.classify("http://x.example/m/"), .notOurs)
        XCTAssertEqual(IntakeClassifier.classify("看看 https://x.example/m/"), .notOurs)
        XCTAssertEqual(IntakeClassifier.classify("你好 hello"), .notOurs)
    }
    func testLockMessageWithLinkIsNotLinkOnly() {
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/m/\n" + session), .sessionMessage(wire: session))
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/m/\n🔒abc"), .incomplete)
    }
}
