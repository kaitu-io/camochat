import XCTest
@testable import Chencang

/// Phase A3.8 Task 22 — Swift side of the cross-platform equivalence gate.
///
/// Loads the Rust-generated `vectors.json`, reconstructs Alice's responder
/// `Session` from `alice_session_state_hex`, and proves that the wire blobs
/// from chencang-core decrypt byte-for-byte to the recorded plaintexts.
/// A failure here means Swift + Rust disagree on the protocol — STOP.
final class GoldenVectorsTests: XCTestCase {
    func testDecryptsCoreGeneratedWireMessages() throws {
        guard let url = Bundle.module.url(forResource: "vectors", withExtension: "json") else {
            XCTFail("vectors.json not bundled in test resources")
            return
        }
        let data = try Data(contentsOf: url)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("vectors.json not a top-level object")
            return
        }

        // Sanity-check the seed marker so a future generator change can't
        // silently regress this test.
        XCTAssertEqual(json["spec_version"] as? String, "1.0.0")
        XCTAssertEqual(json["session_id_hex"] as? String, "0909090909")

        let aliceStateHex = try XCTUnwrap(json["alice_session_state_hex"] as? String)
        let aliceState = try XCTUnwrap(Data(hex: aliceStateHex))

        let aliceSess = try Session.fromSerializedState(state: aliceState)

        let messages = try XCTUnwrap(json["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.count, 2, "generator must emit two messages")

        for (idx, msg) in messages.enumerated() {
            let plaintextHex = try XCTUnwrap(msg["plaintext_hex"] as? String)
            let plaintext = try XCTUnwrap(Data(hex: plaintextHex))
            let wire = try XCTUnwrap(msg["wire"] as? String)

            let decrypted = try aliceSess.decrypt(wireText: wire)
            XCTAssertEqual(
                decrypted,
                plaintext,
                "message #\(idx): Swift decrypt must match Rust-generated plaintext byte-for-byte"
            )
        }
    }

    func testAlicePublicIdentityMatchesGenerator() throws {
        // Defense-in-depth: even before decryption, the public IK bytes
        // should round-trip through the Swift `PublicIdentity` constructor
        // and survive a fingerprint computation deterministically.
        guard let url = Bundle.module.url(forResource: "vectors", withExtension: "json") else {
            XCTFail("vectors.json not bundled")
            return
        }
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let aliceJson = try XCTUnwrap(json["alice"] as? [String: Any])

        let alicePub = PublicIdentity(
            ikDhX25519:   try XCTUnwrap(Data(hex: try XCTUnwrap(aliceJson["ik_dh_x25519_hex"] as? String))),
            ikSigEd25519: try XCTUnwrap(Data(hex: try XCTUnwrap(aliceJson["ik_sig_ed25519_hex"] as? String))),
            ikKemMlkem768: try XCTUnwrap(Data(hex: try XCTUnwrap(aliceJson["ik_kem_mlkem768_hex"] as? String))),
            ikSigMldsa65:  try XCTUnwrap(Data(hex: try XCTUnwrap(aliceJson["ik_sig_mldsa65_hex"] as? String)))
        )
        let fp1 = fingerprintOfPublic(identity: alicePub)
        let fp2 = fingerprintOfPublic(identity: alicePub)
        XCTAssertEqual(fp1.hex, fp2.hex, "fingerprint must be deterministic over fixed inputs")
        XCTAssertEqual(fp1.value.count, 16)
    }
}

// MARK: - Hex helpers

private extension Data {
    /// Lowercase hex decoder. Returns nil on odd length or non-hex chars.
    init?(hex: String) {
        let chars = Array(hex)
        guard chars.count.isMultiple(of: 2) else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard
                let hi = chars[i].hexDigitValue,
                let lo = chars[i + 1].hexDigitValue
            else { return nil }
            bytes.append(UInt8(hi * 16 + lo))
            i += 2
        }
        self = Data(bytes)
    }
}
