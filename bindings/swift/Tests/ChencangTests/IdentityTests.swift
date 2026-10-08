import XCTest
@testable import Chencang

/// Verifies the identity surface and that facade-level length validation
/// surfaces as a typed `ChencangError` on the Swift side. The first two cases
/// exercise the public fingerprint helper; the third deliberately constructs
/// a malformed `PublicIdentity` (ikDhX25519 has 31 bytes instead of 32) and
/// asserts that the resulting throw maps to `ChencangError.InvalidLength`,
/// which is the uniffi-mapped variant of `chencang_core::Error::InvalidLength`.
final class IdentityTests: XCTestCase {
    func testFingerprintIs16Bytes() {
        let id = SecretIdentity()
        let fp = fingerprintOfPublic(identity: id.publicIdentity())
        XCTAssertEqual(fp.value.count, 16, "fingerprint must be 16 bytes")
        XCTAssertEqual(fp.hex.count, 32, "hex fingerprint must be 32 ASCII chars")
    }

    func testFingerprintDifferentIdentitiesDiffer() {
        let a = SecretIdentity()
        let b = SecretIdentity()
        let fpa = fingerprintOfPublic(identity: a.publicIdentity())
        let fpb = fingerprintOfPublic(identity: b.publicIdentity())
        XCTAssertNotEqual(fpa.value, fpb.value,
                          "two fresh identities must hash to different fingerprints")
    }

    func testInvalidLengthErrorPropagates() {
        // Build a structurally malformed PublicIdentity: ikDhX25519 must be
        // exactly 32 bytes but we pass 31. The facade should reject this
        // *before* doing any handshake math, so the throw site is the
        // call into `Session.initiatorAfterHandshake`.
        let badPublic = PublicIdentity(
            ikDhX25519: Data(repeating: 1, count: 31),
            ikSigEd25519: Data(repeating: 2, count: 32),
            ikKemMlkem768: Data(repeating: 3, count: 1184),
            ikSigMldsa65: Data(repeating: 4, count: 1952)
        )
        let sid = Data([1, 2, 3, 4, 5])
        XCTAssertThrowsError(
            try Session.initiatorAfterHandshake(
                sessionRootKey: Data(repeating: 0, count: 32),
                sessionId: sid,
                aliceIkPublic: badPublic,
                ekX25519Secret: Data(repeating: 0, count: 32),
                ekMlkemSecret: Data(repeating: 0, count: 32)
            )
        ) { err in
            guard case ChencangError.InvalidLength = err else {
                XCTFail("expected ChencangError.InvalidLength, got \(err)")
                return
            }
        }
    }
}
