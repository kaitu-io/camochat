import XCTest
@testable import ChencangShared

final class HandshakeHeaderTests: XCTestCase {

    private func seq(_ n: Int) -> Data { Data((0..<n).map { UInt8($0 & 0xFF) }) }

    private func makeHeader(name: String?) -> HandshakeHeader {
        HandshakeHeader(
            bobIkX25519: seq(32),
            bobIkEd25519: seq(32),
            bobIkMlkem768: seq(1184),
            bobIkMldsa65: seq(1952),
            ekX25519Pub: seq(32),
            ekMlkemPub: seq(1184),
            kemCtToSpk: seq(1088),
            kemCtToIk: seq(1088),
            sessionId: Data([9, 9, 9, 9, 9]),
            bobDisplayName: name
        )
    }

    func testRoundTripWithName() throws {
        let h = makeHeader(name: "Alice 小a")
        let decoded = try HandshakeHeader.decode(h.encode())
        XCTAssertEqual(decoded, h)
        XCTAssertEqual(decoded.bobDisplayName, "Alice 小a")
    }

    func testRoundTripNilName() throws {
        let h = makeHeader(name: nil)
        let decoded = try HandshakeHeader.decode(h.encode())
        XCTAssertEqual(decoded, h)
        XCTAssertNil(decoded.bobDisplayName)
    }

    func testHighBitBytesSurviveSignedIntEncoding() throws {
        // Bytes 0x80–0xFF encode as CBOR negative ints in kotlinx; verify they
        // round-trip back to the exact byte values.
        let h = HandshakeHeader(
            bobIkX25519: Data([0x00, 0x7F, 0x80, 0xFF, 0xAA, 0xFE]),
            bobIkEd25519: Data([0xFF]), bobIkMlkem768: Data([0x80]),
            bobIkMldsa65: Data([0xAA]), ekX25519Pub: Data(),
            ekMlkemPub: Data([0x01]), kemCtToSpk: Data([0x20]),
            kemCtToIk: Data([0x21, 0x22]), sessionId: Data([1, 2, 3, 4, 5]),
            bobDisplayName: nil
        )
        XCTAssertEqual(try HandshakeHeader.decode(h.encode()), h)
    }

    /// Authoritative byte-for-byte parity with kotlinx-serialization-cbor 1.6.3
    /// (chencang-android's `HandshakeHeader`), captured from the Android side.
    /// The compact header crosses the 24-byte length boundary, includes an empty
    /// field, and exercises negative-byte encoding.
    func testKotlinxGoldenParity() throws {
        let named = makeCompact(name: "Hi")
        let nullName = makeCompact(name: nil)
        XCTAssertEqual(named.encode().ccHexLower, Self.goldenNamed)
        XCTAssertEqual(nullName.encode().ccHexLower, Self.goldenNull)
        // And decode the golden bytes back.
        XCTAssertEqual(try HandshakeHeader.decode(Data(hex: Self.goldenNamed)), named)
        XCTAssertEqual(try HandshakeHeader.decode(Data(hex: Self.goldenNull)), nullName)
    }

    private func makeCompact(name: String?) -> HandshakeHeader {
        HandshakeHeader(
            bobIkX25519: Data([0x00, 0x01]),
            bobIkEd25519: seq(30),
            bobIkMlkem768: Data([0xFF, 0xFE, 0xFD]),
            bobIkMldsa65: Data([0xAA]),
            ekX25519Pub: Data(),
            ekMlkemPub: Data([0x10, 0x11, 0x12, 0x13]),
            kemCtToSpk: Data([0x20]),
            kemCtToIk: Data([0x21, 0x22]),
            sessionId: Data([9, 9, 9, 9, 9]),
            bobDisplayName: name
        )
    }

    // Authoritative kotlinx-serialization-cbor 1.6.3 output (same class shape as
    // chencang-android's HandshakeHeader), emitted from a Kotlin replica.
    static let goldenNamed = "bf6b626f62496b5832353531399f0001ff6c626f62496b456432353531399f000102030405060708090a0b0c0d0e0f101112131415161718181819181a181b181c181dff6d626f62496b4d6c6b656d3736389f202122ff6c626f62496b4d6c64736136359f3855ff6b656b5832353531395075629fff6a656b4d6c6b656d5075629f10111213ff6a6b656d4374546f53706b9f1820ff696b656d4374546f496b9f18211822ff6973657373696f6e49649f0909090909ff6e626f62446973706c61794e616d65624869ff"
    static let goldenNull = "bf6b626f62496b5832353531399f0001ff6c626f62496b456432353531399f000102030405060708090a0b0c0d0e0f101112131415161718181819181a181b181c181dff6d626f62496b4d6c6b656d3736389f202122ff6c626f62496b4d6c64736136359f3855ff6b656b5832353531395075629fff6a656b4d6c6b656d5075629f10111213ff6a6b656d4374546f53706b9f1820ff696b656d4374546f496b9f18211822ff6973657373696f6e49649f0909090909ffff"
}

private extension Data {
    init(hex: String) {
        var d = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            d.append(UInt8(hex[idx..<next], radix: 16)!)
            idx = next
        }
        self = d
    }
}
