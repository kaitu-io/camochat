import XCTest
@testable import ChencangShared

@MainActor
final class ThreadCleanerTests: XCTestCase {
    private var base: URL!

    override func setUp() async throws {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-cleaner-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: base)
    }

    private func msg(_ id: String, peer: String) -> ChatMessage {
        ChatMessage(id: id, peerId: peer, direction: .incoming, body: "x",
                    timestamp: Date(timeIntervalSince1970: 100), status: .received)
    }

    func testClearThreadDeletesMessagesAndTheirMediaOnly() async throws {
        let store = try ChatStore(directory: base.appendingPathComponent("chat"))
        let files = MediaFiles(root: base.appendingPathComponent("media"))
        try store.append(msg("a1", peer: "alice"))
        try store.append(msg("a2", peer: "alice"))
        try store.append(msg("b1", peer: "bob"))
        for id in ["a1", "a2", "b1"] {
            try files.write(Data([1]), to: files.binURL(messageId: id, index: 0))
        }

        var calls: [[String]] = []
        var filesPresentAtCancel = false
        try await ThreadCleaner(chatStore: store, mediaFiles: files, cancelUploads: { ids in
            calls.append(ids)
            filesPresentAtCancel = ids.allSatisfy { files.exists(files.binURL(messageId: $0, index: 0)) }
        }).clear(peerId: "alice")

        XCTAssertTrue(store.messages(for: "alice").isEmpty)
        XCTAssertFalse(files.exists(files.directory(messageId: "a1")))
        XCTAssertFalse(files.exists(files.directory(messageId: "a2")))
        XCTAssertTrue(files.exists(files.binURL(messageId: "b1", index: 0)))
        XCTAssertEqual(store.messages(for: "bob").map(\.id), ["b1"])
        XCTAssertEqual(calls, [["a1", "a2"]], "清空会话一次批量取消全部上传")
        XCTAssertTrue(filesPresentAtCancel, "先取消,再删文件")
    }

    func testDeleteMessageRemovesRecordAndFiles() async throws {
        let store = try ChatStore(directory: base.appendingPathComponent("chat"))
        let files = MediaFiles(root: base.appendingPathComponent("media"))
        try store.append(msg("a1", peer: "alice"))
        try store.append(msg("a2", peer: "alice"))
        try files.write(Data([1]), to: files.binURL(messageId: "a1", index: 0))

        var cancelled: [String] = []
        try await ThreadCleaner(chatStore: store, mediaFiles: files,
                                cancelUploads: { cancelled.append(contentsOf: $0) }).deleteMessage(id: "a1", peerId: "alice")

        XCTAssertEqual(store.messages(for: "alice").map(\.id), ["a2"])
        XCTAssertFalse(files.exists(files.directory(messageId: "a1")))
        let reloaded = try ChatStore(directory: base.appendingPathComponent("chat"))
        XCTAssertEqual(reloaded.messages(for: "alice").map(\.id), ["a2"])
        XCTAssertEqual(cancelled, ["a1"])
    }
}
