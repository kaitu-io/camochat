import XCTest
import Chencang
@testable import ChencangShared

final class PairingURLParserTests: XCTestCase {
    func testCustomSchemePath() {
        let url = URL(string: "camo://pairing/MCRE-NDQ4-7BPR")!
        XCTAssertEqual(PairingURLParser.parse(url: url), "MCRE-NDQ4-7BPR")
    }

    func testCustomSchemeQuery() {
        let url = URL(string: "camo://pairing?code=ABCD-EFGH-1234")!
        XCTAssertEqual(PairingURLParser.parse(url: url), "ABCD-EFGH-1234")
    }

    func testHttpsLinksAreNoLongerAnEntryPoint() {
        let url = URL(string: "https://site.test/i/MCRE-NDQ4-7BPR")!
        XCTAssertNil(PairingURLParser.parse(url: url))
    }

    func testUnrelatedURLReturnsNil() {
        let url = URL(string: "https://example.com/something")!
        XCTAssertNil(PairingURLParser.parse(url: url))
    }

    private let invite = PairingTransport.bundleToWire(Data([0xA0, 0x01]))

    func testPairingWireFromCustomSchemeQuery() {
        var c = URLComponents()
        c.scheme = "camo"; c.host = "pairing"
        c.queryItems = [URLQueryItem(name: "code", value: invite)]
        XCTAssertEqual(PairingURLParser.pairingWire(url: c.url!), invite)
    }

    func testPairingWireFromHttpsPathIsNil() {
        let encoded = invite.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        let url = URL(string: "https://site.test/i/\(encoded)")!
        XCTAssertNil(PairingURLParser.pairingWire(url: url))
    }

    func testLegacyShortCodeIsNotAWire() {
        let url = URL(string: "camo://pairing/MCRE-NDQ4-7BPR")!
        XCTAssertEqual(PairingURLParser.parse(url: url), "MCRE-NDQ4-7BPR")
        XCTAssertNil(PairingURLParser.pairingWire(url: url))
    }

    func testRejectsLegacyChencangScheme() {
        XCTAssertNil(PairingURLParser.parse(url: URL(string: "chencang://pairing/ABC")!))
    }
}
