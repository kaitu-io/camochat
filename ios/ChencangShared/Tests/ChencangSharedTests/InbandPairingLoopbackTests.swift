import XCTest
@testable import ChencangShared
import Chencang

/// Two-identity loopback proof for the iOS in-band classical pairing engine
/// (host macOS slice of the XCFramework — no device, no server, no network).
///
/// Mirrors chencang-android's `InbandPairingLoopbackTest` /
/// `InbandPairingDeviceTest`: A mints an invite bundle, B accepts (deriving its
/// session + response header), A completes statelessly from B's header + the
/// public pairing nonce, and we assert both sides land on the SAME 8-emoji
/// safety number and that their Double-Ratchet sessions talk bidirectionally.
///
/// The whole exchange is additionally routed through ``PairingTransport`` to
/// prove the 🔒 wire envelope (0xCB 0x01 bundle / 0xCB 0x02 header) classifies
/// and round-trips on the Swift side exactly as on Android.
final class InbandPairingLoopbackTests: XCTestCase {

    /// A = inviter = responder; B = invitee = initiator. Full 2-round handshake.
    func testTwoIdentityPairingAgreesOnEmojiAndSessionsTalk() throws {
        // A = inviter = responder
        let aIk = SecretIdentity()
        let aSpk = try SecretSignedPreKey(ik: aIk, spkVersion: 1)

        // B = invitee = initiator
        let bIk = SecretIdentity()

        // Round 1: A mints the invite bundle.
        let pending = try InbandPairing.buildInviteBundle(
            aIk: aIk, aSpk: aSpk, inviterUsername: "alice"
        )
        XCTAssertEqual(pending.pairingNonce.count, 16)

        // The bundle travels as a 🔒 wire; classify + strip envelope on B's side.
        let bundleWire = PairingTransport.bundleToWire(pending.bundleBytes)
        XCTAssertEqual(PairingTransport.classify(bundleWire), .pairingBundle)
        let bundleBytes = try PairingTransport.pairingPayload(bundleWire)
        XCTAssertEqual(bundleBytes, pending.bundleBytes)

        // Round 2a: B accepts → session + response header.
        let bAccept = try InbandPairing.accept(
            bIk: bIk, bundleBytes: bundleBytes, bDisplayName: "bob"
        )
        XCTAssertEqual(bAccept.emoji.count, 8)

        // The header travels back as a 🔒 wire; classify + strip on A's side.
        let headerWire = PairingTransport.headerToWire(bAccept.headerBytes)
        XCTAssertEqual(PairingTransport.classify(headerWire), .pairingHeader)
        let headerBytes = try PairingTransport.pairingPayload(headerWire)

        // Round 2b: A completes statelessly from B's header + the public nonce.
        let aComplete = try InbandPairing.complete(
            aIk: aIk, aSpk: aSpk, pairingNonce: pending.pairingNonce, headerBytes: headerBytes
        )

        // Both sides must agree on the SRK-derived safety number.
        XCTAssertEqual(aComplete.emoji, bAccept.emoji)
        XCTAssertEqual(aComplete.emoji.count, 8)

        // Each side records the OTHER's classical fingerprint as the peer id.
        XCTAssertNotEqual(aComplete.peerFingerprintHex, bAccept.peerFingerprintHex)
        XCTAssertEqual(aComplete.peerDeviceId, aComplete.peerFingerprintHex)

        // Sessions actually talk: B → A and A → B.
        let msgBA = Data((0..<24).map { UInt8($0) })
        let ctBA = try bAccept.session.encryptToBytes(plaintext: msgBA)
        XCTAssertEqual(try aComplete.session.decryptFromBytes(ciphertext: ctBA), msgBA)

        let msgAB = Data((0..<40).map { UInt8(($0 * 3) & 0xFF) })
        let ctAB = try aComplete.session.encryptToBytes(plaintext: msgAB)
        XCTAssertEqual(try bAccept.session.decryptFromBytes(ciphertext: ctAB), msgAB)
    }

    /// A tampered confirmation tag must make A's `complete` reject — no silent
    /// success when the two sides did NOT agree on the root key.
    func testTamperedHeaderFailsKeyConfirmation() throws {
        let aIk = SecretIdentity()
        let aSpk = try SecretSignedPreKey(ik: aIk, spkVersion: 1)
        let bIk = SecretIdentity()

        let pending = try InbandPairing.buildInviteBundle(
            aIk: aIk, aSpk: aSpk, inviterUsername: "alice"
        )
        let bAccept = try InbandPairing.accept(
            bIk: bIk, bundleBytes: pending.bundleBytes, bDisplayName: nil
        )

        // Flip one byte of the encoded header's confirm tag region. Decode →
        // mutate confirmB → re-encode so it still parses but fails the MAC.
        let header = try decodeClassicalHeader(bytes: bAccept.headerBytes)
        var badTag = header.confirmB
        badTag[badTag.startIndex] ^= 0xFF
        let tampered = ClassicalInbandHeader(
            version: header.version,
            suiteId: header.suiteId,
            bobIk: header.bobIk,
            ekX25519Pub: header.ekX25519Pub,
            sessionId: header.sessionId,
            confirmB: badTag,
            bobDisplayName: header.bobDisplayName
        )
        let tamperedBytes = try encodeClassicalHeader(header: tampered)

        XCTAssertThrowsError(
            try InbandPairing.complete(
                aIk: aIk, aSpk: aSpk,
                pairingNonce: pending.pairingNonce, headerBytes: tamperedBytes
            )
        ) { error in
            XCTAssertTrue(error is InbandPairing.PairingFailedError, "expected PairingFailedError, got \(error)")
        }
    }

    /// Non-pairing text (and voice-shaped wires) must not classify as pairing,
    /// and `pairingPayload` must reject them.
    func testClassifyRejectsNonPairingWires() throws {
        XCTAssertEqual(PairingTransport.classify("just some text"), .unknown)
        XCTAssertEqual(PairingTransport.classify("🔒not-base32768-😀"), .unknown)
        XCTAssertThrowsError(try PairingTransport.pairingPayload("plain text"))
    }
}
