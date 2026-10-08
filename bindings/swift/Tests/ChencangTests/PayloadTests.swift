import XCTest
@testable import Chencang

/// Exercises the V1 payload surface exposed via uniffi:
/// - V1 wire codec: encodeTextFrame / decodeFrame / encodeWire / decodeWire
/// - HKDF blob-material derivation
/// - `.cca` media blob encrypt/decrypt (spec 2026-09-25 rich-media)
/// - 8-emoji safety fingerprint derivation
final class PayloadTests: XCTestCase {
    // MARK: - Media blob crypto (spec 2026-09-25)

    func testBlobRoundTripImage() throws {
        let plaintext = "jpeg bytes".data(using: .utf8)!
        let sealed = try encryptMediaBlob(plaintext: plaintext, kind: 2)
        XCTAssertEqual([UInt8](sealed.blob.prefix(4)), [0x43, 0x43, 0x41, 0x31])
        XCTAssertEqual(sealed.blob[5], 0x02)
        XCTAssertEqual(sealed.blobSecret.count, 32)
        XCTAssertEqual(sealed.blobId, try mediaBlobId(blobSecret: sealed.blobSecret))
        let roundTrip = try decryptMediaBlob(blob: sealed.blob, blobSecret: sealed.blobSecret, expectedKind: 2)
        XCTAssertEqual(roundTrip, plaintext)
    }

    func testEncryptGeneratesDistinctSecretsEachCall() throws {
        let a = try encryptMediaBlob(plaintext: Data("x".utf8), kind: 2)
        let b = try encryptMediaBlob(plaintext: Data("x".utf8), kind: 2)
        XCTAssertNotEqual(a.blobSecret, b.blobSecret)
        XCTAssertNotEqual(a.blobId, b.blobId)
    }

    func testDecryptWrongSecretLengthThrows() throws {
        let sealed = try encryptMediaBlob(plaintext: Data(), kind: 2)
        let badSecret = Data(repeating: 0x42, count: 31)
        XCTAssertThrowsError(
            try decryptMediaBlob(blob: sealed.blob, blobSecret: badSecret, expectedKind: 2)
        ) { err in
            guard case ChencangError.InvalidLength = err else {
                XCTFail("expected InvalidLength, got \(err)")
                return
            }
        }
    }

    func testBlobDecryptWrongSecretFails() throws {
        let wrong = Data(repeating: 0xFF, count: 32)
        let sealed = try encryptMediaBlob(plaintext: Data("hello".utf8), kind: 2)
        XCTAssertThrowsError(try decryptMediaBlob(blob: sealed.blob, blobSecret: wrong, expectedKind: 2)) { err in
            guard case ChencangError.AeadFailed = err else {
                XCTFail("expected AeadFailed, got \(err)")
                return
            }
        }
    }

    func testBlobDecryptKindMismatchFails() throws {
        let sealed = try encryptMediaBlob(plaintext: Data("hello".utf8), kind: 2)
        XCTAssertThrowsError(try decryptMediaBlob(blob: sealed.blob, blobSecret: sealed.blobSecret, expectedKind: 1)) { err in
            guard case ChencangError.Decoding = err else {
                XCTFail("expected Decoding, got \(err)")
                return
            }
        }
    }

    func testMediaBlobIdDeterministic() throws {
        let secret = Data(repeating: 0x42, count: 32)
        XCTAssertEqual(try mediaBlobId(blobSecret: secret), try mediaBlobId(blobSecret: secret))
    }

    // MARK: - V1 wire codec

    func testWireRoundTrip() throws {
        let ct = Data(repeating: 0xAB, count: 80)
        let s = encodeWire(ciphertext: ct)
        XCTAssertTrue(s.hasPrefix("\u{1F512}"))
        let back = try decodeWire(s: s)
        XCTAssertEqual(back, ct)
    }

    func testDecodeWireMissingPrefix() {
        XCTAssertThrowsError(try decodeWire(s: "nope")) { err in
            guard case ChencangError.Decoding = err else {
                XCTFail("expected Decoding, got \(err)")
                return
            }
        }
    }

    func testDecodeFrameGarbageFails() {
        let garbage = Data([0xFF, 0xDE, 0xAD, 0xBE, 0xEF])
        XCTAssertThrowsError(try decodeFrame(bytes: garbage)) { err in
            guard case ChencangError.Decoding = err else {
                XCTFail("expected Decoding, got \(err)")
                return
            }
        }
    }

    // MARK: - HKDF blob-material derivation

    func testDeriveBlobMaterialDeterministic() throws {
        let secret = Data(repeating: 0x42, count: 32)
        let a = try deriveBlobMaterial(blobSecret: secret)
        let b = try deriveBlobMaterial(blobSecret: secret)
        XCTAssertEqual(a.blobId, b.blobId)
        XCTAssertEqual(a.blobKey, b.blobKey)
        XCTAssertEqual(a.blobNonce, b.blobNonce)
        XCTAssertEqual(a.blobId.count, 16)
        XCTAssertEqual(a.blobKey.count, 32)
        XCTAssertEqual(a.blobNonce.count, 24)
    }

    func testDeriveBlobMaterialDistinctSecretsDistinctOutput() throws {
        let a = try deriveBlobMaterial(blobSecret: Data(repeating: 0x00, count: 32))
        let b = try deriveBlobMaterial(blobSecret: Data(repeating: 0xFF, count: 32))
        XCTAssertNotEqual(a.blobId, b.blobId)
        XCTAssertNotEqual(a.blobKey, b.blobKey)
        XCTAssertNotEqual(a.blobNonce, b.blobNonce)
    }

    func testDeriveBlobMaterialSpecVector1() throws {
        // Same pinned vector as core/src/payload/hkdf.rs `spec_vector_1_all_zero`.
        let m = try deriveBlobMaterial(blobSecret: Data(repeating: 0x00, count: 32))
        XCTAssertEqual(m.blobId.map { String(format: "%02x", $0) }.joined(),
                       "31766f35b0acb82ed8cb94d35eae977e")
        XCTAssertEqual(m.blobKey.map { String(format: "%02x", $0) }.joined(),
                       "4b00904f34f16918709cb862aa8225b66514ea91a8d9ca114e14a10ba320f4c9")
        XCTAssertEqual(m.blobNonce.map { String(format: "%02x", $0) }.joined(),
                       "22e9328a3fda5a567c35cb2e0bc19616ce2f7efab72af8e2")
    }

    // MARK: - Safety emoji

    func testSafetyEmojiLength() throws {
        let secret = Data(repeating: 0xA5, count: 32)
        let emoji = try deriveSafetyEmoji(sessionSecret: secret)
        XCTAssertEqual(emoji.count, 8)
        for e in emoji {
            XCTAssertFalse(e.isEmpty)
        }
    }

    func testSafetyEmojiWrongLengthThrows() {
        let bad = Data(repeating: 0x00, count: 31)
        XCTAssertThrowsError(try deriveSafetyEmoji(sessionSecret: bad)) { err in
            guard case ChencangError.InvalidLength = err else {
                XCTFail("expected InvalidLength, got \(err)")
                return
            }
        }
    }

    func testSafetyEmojiDistinctForDistinctSecrets() throws {
        let a = try deriveSafetyEmoji(sessionSecret: Data(repeating: 0x00, count: 32))
        let b = try deriveSafetyEmoji(sessionSecret: Data(repeating: 0xFF, count: 32))
        XCTAssertNotEqual(a, b)
    }
}
