import XCTest
@testable import ChencangShared

@MainActor
final class ChatStoreTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-chat-tests-\(UUID().uuidString)", isDirectory: true)
    }
    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func msg(_ id: String, peer: String, at t: TimeInterval,
                     dir d: ChatMessage.Direction = .outgoing,
                     status: ChatMessage.Status = .sealed) -> ChatMessage {
        ChatMessage(id: id, peerId: peer, direction: d, body: "hi-\(id)",
                    timestamp: Date(timeIntervalSince1970: t), status: status)
    }

    func testAppendPersistsAcrossReload() throws {
        let a = try ChatStore(directory: dir)
        try a.append(msg("1", peer: "alice", at: 100))
        try a.append(msg("2", peer: "alice", at: 200))
        let b = try ChatStore(directory: dir)   // 重新加载 = 模拟冷启动
        XCTAssertEqual(b.messages(for: "alice").map(\.id), ["1", "2"])
    }

    func testMessagesSortedByTimestampAscending() throws {
        let s = try ChatStore(directory: dir)
        try s.append(msg("late", peer: "a", at: 300))
        try s.append(msg("early", peer: "a", at: 100))
        XCTAssertEqual(s.messages(for: "a").map(\.id), ["early", "late"])
    }

    func testSetStatusPersists() throws {
        let s = try ChatStore(directory: dir)
        try s.append(msg("1", peer: "a", at: 100, status: .sealed))
        try s.setStatus(id: "1", peerId: "a", .copied)
        XCTAssertEqual(s.messages(for: "a").first?.status, .copied)
        let reload = try ChatStore(directory: dir)
        XCTAssertEqual(reload.messages(for: "a").first?.status, .copied)
    }

    func testLegacyThreadFileLoadsAndRewritesWithNewStatus() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let legacy = #"""
        [{"body":"a","direction":"out","id":"1","peerId":"p","status":"sent","timestamp":1000},
         {"body":"b","direction":"in","id":"2","peerId":"p","status":"sent","timestamp":2000},
         {"body":"c","direction":"out","id":"3","peerId":"p","status":"sealed","timestamp":3000}]
        """#
        let file = dir.appendingPathComponent("p.json")
        try Data(legacy.utf8).write(to: file)

        let s = try ChatStore(directory: dir)
        XCTAssertEqual(s.messages(for: "p").map(\.status), [.shared, .received, .sealed])

        try s.setStatus(id: "3", peerId: "p", .copied)
        let text = try String(contentsOf: file, encoding: .utf8)
        XCTAssertFalse(text.contains("\"sent\""), text)
        XCTAssertTrue(text.contains("shared"))
        XCTAssertTrue(text.contains("received"))
        XCTAssertTrue(text.contains("copied"))
    }

    func testLatestPerPeer() throws {
        let s = try ChatStore(directory: dir)
        try s.append(msg("a1", peer: "a", at: 100))
        try s.append(msg("a2", peer: "a", at: 200))
        try s.append(msg("b1", peer: "b", at: 150))
        let latest = s.latestPerPeer()
        XCTAssertEqual(latest["a"]?.id, "a2")
        XCTAssertEqual(latest["b"]?.id, "b1")
    }

    func testClearPeerAndClearAll() throws {
        let s = try ChatStore(directory: dir)
        try s.append(msg("1", peer: "a", at: 100))
        try s.append(msg("2", peer: "b", at: 100))
        try s.clear(peerId: "a")
        XCTAssertTrue(s.messages(for: "a").isEmpty)
        XCTAssertFalse(s.messages(for: "b").isEmpty)
        try s.clearAll()
        XCTAssertTrue(s.latestPerPeer().isEmpty)
        XCTAssertTrue(try ChatStore(directory: dir).latestPerPeer().isEmpty)
    }

    func testPeerIdUnsafeForFilenameStillWorks() throws {
        let s = try ChatStore(directory: dir)
        let weird = "a/b:c 🔒"
        try s.append(msg("1", peer: weird, at: 100))
        XCTAssertEqual(try ChatStore(directory: dir).messages(for: weird).count, 1)
    }

    /// 先分享、后上传:已分享(body 非空)的发送端 `uploading` 冷启动不再打成 failed——
    /// 系统后台会话可能还在替我们传,交给上传引擎对账;没分享的中断仍按失败处理;接收端 downloading → pending 不变。
    func testResetInterruptedKeepsOutgoingUploading() throws {
        func item(_ i: Int, _ state: MediaItem.State) -> MediaItem {
            MediaItem(index: i, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 1,
                      blobSecret: Data([1]), blobId: "b\(i)", state: state)
        }
        let s = try ChatStore(directory: dir)
        try s.append(ChatMessage(id: "shared", peerId: "alice", direction: .outgoing, body: "🔒 分享文本",
                                 timestamp: Date(timeIntervalSince1970: 10), status: .sealed, kind: .image,
                                 media: [item(0, .uploading), item(1, .sealed), item(2, .failed)]))
        try s.append(ChatMessage(id: "unshared", peerId: "alice", direction: .outgoing, body: "",
                                 timestamp: Date(timeIntervalSince1970: 20), status: .sealed, kind: .image,
                                 media: [item(0, .encrypting), item(1, .uploading)]))
        try s.append(ChatMessage(id: "in", peerId: "alice", direction: .incoming, body: "🔒",
                                 timestamp: Date(timeIntervalSince1970: 30), status: .received, kind: .image,
                                 media: [item(0, .downloading)]))

        try s.resetInterruptedTransfers()

        let reloaded = try ChatStore(directory: dir)
        XCTAssertEqual(reloaded.message(id: "shared", peerId: "alice")?.media?.map(\.state), [.uploading, .sealed, .failed])
        XCTAssertEqual(reloaded.message(id: "unshared", peerId: "alice")?.media?.map(\.state), [.failed, .failed])
        XCTAssertEqual(reloaded.message(id: "in", peerId: "alice")?.media?.map(\.state), [.pending])
    }

    /// 首次解锁前被系统在后台拉起:线程文件存在但读不了(数据保护)。不能把它当成「空会话」——
    /// 否则下一次写入会用只含新消息的列表覆盖掉整个历史。读不了的会话标记未加载、拒绝写入,
    /// 数据可用后 `reloadUnreadable` 再读回来(并只对这些会话做冷启动复位)。
    func testUnreadableThreadIsNotTreatedAsEmptyAndRefusesWritesUntilReload() throws {
        let seed = try ChatStore(directory: dir)
        try seed.append(msg("1", peer: "alice", at: 100))
        try seed.append(msg("2", peer: "bob", at: 100))
        let original = try Data(contentsOf: dir.appendingPathComponent("alice.json"))

        final class Gate { var locked = true }
        let gate = Gate()
        let store = try ChatStore(directory: dir, reader: { url in
            if gate.locked, url.lastPathComponent == "alice.json" {
                throw NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoPermissionError)
            }
            return try Data(contentsOf: url)
        })

        XCTAssertFalse(store.isFullyLoaded)
        XCTAssertEqual(store.unreadablePeers, ["alice"])
        XCTAssertTrue(store.messages(for: "alice").isEmpty)
        XCTAssertEqual(store.messages(for: "bob").map(\.id), ["2"], "读得了的会话照常")
        XCTAssertThrowsError(try store.append(msg("3", peer: "alice", at: 200))) {
            XCTAssertEqual($0 as? ChatStore.StoreError, .threadNotLoaded)
        }
        XCTAssertTrue(store.messages(for: "alice").isEmpty)
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("alice.json")), original, "磁盘上的历史原样")
        XCTAssertNoThrow(try store.append(msg("4", peer: "bob", at: 200)))

        XCTAssertEqual(try store.reloadUnreadable(), [], "还锁着:什么都没读回")
        XCTAssertFalse(store.isFullyLoaded)

        gate.locked = false
        XCTAssertEqual(try store.reloadUnreadable(), ["alice"])
        XCTAssertTrue(store.isFullyLoaded)
        XCTAssertEqual(store.messages(for: "alice").map(\.id), ["1"])
        try store.append(msg("3", peer: "alice", at: 200))
        XCTAssertEqual(try ChatStore(directory: dir).messages(for: "alice").map(\.id), ["1", "3"])
    }

    func testUndecodableThreadIsStillSkippedNotBlocked() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("not json".utf8).write(to: dir.appendingPathComponent("carol.json"))
        let store = try ChatStore(directory: dir)
        XCTAssertTrue(store.isFullyLoaded, "解不开 ≠ 读不了:沿用原来的跳过")
        XCTAssertNoThrow(try store.append(msg("1", peer: "carol", at: 100)))
    }

    func testResetInterruptedCanBeLimitedToReloadedPeers() throws {
        func item(_ state: MediaItem.State) -> MediaItem {
            MediaItem(index: 0, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 1,
                      blobSecret: Data([1]), blobId: "b", state: state)
        }
        let s = try ChatStore(directory: dir)
        for peer in ["alice", "bob"] {
            try s.append(ChatMessage(id: peer, peerId: peer, direction: .incoming, body: "🔒",
                                     timestamp: Date(timeIntervalSince1970: 10), status: .received, kind: .image,
                                     media: [item(.downloading)]))
        }
        try s.resetInterruptedTransfers(peers: ["alice"])
        XCTAssertEqual(s.message(id: "alice", peerId: "alice")?.media?.first?.state, .pending)
        XCTAssertEqual(s.message(id: "bob", peerId: "bob")?.media?.first?.state, .downloading, "本进程正在下的不动")
    }
}
