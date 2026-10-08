import Chencang
import Foundation
import XCTest

final class MediaBindingGoldenTests: XCTestCase {
    private func media() throws -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath)
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent("bindings/golden-vectors/vectors.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let obj = try JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as! [String: Any]
                return obj["media"] as! [String: Any]
            }
            dir.deleteLastPathComponent()
        }
        throw XCTSkip("vectors.json not found")
    }

    private func hex(_ s: String) -> Data {
        var d = Data(capacity: s.count / 2)
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            d.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return d
    }

    func testGoldenBlobDecryptsAndIdMatches() throws {
        let m = try media()
        let secret = hex(m["blob_secret_hex"] as! String)
        let kind = UInt8(m["kind"] as! Int)
        XCTAssertEqual(try mediaBlobId(blobSecret: secret), m["blob_id"] as! String)
        XCTAssertEqual(try decryptMediaBlob(blob: hex(m["cca_hex"] as! String), blobSecret: secret, expectedKind: kind),
                       hex(m["plaintext_hex"] as! String))
        // encrypt_media_blob no longer takes a caller-supplied secret (F2
        // fix — core generates it to rule out nonce reuse), so it can't
        // reproduce the golden ciphertext byte-for-byte any more. Cover the
        // same ground with a round trip plus the id/secret derivation
        // relation instead.
        let sealed = try encryptMediaBlob(plaintext: hex(m["plaintext_hex"] as! String), kind: kind)
        XCTAssertEqual(sealed.blobId, try mediaBlobId(blobSecret: sealed.blobSecret))
        XCTAssertEqual(try decryptMediaBlob(blob: sealed.blob, blobSecret: sealed.blobSecret, expectedKind: kind),
                       hex(m["plaintext_hex"] as! String))
    }

    func testGoldenFrameDecodesToMediaRef() throws {
        let m = try media()
        guard case let .media(refs) = try decodeFrame(bytes: hex(m["media_ref_frame_hex"] as! String)) else {
            return XCTFail("expected media")
        }
        XCTAssertEqual(refs.count, 1)
        XCTAssertEqual(refs[0].width, 1920)
        XCTAssertEqual(refs[0].blobSecret, hex(m["blob_secret_hex"] as! String))
    }
}
