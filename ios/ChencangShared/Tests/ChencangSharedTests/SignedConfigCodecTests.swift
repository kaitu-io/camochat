import XCTest
@testable import ChencangShared

final class SignedConfigCodecTests: XCTestCase {
    private typealias T = ConfigTestSupport
    private func dec(_ e: Data) -> AppConfig? { SignedConfigCodec.decode(e, verify: T.fakeVerify) }

    func testDecodesValidEnvelope() throws {
        let c = try XCTUnwrap(dec(T.envelope(T.payload(seq: 7))))
        XCTAssertEqual(c.seq, 7)
        XCTAssertEqual(c.sources, ["https://a.example/c/"])
        XCTAssertEqual(c.relays, ["https://r.example"])
        XCTAssertEqual(c.shareSite, "https://s.example/")
    }

    func testRejectsBadSignature() {
        XCTAssertNil(dec(T.envelope(T.payload(), sig: Data(count: 64))))
        XCTAssertNil(dec(T.envelope(T.payload(), sig: T.fakeSig(Data("x".utf8)))))
    }

    func testRejectsSchema2() { XCTAssertNil(dec(T.envelope(T.payload(schema: 2)))) }

    func testRejectsHttpUrl() {
        XCTAssertNil(dec(T.envelope(T.payload(sources: #"["http://a.example/c/"]"#))))
        XCTAssertNil(dec(T.envelope(T.payload(relays: #"["http://r.example"]"#))))
        XCTAssertNil(dec(T.envelope(T.payload(shareSite: "http://s.example/"))))
        XCTAssertNil(dec(T.envelope(T.payload(mirrors: #"["http://m.example/x.apk"]"#))))
    }

    func testRejectsUppercaseSha() { XCTAssertNil(dec(T.envelope(T.payload(sha: T.sha.uppercased())))) }

    func testRejectsLatestBelowMin() {
        XCTAssertNil(dec(T.envelope(T.payload(versionCode: 2, minVersionCode: 3))))
    }

    func testIgnoresUnknownKeys() {
        XCTAssertNotNil(dec(T.envelope(T.payload(extra: #","future":{"a":1}"#))))
    }

    func testRejectsMalformedBase64AndJson() {
        XCTAssertNil(dec(Data(#"{"p":"!!!","s":"???"}"#.utf8)))
        XCTAssertNil(dec(Data("not json".utf8)))
    }

    func testRejectsStructuralViolations() {
        XCTAssertNil(dec(T.envelope(T.payload(sources: "[]"))))
        XCTAssertNil(dec(T.envelope(T.payload(sources: #"["https://a.example/c"]"#))))
        XCTAssertNil(dec(T.envelope(T.payload(relays: #"["https://r.example/"]"#))))
        XCTAssertNil(dec(T.envelope(T.payload(shareSite: "https://s.example"))))
        XCTAssertNil(dec(T.envelope(T.payload(mirrors: "[]"))))
    }
}
