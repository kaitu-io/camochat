import XCTest
@testable import ChencangShared
import Chencang

/// In-process round-trip for the V1 wire codec on the host (macOS test
/// bundle slice of the XCFramework).
///
/// Mints two PQXDH sessions, builds an in-band text frame, runs the full
/// sender flow (encodeTextFrame → Session.encryptToBytes → encodeWire), then
/// reverses it on the receiver session and asserts the recovered text matches.
///
/// Mirrors `SessionV1WireRoundTripTest` on Android.
final class SessionV1WireRoundTripTests: XCTestCase {

    func testTextFrameRoundTripsThroughPQXDHSessionPairAndWireCodec() throws {
        let (bobSess, aliceSess) = try mintLoopbackSessionPair()
        let text = "陈仓 UAT round-trip \(UUID().uuidString)"

        let frame = encodeTextFrame(text: text)

        let ct = try bobSess.encryptToBytes(plaintext: frame)
        let wire = encodeWire(ciphertext: ct)

        print(
            "[text-frame round-trip] wire length: \(wire.count) graphemes "
            + "(\(wire.utf8.count) UTF-8 bytes), frame=\(frame.count)B, ct=\(ct.count)B"
        )
        XCTAssertTrue(wire.hasPrefix("🔒"), "wire must start with U+1F512")

        // Reverse on receiver side.
        let ct2 = try decodeWire(s: wire)
        XCTAssertEqual(ct2, ct, "decodeWire(encodeWire(x)) must equal x")

        let frame2 = try aliceSess.decryptFromBytes(ciphertext: ct2)
        XCTAssertEqual(frame2, frame, "DR round-trip must recover the framed bytes")

        let msg = try decodeFrame(bytes: frame2)
        guard case let .text(recoveredText) = msg else {
            return XCTFail("expected text, got \(msg)")
        }
        XCTAssertEqual(recoveredText, text, "text round-trip")
    }

    /// Run the full PQXDH handshake and produce a (Bob, Alice) Session pair.
    /// Mirrors `UATLoopback.seed()` byte-for-byte; copied here so this test
    /// doesn't depend on the Companion's seeding pipeline.
    private func mintLoopbackSessionPair() throws -> (Session, Session) {
        let bobIk = SecretIdentity()
        let aliceIk = SecretIdentity()
        let aliceSpk = try SecretSignedPreKey(ik: aliceIk, spkVersion: 1)
        let aliceOpk = SecretOneTimePreKey(opkIndex: 7)

        let bundle = PreKeyBundle(
            ik: aliceIk.publicIdentity(),
            spk: aliceSpk.publicForm(),
            opk: aliceOpk.publicForm(),
            inviterUsername: "alice",
            inviteId: Data(repeating: 0x11, count: 16),
            pairingNonce: Data(repeating: 0x22, count: 16)
        )

        let initOut = try deriveInitiatorHandshake(bobIk: bobIk, aliceBundle: bundle)
        let srkAlice = try deriveResponderHandshake(
            aliceIk: aliceIk,
            aliceSpk: aliceSpk,
            aliceOpk: aliceOpk,
            bobIkPub: initOut.bobIdentityPublic,
            bobEkXPub: initOut.ekX25519Pub,
            bobEkKemPub: initOut.ekMlkemPub,
            kemCtToSpk: initOut.kemCtToSpk,
            kemCtToIk: initOut.kemCtToIk,
            kemCtToOpk: initOut.kemCtToOpk,
            pairingNonce: Data(repeating: 0x22, count: 16)
        )

        let sid = Data([9, 9, 9, 9, 9])
        let bobSess = try Session.initiatorAfterHandshake(
            sessionRootKey: initOut.sessionRootKey,
            sessionId: sid,
            aliceIkPublic: aliceIk.publicIdentity(),
            ekX25519Secret: initOut.ekX25519Secret!,
            ekMlkemSecret: initOut.ekMlkemSecret!
        )
        let aliceSess = try Session.responderAfterHandshake(
            sessionRootKey: srkAlice,
            sessionId: sid,
            initiatorOutput: initOut
        )
        return (bobSess, aliceSess)
    }
}
