import XCTest
@testable import ChencangShared

@MainActor
final class ChatMessageModelTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-model-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func item(_ index: Int, _ state: MediaItem.State, kind: MediaKind = .image) -> MediaItem {
        MediaItem(index: index, kind: kind, durMs: 0, width: 640, height: 480, byteLen: 100,
                  blobSecret: Data(repeating: UInt8(index + 1), count: 32), blobId: "blob\(index)", state: state)
    }

    func testLegacyJSONWithoutKindLoadsAsText() throws {
        let legacy = #"[{"body":"老消息","direction":"out","id":"1","peerId":"alice","status":"sealed","timestamp":1000}]"#
        try Data(legacy.utf8).write(to: dir.appendingPathComponent("alice.json"))

        let store = try ChatStore(directory: dir)
        let msg = try XCTUnwrap(store.messages(for: "alice").first)
        XCTAssertEqual(msg.kind, .text)
        XCTAssertNil(msg.media)
        XCTAssertEqual(msg.body, "老消息")
    }

    /// 文字 wire 字段:旧 JSON 没有 → nil 照常可读;新写入的随重载保留。
    func testTextWireRoundTripsAndLegacyHasNone() throws {
        let legacy = #"[{"body":"老消息","direction":"out","id":"1","peerId":"alice","status":"sealed","timestamp":1000}]"#
        try Data(legacy.utf8).write(to: dir.appendingPathComponent("alice.json"))
        let store = try ChatStore(directory: dir)
        XCTAssertNil(try XCTUnwrap(store.messages(for: "alice").first).wire)

        let message = ChatMessage(id: "2", peerId: "alice", direction: .outgoing, body: "新消息",
                                  timestamp: Date(timeIntervalSince1970: 2000), status: .sealed, wire: "🔒WIRE")
        try store.append(message)
        try store.setStatus(id: "2", peerId: "alice", .copied)
        let reloaded = try ChatStore(directory: dir)
        let got = try XCTUnwrap(reloaded.message(id: "2", peerId: "alice"))
        XCTAssertEqual(got.wire, "🔒WIRE")
        XCTAssertEqual(got.status, .copied)
        XCTAssertNil(reloaded.message(id: "1", peerId: "alice")?.wire)
    }

    // MARK: - 状态拆分(已加密/已复制/已分享/收到)与旧 "sent" 迁移

    private func decode(_ json: String) throws -> ChatMessage {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return try d.decode(ChatMessage.self, from: Data(json.utf8))
    }

    func testLegacyOutgoingSentDecodesAsShared() throws {
        let json = #"{"id":"m","peerId":"p","direction":"out","body":"hi","timestamp":1,"status":"sent"}"#
        XCTAssertEqual(try decode(json).status, .shared)
    }

    func testLegacyIncomingSentDecodesAsReceived() throws {
        let json = #"{"id":"m","peerId":"p","direction":"in","body":"hi","timestamp":1,"status":"sent"}"#
        XCTAssertEqual(try decode(json).status, .received)
    }

    func testUnknownStatusDoesNotDropMessage() throws {
        let out = #"{"id":"m","peerId":"p","direction":"out","body":"hi","timestamp":1,"status":"future"}"#
        let inc = #"{"id":"m","peerId":"p","direction":"in","body":"hi","timestamp":1,"status":"future"}"#
        XCTAssertEqual(try decode(out).status, .sealed)
        XCTAssertEqual(try decode(inc).status, .received)

        // 线程文件里一条未知状态不让它被 LossyArray 丢掉。
        let file = #"[{"body":"a","direction":"out","id":"1","peerId":"alice","status":"future","timestamp":1000},{"body":"b","direction":"in","id":"2","peerId":"alice","status":"sent","timestamp":2000}]"#
        try Data(file.utf8).write(to: dir.appendingPathComponent("alice.json"))
        let store = try ChatStore(directory: dir)
        XCTAssertEqual(store.messages(for: "alice").map(\.status), [.sealed, .received])
    }

    func testNewStatusesRoundTrip() throws {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        let cases: [(ChatMessage.Direction, ChatMessage.Status, String)] = [
            (.outgoing, .sealed, "sealed"), (.outgoing, .copied, "copied"),
            (.outgoing, .shared, "shared"), (.incoming, .received, "received"),
        ]
        for (direction, status, raw) in cases {
            let m = ChatMessage(id: "m", peerId: "p", direction: direction, body: "x",
                                timestamp: Date(timeIntervalSince1970: 1), status: status)
            let json = try XCTUnwrap(String(data: try e.encode(m), encoding: .utf8))
            XCTAssertTrue(json.contains("\"status\":\"\(raw)\""), json)
            XCTAssertEqual(try decode(json).status, status)
        }
    }

    func testUnknownKindStringDecodesAsUnsupported() throws {
        let json = #"[{"body":"","direction":"in","id":"1","kind":"sticker","peerId":"alice","status":"sent","timestamp":1000}]"#
        try Data(json.utf8).write(to: dir.appendingPathComponent("alice.json"))
        let store = try ChatStore(directory: dir)
        XCTAssertEqual(store.messages(for: "alice").first?.kind, .unsupported)
    }

    func testMediaMessageRoundTripsThroughReload() throws {
        let store = try ChatStore(directory: dir)
        let message = ChatMessage(id: "m1", peerId: "alice", direction: .incoming, body: "",
                                  timestamp: Date(timeIntervalSince1970: 50), status: .received,
                                  kind: .image, media: [item(0, .pending), item(1, .ready)])
        try store.append(message)

        let reloaded = try ChatStore(directory: dir)
        XCTAssertEqual(reloaded.messages(for: "alice"), [message])
    }

    /// 上传失败原因(审查 M7)是追加字段:旧记录没有它读成 nil,不让整条解码失败;认不出的值(降级 / 将来新增)
    /// 读成 `.rejected`——永久失败「文件无法发送」,与 Android `fromStored` 同口径(UAT N2)。
    func testMediaItemUploadFailureIsAdditive() throws {
        var tooLarge = item(0, .failed)
        tooLarge.uploadFailure = .tooLarge
        let roundTrip = try JSONDecoder().decode(MediaItem.self, from: JSONEncoder().encode(tooLarge))
        XCTAssertEqual(roundTrip.uploadFailure, .tooLarge)
        var rejected = item(0, .failed)
        rejected.uploadFailure = .rejected
        XCTAssertEqual(try JSONDecoder().decode(MediaItem.self, from: JSONEncoder().encode(rejected)).uploadFailure,
                       .rejected)

        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(item(0, .failed)))
                                   as? [String: Any])
        object["uploadFailure"] = nil
        let legacy = try JSONSerialization.data(withJSONObject: object)
        XCTAssertNil(try JSONDecoder().decode(MediaItem.self, from: legacy).uploadFailure)

        object["uploadFailure"] = "quotaExceeded"
        let future = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(MediaItem.self, from: future)
        XCTAssertEqual(decoded.uploadFailure, .rejected)
        XCTAssertEqual(decoded.state, .failed)
        XCTAssertEqual(outgoingMediaStatus(items: [decoded], shared: true, ccaExists: { _ in true }),
                       .permanentlyFailed(.rejected))
        XCTAssertEqual(MediaLayout.outgoingStatusText(.permanentlyFailed(.rejected)), L10n.mediaFailureRejected)
    }

    func testLegacyInboxEntryDecodesAsText() throws {
        let defaults = FakeAppGroupDefaults()
        let legacy = #"[{"body":"你好","id":"e1","peerId":"alice","timestamp":1000}]"#
        defaults.set(Data(legacy.utf8), forKey: AppGroupInbox.key)

        let drained = AppGroupInbox(defaults: defaults).drain()
        XCTAssertEqual(drained, [InboxEntry(id: "e1", peerId: "alice", body: "你好",
                                            timestamp: Date(timeIntervalSince1970: 1))])
        XCTAssertEqual(drained.first?.kind, .text)
    }

    func testInboxEntryWithMediaRoundTrips() throws {
        let inbox = AppGroupInbox(defaults: FakeAppGroupDefaults())
        let entry = InboxEntry(id: "e1", peerId: "alice", body: "", timestamp: Date(timeIntervalSince1970: 1),
                               kind: .voice, media: [item(0, .pending, kind: .voice)])
        try inbox.append(entry)
        XCTAssertEqual(inbox.drain(), [entry])
    }

    func testUpdateMediaItemPersists() throws {
        let store = try ChatStore(directory: dir)
        try store.append(ChatMessage(id: "m1", peerId: "alice", direction: .incoming, body: "",
                                     timestamp: Date(timeIntervalSince1970: 50), status: .received,
                                     kind: .voice, media: [item(0, .pending, kind: .voice)]))
        try store.updateMediaItem(messageId: "m1", peerId: "alice", index: 0) {
            $0.state = .ready
            $0.played = true
        }
        let reloaded = try ChatStore(directory: dir)
        let got = try XCTUnwrap(reloaded.message(id: "m1", peerId: "alice")?.media?.first)
        XCTAssertEqual(got.state, .ready)
        XCTAssertTrue(got.played)
    }

    func testSetBodyIsPartialAndMessageLookup() throws {
        let store = try ChatStore(directory: dir)
        try store.append(ChatMessage(id: "m1", peerId: "alice", direction: .outgoing, body: "",
                                     timestamp: Date(timeIntervalSince1970: 50), status: .sealed,
                                     kind: .image, media: [item(0, .uploading)]))
        try store.setStatus(id: "m1", peerId: "alice", .shared)
        try store.setBody(messageId: "m1", peerId: "alice", body: "🔒 两行文本")
        let got = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        XCTAssertEqual(got.body, "🔒 两行文本")
        XCTAssertEqual(got.status, .shared, "局部更新不得覆盖别处改过的字段")
        XCTAssertEqual(got.media?.first?.state, .uploading)
        XCTAssertNil(store.message(id: "nope", peerId: "alice"))
    }

    func testPartialUpdatesOnMissingMessageDoNotResurrectIt() throws {
        let store = try ChatStore(directory: dir)
        try store.append(ChatMessage(id: "m1", peerId: "alice", direction: .outgoing, body: "",
                                     timestamp: Date(timeIntervalSince1970: 50), status: .sealed,
                                     kind: .image, media: [item(0, .uploading)]))
        try store.remove(id: "m1", peerId: "alice")

        try store.setBody(messageId: "m1", peerId: "alice", body: "x")
        try store.updateMediaItem(messageId: "m1", peerId: "alice", index: 0) { $0.state = .sealed }

        XCTAssertNil(store.message(id: "m1", peerId: "alice"))
        XCTAssertTrue(try ChatStore(directory: dir).messages(for: "alice").isEmpty)
    }

    func testOneBadRecordDoesNotWipeTheThread() throws {
        // 第 2 条消息 JSON 损坏(缺 body);第 3 条里有一个未知 kind 的媒体条目、一个未知 state、一个缺 played
        let json = #"""
        [{"body":"好","direction":"in","id":"1","peerId":"alice","status":"sent","timestamp":1000},
         {"direction":"in","id":"2","peerId":"alice","status":"sent","timestamp":2000},
         {"body":"","direction":"in","id":"3","kind":"image","peerId":"alice","status":"sent","timestamp":3000,
          "media":[{"index":0,"kind":2,"durMs":0,"width":1,"height":1,"byteLen":9,"blobSecret":"AAAA","blobId":"b0","state":"hologram"},
                   {"index":1,"kind":9,"durMs":0,"width":1,"height":1,"byteLen":9,"blobSecret":"AAAA","blobId":"b1","state":"pending","played":false}]}]
        """#
        try Data(json.utf8).write(to: dir.appendingPathComponent("alice.json"))

        let store = try ChatStore(directory: dir)

        XCTAssertEqual(store.messages(for: "alice").map(\.id), ["1", "3"])
        let media = try XCTUnwrap(store.message(id: "3", peerId: "alice")?.media)
        XCTAssertEqual(media.count, 1, "未知 kind 的条目单独丢弃")
        XCTAssertEqual(media[0].state, .failed, "未知 state 读成 failed")
        XCTAssertFalse(media[0].played, "缺 played 读成 false")
    }

    func testOneBadInboxEntryDoesNotDropTheOthers() throws {
        let defaults = FakeAppGroupDefaults()
        let json = #"[{"body":"一","id":"e1","peerId":"alice","timestamp":1000},{"id":"broken"},{"body":"三","id":"e3","peerId":"alice","timestamp":3000}]"#
        defaults.set(Data(json.utf8), forKey: AppGroupInbox.key)

        XCTAssertEqual(AppGroupInbox(defaults: defaults).drain().map(\.id), ["e1", "e3"])
    }

    func testResetInterruptedTransfers() throws {
        let store = try ChatStore(directory: dir)
        try store.append(ChatMessage(id: "out", peerId: "alice", direction: .outgoing, body: "",
                                     timestamp: Date(timeIntervalSince1970: 50), status: .sealed,
                                     kind: .image, media: [item(0, .encrypting), item(1, .uploading), item(2, .sealed)]))
        try store.append(ChatMessage(id: "in", peerId: "alice", direction: .incoming, body: "",
                                     timestamp: Date(timeIntervalSince1970: 60), status: .received,
                                     kind: .image, media: [item(0, .downloading), item(1, .ready)]))

        try store.resetInterruptedTransfers()

        let reloaded = try ChatStore(directory: dir)
        XCTAssertEqual(reloaded.message(id: "out", peerId: "alice")?.media?.map(\.state), [.failed, .failed, .sealed])
        XCTAssertEqual(reloaded.message(id: "in", peerId: "alice")?.media?.map(\.state), [.pending, .ready])
    }

    func testSummaryAndDisplayLabel() {
        func make(_ kind: MessageKind, body: String = "") -> ChatMessage {
            ChatMessage(id: "1", peerId: "a", direction: .incoming, body: body,
                        timestamp: Date(), status: .received, kind: kind)
        }
        XCTAssertEqual(make(.text, body: "hi").summary, "hi")
        XCTAssertEqual(make(.voice).summary, L10n.mediaPreviewVoice)
        XCTAssertEqual(make(.image).summary, L10n.mediaPreviewImage)
        XCTAssertEqual(make(.video).summary, L10n.mediaPreviewVideo)
        XCTAssertEqual(make(.unsupported).summary, L10n.threadUnsupportedMessage)
        XCTAssertEqual(MessageKind.voice.displayLabel(count: 1), L10n.mediaKindVoice)
        XCTAssertEqual(MessageKind.video.displayLabel(count: 1), L10n.mediaKindVideo)
        XCTAssertEqual(MessageKind.text.displayLabel(count: 1), L10n.mediaKindMessage)
        XCTAssertEqual(MessageKind.unsupported.displayLabel(count: 1), L10n.mediaKindMessage)
    }

    func testMultiImageSummaryIsPlural() {
        let items = (0..<3).map {
            MediaItem(index: $0, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 1,
                      blobSecret: Data(), blobId: "b\($0)", state: .ready)
        }
        let msg = ChatMessage(id: "1", peerId: "a", direction: .incoming, body: "", timestamp: Date(),
                              status: .received, kind: .image, media: items)
        XCTAssertEqual(msg.summary, L10n.mediaPreviewImages(3))
    }

    func testDisplayLabelPluralisesImages() {
        XCTAssertEqual(MessageKind.image.displayLabel(count: 1), L10n.mediaKindImage)
        XCTAssertEqual(MessageKind.image.displayLabel(count: 3), L10n.mediaPhotoCount(3))
    }

    // 老数据里占位正文是写死的中文;显示时一律按当前语言取占位,不读 body。
    func testUnsupportedSummaryIsLocalizedRegardlessOfStoredBody() {
        let legacy = ChatMessage(id: "1", peerId: "a", direction: .incoming, body: "这是一条新类型消息，请升级陈仓",
                                 timestamp: Date(), status: .received, kind: .unsupported)
        XCTAssertEqual(legacy.summary, L10n.threadUnsupportedMessage)
    }

    func testOutgoingMediaNeedsRetry() {
        func out(_ states: [MediaItem.State], body: String = "") -> ChatMessage {
            ChatMessage(id: "1", peerId: "a", direction: .outgoing, body: body, timestamp: Date(), status: .sealed,
                        kind: .image, media: states.enumerated().map { item($0.offset, $0.element) })
        }
        XCTAssertTrue(out([.sealed, .failed]).outgoingMediaNeedsRetry)
        XCTAssertTrue(out([.sealed, .sealed]).outgoingMediaNeedsRetry, "全部上传成功但封帧失败，也要能重试")
        XCTAssertFalse(out([.sealed, .uploading]).outgoingMediaNeedsRetry)
        XCTAssertFalse(out([.sealed], body: "🔒 …").outgoingMediaNeedsRetry)
    }
}
