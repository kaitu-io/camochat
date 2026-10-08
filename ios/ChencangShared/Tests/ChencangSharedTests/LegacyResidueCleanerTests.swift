import XCTest
@testable import ChencangShared

final class LegacyResidueCleanerTests: XCTestCase {
    func testRemovesResidueKeysAndModelDirsOnce() throws {
        let suite = "cc.test.residue.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        for k in LegacyResidueCleaner.residueKeys { defaults.set("x", forKey: k) }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let model = dir.appendingPathComponent("MimiEncoder25.mlmodelc")
        try FileManager.default.createDirectory(at: model, withIntermediateDirectories: true)
        let keep = dir.appendingPathComponent("chat")
        try FileManager.default.createDirectory(at: keep, withIntermediateDirectories: true)

        LegacyResidueCleaner.runOnce(defaults: defaults, containerURL: dir)

        for k in LegacyResidueCleaner.residueKeys { XCTAssertNil(defaults.object(forKey: k)) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: model.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: keep.path))
        XCTAssertTrue(defaults.bool(forKey: LegacyResidueCleaner.markerKey))

        // Second run is a no-op even if a key reappears.
        defaults.set("again", forKey: "cc.sticky.v1")
        LegacyResidueCleaner.runOnce(defaults: defaults, containerURL: dir)
        XCTAssertEqual(defaults.string(forKey: "cc.sticky.v1"), "again")
    }
}
