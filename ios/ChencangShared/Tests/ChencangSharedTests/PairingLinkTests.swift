import XCTest
import Chencang
@testable import ChencangShared

final class PairingLinkTests: XCTestCase {
    let envelope = Data([0xCB, 0x01, 0x01, 0x02, 0x03])
    var wire: String { encodeWire(ciphertext: envelope) }

    func testMakeAndWiresRoundTrip() {
        let link = PairingLink.make(site: "https://x.example/", wire: wire)
        XCTAssertEqual(link, "https://x.example/p/#ywEBAgM")
        XCTAssertEqual(PairingLink.wires(in: link), [wire])
    }

    func testClassifyLinkAloneAndWrapped() {
        let link = PairingLink.make(site: "https://x.example/", wire: wire)
        let expected = IntakeKind.pairingInvite(wire: wire)
        XCTAssertEqual(IntakeClassifier.classify(link), expected)
        XCTAssertEqual(IntakeClassifier.classify("「\(link)」"), expected)
        XCTAssertEqual(IntakeClassifier.classify("\(link)快来加我"), expected)
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/p/?from=x#ywEBAgM"), expected)
        XCTAssertEqual(IntakeClassifier.classify("HTTPS://X.EXAMPLE/p/#ywEBAgM"), expected)
    }

    func testResponseEnvelopeClassifiesAsResponse() {
        let w = encodeWire(ciphertext: Data([0xCB, 0x02, 0x09]))
        XCTAssertEqual(IntakeClassifier.classify(PairingLink.make(site: "https://x.example/", wire: w)), .pairingResponse(wire: w))
    }

    func testUppercasePathIsNotALink() {
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/P/#ywEBAgM"), .notOurs)
    }

    func testBareLinkWithoutFragmentIsLinkOnly() {
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/p/"), .linkOnly)
    }

    func testBadFragmentFallsBack() {
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/p/#!!!"), .notOurs)
        // CC C8 会话魔数、CB 03 未知类型:都不是配对。
        let sessionFragment = PairingLink.base64url(Data([0xCC, 0xC8, 0x01]))
        let unknownFragment = PairingLink.base64url(Data([0xCB, 0x03, 0x01]))
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/p/#" + sessionFragment), .notOurs)
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/p/#" + unknownFragment), .notOurs)
        XCTAssertEqual(IntakeClassifier.classify("🔒 头\nhttps://x.example/p/#" + unknownFragment), .incomplete)
        XCTAssertEqual(IntakeClassifier.classify("https://x.example/p/#y"), .notOurs, "长度余 1 不是合法 base64")
    }

    func testRecognizedLockWireBeatsLink() {
        let session = encodeWire(ciphertext: Data([0xCC, 0xC8, 0x01, 0x02]))
        let link = PairingLink.make(site: "https://x.example/", wire: wire)
        XCTAssertEqual(IntakeClassifier.classify("\(link)\n\(session)"), .sessionMessage(wire: session))
    }

    func testLastValidLinkWins() {
        let other = encodeWire(ciphertext: Data([0xCB, 0x02, 0x07]))
        let text = PairingLink.make(site: "https://x.example/", wire: wire) + " " + PairingLink.make(site: "https://x.example/", wire: other)
        XCTAssertEqual(IntakeClassifier.classify(text), .pairingResponse(wire: other))
    }

    func testPairingLinkShape() {
        XCTAssertTrue(PairingLink.isPairingLinkShape(URL(string: "https://x.test/p/#abc")!))
        XCTAssertTrue(PairingLink.isPairingLinkShape(URL(string: "https://x.test/p/")!))
        XCTAssertTrue(PairingLink.isPairingLinkShape(URL(string: "HTTPS://x.test/p#")!))
        XCTAssertFalse(PairingLink.isPairingLinkShape(URL(string: "https://x.test/m/#abc")!))
        XCTAssertFalse(PairingLink.isPairingLinkShape(URL(string: "http://x.test/p/#abc")!))
        XCTAssertFalse(PairingLink.isPairingLinkShape(URL(string: "camo://p/#abc")!))
        XCTAssertFalse(PairingLink.isPairingLinkShape(URL(string: "https://x.test/pp/#abc")!))
    }

    func testDamagedFragmentHasShapeButNoWire() {
        let url = URL(string: "https://x.test/p/#A")!
        XCTAssertTrue(PairingLink.isPairingLinkShape(url))
        XCTAssertTrue(PairingLink.wires(in: url.absoluteString).isEmpty)
    }
}
