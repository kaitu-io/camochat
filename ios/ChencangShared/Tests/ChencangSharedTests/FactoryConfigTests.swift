import XCTest
@testable import ChencangShared

final class FactoryConfigTests: XCTestCase {
    private func bundled() throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "chencang-config", withExtension: "json"))
        return try Data(contentsOf: url)
    }

    func testBundledConfigMatchesReleaseFile() throws {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: dir.appendingPathComponent("release/config/chencang-config.json").path),
              dir.path != "/" {
            dir.deleteLastPathComponent()
        }
        let release = try Data(contentsOf: dir.appendingPathComponent("release/config/chencang-config.json"))
        XCTAssertEqual(try bundled(), release)
    }

    func testBundledConfigVerifiesWithProductionKey() throws {
        let c = try XCTUnwrap(SignedConfigCodec.decode(try bundled()))
        XCTAssertGreaterThanOrEqual(c.seq, 1)
        XCTAssertEqual(c.relays.count, 3)
    }
}
