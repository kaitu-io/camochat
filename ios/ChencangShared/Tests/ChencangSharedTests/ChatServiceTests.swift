import XCTest
@testable import ChencangShared

/// 恒等-ish 假 crypto:wire = "🔒FAKE:<peerId>:<text>"。用来把 ChatService 的
/// 编排逻辑(封缄→落库/收 wire→入库/markCopied/markShared/ingest)与真会话解耦测试,
/// 镜像 Android `ChatRepositoryTest`(`IdentityCrypto` 同样的角色)。
struct EchoThreadCrypto: ThreadCrypto {
    func sealText(peerId: String, text: String) async throws -> String { "🔒FAKE:\(peerId):\(text)" }

    func sealMedia(peerId: String, items: [MediaItem]) async throws -> String {
        "🔒FAKEMEDIA:\(peerId):\(items[0].kind.rawValue):\(items.count)"
    }

    func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? {
        if wire.hasPrefix("🔒FAKEMEDIA:") {
            let p = wire.dropFirst("🔒FAKEMEDIA:".count).split(separator: ":")
            guard p.count == 3, candidates.contains(String(p[0])),
                  let raw = UInt8(p[1]), let kind = MediaKind(rawValue: raw), let n = Int(p[2]) else { return nil }
            let items = (0..<n).map { i in
                MediaItem(index: i, kind: kind, durMs: kind == .image ? 0 : 3_000,
                          width: kind == .voice ? 0 : 640, height: kind == .voice ? 0 : 480, byteLen: 100,
                          blobSecret: Data(repeating: UInt8(i + 1), count: 32), blobId: "fakeblob\(i)", state: .pending)
            }
            return OpenedWire(peerId: String(p[0]), content: .media(kind: kind.messageKind, items: items))
        }
        if wire.hasPrefix("🔒FAKEUNSUP:") {
            let peer = String(wire.dropFirst("🔒FAKEUNSUP:".count))
            guard candidates.contains(peer) else { return nil }
            return OpenedWire(peerId: peer, content: .unsupported)
        }
        guard wire.hasPrefix("🔒FAKE:") else { return nil }
        let parts = wire.dropFirst("🔒FAKE:".count).split(separator: ":", maxSplits: 1)
        guard parts.count == 2, candidates.contains(String(parts[0])) else { return nil }
        return OpenedWire(peerId: String(parts[0]), content: .text(String(parts[1])))
    }
}

@MainActor
final class ChatServiceTests: XCTestCase {
    private var dir: URL!
    private var store: ChatStore!
    private var service: ChatService!
    // 计数器而非常量:idempotent ingest(按 id 去重)之后,一个测试里连着调用
    // sendTextSealed + receiveWireText 必须拿到两个不同的 id——同一个 peer 下
    // 撞了 id,第二条会被误判成「已经落库过」而不会真正创建一条新消息。
    private var idCounter = 0

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-chatservice-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: dir)
        idCounter = 0
        service = ChatService(
            store: store,
            crypto: EchoThreadCrypto(),
            contactIds: { ["alice"] },
            now: { Date(timeIntervalSince1970: 42) },
            newId: { [weak self] in
                self!.idCounter += 1
                return "fixed-id-\(self!.idCounter)"
            },
            isWire: { $0.hasPrefix("🔒FAKE") }
        )
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    // ① sendTextSealed 返回 🔒 前缀 wire 且落一条 OUT/sealed。
    func testSendTextSealedReturnsLockPrefixedWireAndPersistsOutMessage() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "明天老地方见")
        XCTAssertTrue(sealed.wire.hasPrefix("🔒"))
        let thread = store.messages(for: "alice")
        XCTAssertEqual(thread.count, 1)
        XCTAssertEqual(thread[0].direction, .outgoing)
        XCTAssertEqual(thread[0].body, "明天老地方见")
        XCTAssertEqual(thread[0].status, .sealed)
    }

    // ② send→receive 回环成 IN/received(容忍前后空白)。
    func testReceiveWireTextRoundTripsSentWireIntoInMessage() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "你好")
        let received = try await service.receiveWireText("  \(sealed.wire)  ")
        let msg = try XCTUnwrap(received)
        XCTAssertEqual(msg.peerId, "alice")
        XCTAssertEqual(msg.direction, .incoming)
        XCTAssertEqual(msg.body, "你好")
        XCTAssertEqual(msg.status, .received)
    }

    // ③ 普通文本(非 wire)→ nil 且不落库。
    func testReceiveWireTextReturnsNilOnNonWireText() async throws {
        let received = try await service.receiveWireText("这只是普通文字")
        XCTAssertNil(received)
        XCTAssertTrue(store.latestPerPeer().isEmpty)
    }

    // ⑧ R2:receiveWireText 也过 WireLocator.extract——R1 两行分享形态(说明行 + wire)
    // 粘贴进 App 内一样能解开,不只是 Action Extension 那一侧。
    func testReceiveWireTextExtractsWireFromTwoLineShareForm() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "你好")
        let pasted = "🔒 陈仓加密图片 · 24小时内有效 https://site.test/m/AAAAAAAAAAAAAAAAAAAAAA\n\(sealed.wire)"
        let received = try await service.receiveWireText(pasted)
        let msg = try XCTUnwrap(received)
        XCTAssertEqual(msg.body, "你好")
    }

    // ④ 复制 / 分享分开记:只说用户做过的事,分享不会被复制降级。
    func testMarkCopiedFromSealed() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "hello")
        XCTAssertEqual(service.markCopied(messageId: sealed.message.id, peerId: "alice"), .marked)
        XCTAssertEqual(store.messages(for: "alice").first?.status, .copied)
        XCTAssertEqual(service.markCopied(messageId: sealed.message.id, peerId: "alice"), .unchanged)
        XCTAssertEqual(store.messages(for: "alice").first?.status, .copied)
    }

    func testMarkSharedAfterCopiedUpgrades() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "hello")
        service.markCopied(messageId: sealed.message.id, peerId: "alice")
        XCTAssertEqual(service.markShared(messageId: sealed.message.id, peerId: "alice"), .marked)
        XCTAssertEqual(store.messages(for: "alice").first?.status, .shared)
        XCTAssertEqual(try ChatStore(directory: dir).messages(for: "alice").first?.status, .shared)
    }

    func testMarkCopiedAfterSharedKeepsShared() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "hello")
        XCTAssertEqual(service.markShared(messageId: sealed.message.id, peerId: "alice"), .marked)
        XCTAssertEqual(service.markCopied(messageId: sealed.message.id, peerId: "alice"), .unchanged)
        XCTAssertEqual(store.messages(for: "alice").first?.status, .shared)
        XCTAssertEqual(try ChatStore(directory: dir).messages(for: "alice").first?.status, .shared)
    }

    func testMarkSharedTwiceIsUnchanged() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "hello")
        XCTAssertEqual(service.markShared(messageId: sealed.message.id, peerId: "alice"), .marked)
        XCTAssertEqual(service.markShared(messageId: sealed.message.id, peerId: "alice"), .unchanged)
        XCTAssertEqual(store.messages(for: "alice").first?.status, .shared)
    }

    func testMarkOnIncomingIsNotOwned() async throws {
        let sealed = try await service.sendTextSealed(to: "alice", text: "hello")
        XCTAssertEqual(service.markCopied(messageId: sealed.message.id, peerId: "bob"), .notOwned,
                       "别的会话里没有这条")
        XCTAssertEqual(service.markShared(messageId: sealed.message.id, peerId: "bob"), .notOwned)
        let incoming = try service.ingest(peerId: "alice", text: "in", timestamp: Date(), id: "in-x")
        XCTAssertEqual(service.markCopied(messageId: incoming.id, peerId: "alice"), .notOwned)
        XCTAssertEqual(service.markShared(messageId: incoming.id, peerId: "alice"), .notOwned)
        XCTAssertEqual(store.message(id: incoming.id, peerId: "alice")?.status, .received)
    }

    // ⑤ ingest 落 IN/received。
    func testIngestStoresReceived() throws {
        let msg = try service.ingest(peerId: "bob", text: "hi there",
                                     timestamp: Date(timeIntervalSince1970: 100), id: "in-1")
        XCTAssertEqual(msg.direction, .incoming)
        XCTAssertEqual(msg.status, .received)
        XCTAssertEqual(store.messages(for: "bob").first?.id, "in-1")
    }

    // ③ 收件箱媒体引用入库:kind/media 原样落库,扩展给的 id/时间戳不变。
    func testIngestMediaEntryStoresReferences() throws {
        let item = MediaItem(index: 0, kind: .voice, durMs: 3_000, width: 0, height: 0, byteLen: 100,
                             blobSecret: Data(repeating: 1, count: 32), blobId: "b0", state: .pending)
        let msg = try service.ingest(InboxEntry(id: "e1", peerId: "alice", body: "",
                                                timestamp: Date(timeIntervalSince1970: 7), kind: .voice, media: [item]))
        XCTAssertEqual(msg.kind, .voice)
        XCTAssertEqual(store.message(id: "e1", peerId: "alice")?.media, [item])
        XCTAssertEqual(store.message(id: "e1", peerId: "alice")?.direction, .incoming)
    }

    // ④ 解得开但帧不认识 → 占位消息入库(R3)。
    func testReceiveUnsupportedWireStoresPlaceholder() async throws {
        let received = try await service.receiveWireText("🔒FAKEUNSUP:alice")
        let msg = try XCTUnwrap(received)
        XCTAssertEqual(msg.kind, .unsupported)
        XCTAssertEqual(msg.body, "", "占位文案在显示时按语言取,不落库")
        XCTAssertEqual(msg.summary, L10n.threadUnsupportedMessage)
        XCTAssertEqual(store.messages(for: "alice").count, 1)
    }

    // ⑥ 幂等 ingest:同一个 entry(同 id)落两次库只产生一条消息——收件箱重放安全
    // (`MixinAppModel.handleBecameActive` 失败重试靠的就是这条)。
    func testIngestSameEntryTwiceProducesOneMessage() throws {
        let entry = InboxEntry(id: "dup-1", peerId: "alice", body: "hi", timestamp: Date(timeIntervalSince1970: 1))
        try service.ingest(entry)
        try service.ingest(entry)
        XCTAssertEqual(store.messages(for: "alice").count, 1)
    }

    // ⑦ 镜像 `MixinAppModel.handleBecameActive` 的「读全部 → 逐条 ingest → 只删成功
    // id」重试安全模式(该编排本体在 ChencangCompanion app target,没有测试 target,
    // 这里在 ChatService + AppGroupInbox 这一层直接验证同一套逻辑):落库失败的条目
    // 原样留在收件箱里,成功的被删。
    func testFailedIngestLeavesEntryInInboxWhileSucceededOnesAreRemoved() throws {
        // "bob.json" 提前占成一个目录,让 ChatStore 对 peer "bob" 的 persist(写文件)
        // 必然失败,同时不影响 "alice" 那一份。
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("bob.json"),
                                                 withIntermediateDirectories: true)

        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let okEntry = InboxEntry(id: "ok-1", peerId: "alice", body: "hi", timestamp: Date(timeIntervalSince1970: 1))
        let failEntry = InboxEntry(id: "fail-1", peerId: "bob", body: "hey", timestamp: Date(timeIntervalSince1970: 2))
        try inbox.append(okEntry)
        try inbox.append(failEntry)

        var handled: Set<String> = []
        for entry in inbox.readAll() {
            do {
                try service.ingest(entry)
                handled.insert(entry.id)
            } catch {
                // 落库失败,留给下次回前台重试——不加入 handled。
            }
        }
        try inbox.remove(ids: handled)

        XCTAssertEqual(store.messages(for: "alice").count, 1)
        XCTAssertTrue(store.messages(for: "bob").isEmpty)
        XCTAssertEqual(inbox.readAll().map(\.id), ["fail-1"])
    }

    /// 收到并真正落库一条 incoming 才通知(同 id 重放不通知)。
    func testIngestIncomingInvokesOnIncomingStoredOnce() throws {
        var stored: [String] = []
        let svc = ChatService(
            store: store, crypto: EchoThreadCrypto(), contactIds: { ["alice"] },
            onIncomingStored: { stored.append($0) }
        )
        try svc.ingest(peerId: "alice", text: "hi", timestamp: Date(timeIntervalSince1970: 1), id: "m-1")
        try svc.ingest(peerId: "alice", text: "hi", timestamp: Date(timeIntervalSince1970: 1), id: "m-1")
        XCTAssertEqual(stored, ["alice"])
    }
}
