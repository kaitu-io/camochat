import XCTest
@testable import ChencangShared

final class MediaFilesTests: XCTestCase {
    private var base: URL!
    private var files: MediaFiles!

    override func setUp() {
        super.setUp()
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-media-tests-\(UUID().uuidString)", isDirectory: true)
        files = MediaFiles(root: base.appendingPathComponent("media", isDirectory: true))
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: base)
        super.tearDown()
    }

    func testLayoutAndWriteAndBackupExclusion() throws {
        try files.write(Data([1, 2, 3]), to: files.ccaURL(messageId: "m1", index: 0))
        try files.write(Data([4]), to: files.binURL(messageId: "m1", index: 1))

        XCTAssertEqual(files.ccaURL(messageId: "m1", index: 0).path,
                       files.root.appendingPathComponent("m1/0.cca").path)
        XCTAssertEqual(files.binURL(messageId: "m1", index: 1).lastPathComponent, "1.bin")
        XCTAssertEqual(try Data(contentsOf: files.ccaURL(messageId: "m1", index: 0)), Data([1, 2, 3]))

        // 重新构造 URL，避免读到 URL 对象内缓存的资源值
        let values = try URL(fileURLWithPath: files.root.path).resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testDeleteMessageRemovesOnlyThatMessage() throws {
        try files.write(Data([1]), to: files.binURL(messageId: "m1", index: 0))
        try files.write(Data([2]), to: files.binURL(messageId: "m2", index: 0))

        files.delete(messageId: "m1")

        XCTAssertFalse(files.exists(files.directory(messageId: "m1")))
        XCTAssertTrue(files.exists(files.binURL(messageId: "m2", index: 0)))
    }

    func testDeleteAllRemovesRootAndIsIdempotent() throws {
        try files.write(Data([1]), to: files.binURL(messageId: "m1", index: 0))
        try files.deleteAll()
        XCTAssertFalse(files.exists(files.root))
        XCTAssertNoThrow(try files.deleteAll())
    }

    func testPlayableURLHardLinksBinWithExtension() throws {
        try files.write(Data([9, 9]), to: files.binURL(messageId: "m1", index: 0))
        let url = try files.playableURL(messageId: "m1", index: 0, ext: "mp4")
        XCTAssertEqual(url.lastPathComponent, "0.mp4")
        XCTAssertEqual(try Data(contentsOf: url), Data([9, 9]))
        XCTAssertEqual(try files.playableURL(messageId: "m1", index: 0, ext: "mp4"), url) // 第二次不报错
        files.delete(messageId: "m1")
        XCTAssertFalse(files.exists(url))
    }
}
