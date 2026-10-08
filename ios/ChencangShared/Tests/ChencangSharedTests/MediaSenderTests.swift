import XCTest
import Chencang
@testable import ChencangShared

@MainActor
final class MediaSenderTests: XCTestCase {
    private var base: URL!
    private var store: ChatStore!
    private var files: MediaFiles!
    private var activity: MediaActivity!
    private var transport: FakeMediaTransport!
    private var mediaCrypto: CountingMediaCrypto!
    private var sender: MediaSender!
    private var idCounter = 0
    /// 注入的上传调度器收到的 messageId(按调用顺序)。
    private var scheduled: [String] = []

    override func setUp() async throws {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-sender-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: base.appendingPathComponent("chat"))
        files = MediaFiles(root: base.appendingPathComponent("media"))
        activity = MediaActivity()
        transport = FakeMediaTransport()
        mediaCrypto = CountingMediaCrypto()
        idCounter = 0
        scheduled = []
        sender = makeSender()
    }

    private func makeSender(crypto: ThreadCrypto = EchoThreadCrypto(), files: MediaFiles? = nil) -> MediaSender {
        MediaSender(store: store, crypto: crypto, mediaCrypto: mediaCrypto,
                    transport: transport, files: files ?? self.files, activity: activity,
                    uploadScheduler: { [unowned self] id in self.scheduled.append(id) },
                    now: { Date(timeIntervalSince1970: 1_000) },
                    newId: { [unowned self] in self.idCounter += 1; return "m\(self.idCounter)" },
                    site: { "https://site.test/" })
    }

    private var chatDir: URL { base.appendingPathComponent("chat") }

    /// 把消息存储目录设成只读,模拟磁盘写不进去。
    private func setChatStoreReadOnly(_ readOnly: Bool) throws {
        try FileManager.default.setAttributes([.posixPermissions: readOnly ? 0o500 : 0o755], ofItemAtPath: chatDir.path)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: base)
    }

    private func image(_ fill: UInt8 = 0xAB, size: Int = 5_000) -> PreparedMedia {
        PreparedMedia(kind: .image, plaintext: Data(repeating: fill, count: size), durMs: 0, width: 1920, height: 1080)
    }

    func testSendSingleImageUploadsSealsAndComposesShareText() async throws {
        let prepared = image()
        let outcome = await sender.send(to: "alice", media: [prepared])
        let r1 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r1, .done)

        let msg = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        let item = try XCTUnwrap(msg.media?.first)
        let expected = L10n.cardShareHeaderMedia(L10n.mediaKindImage, link: "https://site.test/m/\(item.blobId)")
            + "\n🔒FAKEMEDIA:alice:2:1"
        XCTAssertEqual(outcome, .sealed(messageId: "m1", shareText: expected))
        XCTAssertEqual(msg.body, expected)
        XCTAssertEqual(msg.kind, .image)
        XCTAssertEqual(msg.direction, .outgoing)
        XCTAssertEqual(msg.status, .sealed)
        XCTAssertEqual(item.state, .sealed)
        XCTAssertEqual(item.blobId.count, 22)
        XCTAssertEqual(item.width, 1920)

        XCTAssertEqual(transport.requested.count, 1)
        XCTAssertEqual(transport.requested[0].blobId, item.blobId)
        XCTAssertEqual(transport.requested[0].byteLen, item.byteLen)
        XCTAssertEqual(transport.requested[0].kind, .image)
        // 上传的正是能用帧里那把密钥解开的密文
        XCTAssertEqual(try decryptMediaBlob(blob: transport.uploads[0].body, blobSecret: item.blobSecret, expectedKind: 2),
                       prepared.plaintext)
        XCTAssertEqual(transport.uploads[0].body.count, item.byteLen)
        // 发送方留明文、删密文(R5)
        XCTAssertEqual(try Data(contentsOf: files.binURL(messageId: "m1", index: 0)), prepared.plaintext)
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))
    }

    func testMultiImageSealsOneFrameLabelledWithCountAndFirstBlobId() async throws {
        let outcome = await sender.send(to: "alice", media: [image(1), image(2), image(3)])
        let r2 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r2, .done)

        let items = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media)
        XCTAssertEqual(items.map(\.state), [.sealed, .sealed, .sealed])
        XCTAssertEqual(transport.uploads.map(\.blobId), items.map(\.blobId))
        XCTAssertEqual(Set(items.map(\.blobSecret)).count, 3, "每条媒体独立密钥")
        guard case let .sealed(_, text) = outcome else { return XCTFail("应封缄成功") }
        XCTAssertEqual(text, L10n.cardShareHeaderMedia(L10n.mediaPhotoCount(3), link: "https://site.test/m/\(items[0].blobId)")
                       + "\n🔒FAKEMEDIA:alice:2:3")
    }

    func testShareTextLabelsForVoiceAndVideo() {
        let voice = MediaItem(index: 0, kind: .voice, durMs: 3_000, width: 0, height: 0, byteLen: 1,
                              blobSecret: Data(), blobId: "VVVVVVVVVVVVVVVVVVVVVV", state: .sealed)
        XCTAssertEqual(MediaShareText.compose(kind: .voice, items: [voice], wire: "🔒W", site: "https://site.test/"),
                       L10n.cardShareHeaderMedia(L10n.mediaKindVoice, link: "https://site.test/m/VVVVVVVVVVVVVVVVVVVVVV") + "\n🔒W")
        XCTAssertEqual(MediaShareText.compose(kind: .video, items: [voice], wire: "🔒W", site: "https://site.test/"),
                       L10n.cardShareHeaderMedia(L10n.mediaKindVideo, link: "https://site.test/m/VVVVVVVVVVVVVVVVVVVVVV") + "\n🔒W")
    }

    func testFailedUploadRetriesSameCiphertextWithoutReencrypting() async throws {
        transport.failNextUploads([.network])
        guard case let .sealed(_, text) = await sender.send(to: "alice", media: [image()]) else { return XCTFail() }

        let first = await sender.upload(messageId: "m1")

        XCTAssertEqual(first, .retryable(.network))
        let pending = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        XCTAssertEqual(pending.media?.first?.state, .uploading)
        XCTAssertEqual(pending.body, text)
        let cca = files.ccaURL(messageId: "m1", index: 0)
        let ccaBytes = try Data(contentsOf: cca)
        XCTAssertNil(activity.notice, "断网自动重传,不弹提示")

        let second = await sender.upload(messageId: "m1")

        XCTAssertEqual(second, .done)
        XCTAssertEqual(mediaCrypto.sealCount, 1, "重传不得重新调用 encryptMediaBlob")
        XCTAssertEqual(transport.uploads.count, 1)
        XCTAssertEqual(transport.uploads[0].body, ccaBytes)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.blobSecret, pending.media?.first?.blobSecret)
        XCTAssertFalse(files.exists(cca))
    }

    // MARK: - 终审 fix round 1(加密阶段 = 分享之前)

    /// I1:加密阶段某一条失败时,其余还没完成的条目不能停在 encrypting——一起标 failed,
    /// 气泡才会显示红 !、可点重试;没有分享文本,也不交给上传。
    func testEncryptionFailureMarksAllUnsharedItemsFailed() async throws {
        mediaCrypto.failSeal(onCall: 2)

        let outcome = await sender.send(to: "alice", media: [image(1), image(2), image(3)])

        XCTAssertEqual(outcome, .failed(messageId: "m1"))
        let msg = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        XCTAssertEqual(msg.media?.map(\.state), [.failed, .failed, .failed])
        XCTAssertEqual(msg.body, "")
        XCTAssertTrue(msg.outgoingMediaNeedsRetry)
        XCTAssertEqual(scheduled, [], "没分享就不上传")
        XCTAssertTrue(transport.requested.isEmpty)
    }

    /// I2:分享之前出现「有 secret、`.cca` 却不在了」的畸形态——这时还没人拿到过密钥,
    /// 重试可以重新加密,但新 secret 绝不能和旧的一样(nonce 安全)。
    func testRetryBeforeShareWithSecretButMissingCcaReencryptsWithFreshSecret() async throws {
        mediaCrypto.failSeal(onCall: 2)
        _ = await sender.send(to: "alice", media: [image(1), image(2)])
        let stuck = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media?.first)
        XCTAssertFalse(stuck.blobSecret.isEmpty)
        let cca = files.ccaURL(messageId: "m1", index: 0)
        XCTAssertTrue(files.exists(cca))
        files.remove(cca)

        let outcome = await sender.retry(messageId: "m1", peerId: "alice")

        guard case .sealed = outcome else { return XCTFail("应重新加密后封缄成功") }
        XCTAssertEqual(mediaCrypto.sealCount, 4, "首次两次 + 重试两条各一次")
        let after = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media?.first)
        XCTAssertNotEqual(after.blobSecret, stuck.blobSecret, "补救绝不能复用旧 secret")
        XCTAssertTrue(files.exists(cca))
    }

    /// I3:落库本身失败(这里用只读目录模拟磁盘写不进去)时,旧代码 `try?` 吞掉错误、假装
    /// 落库成功,还会接着往下跑加密/上传。修完之后落库失败必须直接短路成 `.failed`。
    func testPersistFailureDuringItemSaveFailsWithoutAttemptingEncryptionOrUpload() async throws {
        let prepared = image()
        let items = [MediaItem(index: 0, kind: prepared.kind, durMs: prepared.durMs, width: prepared.width,
                               height: prepared.height, byteLen: 0, blobSecret: Data(), blobId: "", state: .encrypting)]
        let message = ChatMessage(id: "m1", peerId: "alice", direction: .outgoing, body: "",
                                  timestamp: Date(timeIntervalSince1970: 1_000), status: .sealed, kind: .image, media: items)
        try store.append(message)
        try files.write(prepared.plaintext, to: files.binURL(messageId: "m1", index: 0))

        let chatDir = base.appendingPathComponent("chat")
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: chatDir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: chatDir.path) }

        let outcome = await sender.retry(messageId: "m1", peerId: "alice")

        XCTAssertEqual(outcome, .failed(messageId: "m1"))
        XCTAssertEqual(mediaCrypto.sealCount, 0, "落库失败必须在加密之前拦下")
        XCTAssertTrue(transport.requested.isEmpty)
        XCTAssertTrue(transport.uploads.isEmpty)
        XCTAssertEqual(scheduled, [])
    }

    /// M3:选择器不会产出混 kind 的一批,这里只是兜底这条不变量,绝不能真落库一条坏消息。
    func testMixedKindMediaIsRefusedBeforeAnyPersistence() async throws {
        let voice = PreparedMedia(kind: .voice, plaintext: Data([1, 2, 3]), durMs: 1_000, width: 0, height: 0)

        let outcome = await sender.send(to: "alice", media: [image(), voice])

        XCTAssertEqual(outcome, .failed(messageId: "m1"))
        XCTAssertNil(store.message(id: "m1", peerId: "alice"), "类型不一致的一批不应该落库")
        XCTAssertNotNil(activity.notice)
        XCTAssertEqual(mediaCrypto.sealCount, 0)
    }

    /// M5:`SealedMediaBlob` 打印出来绝不能带出 secret——只有 blobId(本来就是公开的落地页
    /// 参数)和长度信息可以见光。
    func testSealedMediaBlobDescriptionRedactsSecret() {
        let secret = Data(repeating: 0x5A, count: 32)
        let secretHex = secret.map { String(format: "%02x", $0) }.joined()
        let blob = SealedMediaBlob(secret: secret, blob: Data([9, 9, 9]), blobId: "blobIdForRedactionTest")

        XCTAssertFalse("\(blob)".contains(secretHex))
        XCTAssertFalse(blob.debugDescription.contains(secretHex))
        XCTAssertTrue(blob.description.contains("blobIdForRedactionTest"))
    }

    /// 限流是暂时的:上传引擎自己退避重传,不弹提示、项留在 uploading。
    func testUploadRateLimitIsRetryableWithoutNotice() async throws {
        transport.failNextUploads([.rateLimited])
        _ = await sender.send(to: "alice", media: [image()])
        let r3 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r3, .retryable(.rateLimited))
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .uploading)
        XCTAssertNil(activity.notice)
    }

    func testUploadTooLargeIsPermanentAndPostsNotice() async throws {
        transport.failNextUploads([.tooLarge])
        _ = await sender.send(to: "alice", media: [image()])
        let r4 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r4, .permanent(.tooLarge))
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .failed)
        XCTAssertEqual(activity.notice, L10n.mediaFailureTooLarge)
    }

    func testUploadOfFullyUploadedMessageIsNoop() async throws {
        _ = await sender.send(to: "alice", media: [image()])
        let r5 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r5, .done)
        let r6 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r6, .done)
        XCTAssertEqual(transport.uploads.count, 1)
        XCTAssertEqual(transport.requested.count, 1)
    }

    func testForwardReencryptsFromPlaintextForAnotherPeer() async throws {
        _ = await sender.send(to: "alice", media: [image()])
        let original = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media?.first)

        let outcome = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: [0], toPeer: "bob")

        guard case let .sealed(newId, text)? = outcome else { return XCTFail("转发应成功") }
        XCTAssertEqual(newId, "m2")
        XCTAssertTrue(text.hasSuffix("\n🔒FAKEMEDIA:bob:2:1"))
        let forwarded = try XCTUnwrap(store.message(id: "m2", peerId: "bob")?.media?.first)
        XCTAssertNotEqual(forwarded.blobSecret, original.blobSecret, "转发必须是新 blob_secret")
        XCTAssertEqual(mediaCrypto.sealCount, 2)
        XCTAssertEqual(scheduled, ["m1", "m2"], "转发走同一 seal + 上传引擎")
        XCTAssertTrue(transport.uploads.isEmpty, "seal 阶段零网络")
    }

    private func album() -> [PreparedMedia] {
        (0..<3).map { PreparedMedia(kind: .image, plaintext: Data(repeating: UInt8($0 + 1), count: 8), durMs: 0, width: 4, height: 4) }
    }

    func testForwardSingleIndexOnlyCarriesThatItem() async throws {
        _ = await sender.send(to: "alice", media: album())
        let outcome = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: [2, 2], toPeer: "bob")
        guard case let .sealed(newId, _)? = outcome else { return XCTFail("转发应成功") }
        let media = try XCTUnwrap(store.message(id: newId, peerId: "bob")?.media)
        XCTAssertEqual(media.count, 1)
        let plain = try Data(contentsOf: files.binURL(messageId: newId, index: 0))
        XCTAssertEqual(plain, Data(repeating: 3, count: 8), "应是原第 2 张的内容")
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.count, 3, "原消息不受影响")
    }

    func testForwardNilIndicesCarriesAll() async throws {
        _ = await sender.send(to: "alice", media: album())
        let outcome = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: nil, toPeer: "bob")
        guard case let .sealed(newId, _)? = outcome else { return XCTFail("转发应成功") }
        XCTAssertEqual(store.message(id: newId, peerId: "bob")?.media?.count, 3)
    }

    func testForwardEmptyIndicesThrows() async {
        _ = await sender.send(to: "alice", media: album())
        do {
            _ = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: [], toPeer: "bob")
            XCTFail("空下标应抛错")
        } catch {
            XCTAssertEqual(error as? MediaSender.ForwardError, .indexOutOfRange)
        }
        XCTAssertNil(store.message(id: "m2", peerId: "bob"))
    }

    func testForwardIndicesAreAscendingAndDeduped() async throws {
        _ = await sender.send(to: "alice", media: album())
        guard case let .sealed(id1, _)? = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: [2, 0], toPeer: "bob")
        else { return XCTFail() }
        XCTAssertEqual(try Data(contentsOf: files.binURL(messageId: id1, index: 0)), Data(repeating: 1, count: 8))
        XCTAssertEqual(try Data(contentsOf: files.binURL(messageId: id1, index: 1)), Data(repeating: 3, count: 8))
        XCTAssertEqual(store.message(id: id1, peerId: "bob")?.media?.count, 2)
        guard case let .sealed(id2, _)? = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: [1, 1], toPeer: "bob")
        else { return XCTFail() }
        XCTAssertEqual(store.message(id: id2, peerId: "bob")?.media?.count, 1)
    }

    func testForwardOutOfRangeThrowsAndSendsNothing() async {
        _ = await sender.send(to: "alice", media: album())
        let uploads = transport.uploads.count
        for bad in [[3], [-1], [0, 5]] {
            do {
                _ = try await sender.forward(messageId: "m1", fromPeer: "alice", indices: bad, toPeer: "bob")
                XCTFail("越界应抛错 \(bad)")
            } catch {
                XCTAssertEqual(error as? MediaSender.ForwardError, .indexOutOfRange)
            }
        }
        XCTAssertEqual(transport.uploads.count, uploads)
        XCTAssertEqual(scheduled, ["m1"])
        XCTAssertNil(store.message(id: "m2", peerId: "bob"))
    }

    func testDeletingMessageDuringUploadKeepsItDeleted() async throws {
        let store = self.store!
        let files = self.files!
        transport.setUploadHook {
            try? await ThreadCleaner(chatStore: store, mediaFiles: files, cancelUploads: { _ in })
                .deleteMessage(id: "m1", peerId: "alice")
        }

        _ = await sender.send(to: "alice", media: [image()])
        let outcome = await sender.upload(messageId: "m1")

        XCTAssertEqual(outcome, .permanent(.deleted))
        XCTAssertNil(store.message(id: "m1", peerId: "alice"))
        XCTAssertTrue(try ChatStore(directory: base.appendingPathComponent("chat")).messages(for: "alice").isEmpty,
                      "删掉的消息不能被写回磁盘")
        XCTAssertFalse(files.exists(files.directory(messageId: "m1")))
    }

    func testConcurrentRetriesBeforeShareRunOnlyOnce() async throws {
        mediaCrypto.failSeal(onCall: 1)
        _ = await sender.send(to: "alice", media: [image()])

        async let first = sender.retry(messageId: "m1", peerId: "alice")
        async let second = sender.retry(messageId: "m1", peerId: "alice")
        let results = await [first, second]

        XCTAssertEqual(mediaCrypto.sealCount, 2, "连点两次重试只加密一次")
        XCTAssertEqual(results.filter { if case .sealed = $0 { return true } else { return false } }.count, 1)
        XCTAssertTrue(results.contains(.inProgress(messageId: "m1")))
        XCTAssertEqual(scheduled, ["m1"])
    }

    func testConcurrentUploadsOfSameMessageUploadOnce() async throws {
        _ = await sender.send(to: "alice", media: [image()])
        transport.uploadDelayNanos = 50_000_000

        async let first = sender.upload(messageId: "m1")
        async let second = sender.upload(messageId: "m1")
        let results = await [first, second]

        XCTAssertEqual(transport.uploads.count, 1)
        XCTAssertTrue(results.contains(.done))
        XCTAssertTrue(results.contains(.retryable(.busy)))
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .sealed)
    }

    func testStatusChangeDuringUploadIsNotOverwritten() async throws {
        let store = self.store!
        transport.setUploadHook {
            await MainActor.run { try? store.setStatus(id: "m1", peerId: "alice", .shared) }
        }
        _ = await sender.send(to: "alice", media: [image()])
        _ = await sender.upload(messageId: "m1")
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.status, .shared, "局部写库不得覆盖并发的状态修改")
    }

    // MARK: - 先分享、后上传(2026-09-30)

    func testSealReturnsShareTextWithoutNetwork() async throws {
        let outcome = await sender.seal(to: "alice", media: [image(1), image(2)])

        guard case let .sealed(id, text) = outcome else { return XCTFail("应封缄成功") }
        XCTAssertEqual(id, "m1")
        XCTAssertTrue(transport.requested.isEmpty, "seal 不得申请上传地址")
        XCTAssertTrue(transport.uploads.isEmpty, "seal 不得上传")
        let msg = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        XCTAssertEqual(msg.body, text)
        XCTAssertFalse(text.isEmpty)
        XCTAssertEqual(msg.media?.map(\.state), [.uploading, .uploading])
        XCTAssertTrue(msg.media?.allSatisfy { !$0.blobSecret.isEmpty && $0.blobId.count == 22 } ?? false)
        XCTAssertTrue(files.exists(files.ccaURL(messageId: "m1", index: 0)))
        XCTAssertTrue(files.exists(files.ccaURL(messageId: "m1", index: 1)))
        XCTAssertEqual(scheduled, ["m1"], "seal 完成后交给上传调度器一次")
        XCTAssertTrue(sender.isShared("m1"))
        XCTAssertFalse(msg.outgoingMediaNeedsRetry)
    }

    func testUploadMarksSealedAndDeletesCca() async throws {
        _ = await sender.seal(to: "alice", media: [image(1), image(2)])

        let r7 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r7, .done)

        let items = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media)
        XCTAssertEqual(items.map(\.state), [.sealed, .sealed])
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 1)))
        XCTAssertEqual(transport.uploads.map(\.blobId), items.map(\.blobId))
        XCTAssertTrue(files.exists(files.binURL(messageId: "m1", index: 0)), "发送方保留明文")
    }

    func testUpload412IsSuccess() async throws {
        _ = await sender.seal(to: "alice", media: [image()])

        let outcome = sender.completeItem(messageId: "m1", index: 0, httpStatus: 412, error: nil)

        XCTAssertEqual(outcome, .done)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .sealed)
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))
    }

    func testCompleteItemStatusMapping() async throws {
        _ = await sender.seal(to: "alice", media: [image()])
        let cca = files.ccaURL(messageId: "m1", index: 0)

        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: nil, error: URLError(.timedOut)),
                       .retryable(.network))
        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: 503, error: nil), .retryable(.server))
        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: 429, error: nil), .retryable(.rateLimited))
        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: 403, error: nil), .retryable(.gone),
                       "403 = presign 过期,需重新 presign")
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .uploading)
        XCTAssertTrue(files.exists(cca), "可重试的失败绝不删 .cca")
        XCTAssertEqual(sender.completeItem(messageId: "nope", index: 0, httpStatus: 200, error: nil), .permanent(.deleted))
        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: 201, error: nil), .done)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .sealed)
    }

    func testPresignedRequestIsIdempotentPut() async throws {
        _ = await sender.seal(to: "alice", media: [image()])
        let item = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media?.first)

        let request = try await sender.presignedRequest(messageId: "m1", index: 0)

        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url, URL(string: "https://upload.test/\(item.blobId)"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
        XCTAssertEqual(request.value(forHTTPHeaderField: "If-None-Match"), "*")
        XCTAssertEqual(transport.requested.count, 1)
        XCTAssertEqual(transport.requested[0].byteLen, item.byteLen)
        _ = try await sender.presignedRequest(messageId: "m1", index: 0)
        XCTAssertEqual(transport.requested.count, 2, "每次尝试都重新 presign")
        XCTAssertEqual(mediaCrypto.sealCount, 1)
    }

    func testUploadNetworkErrorIsRetryableAndKeepsCca() async throws {
        _ = await sender.seal(to: "alice", media: [image()])
        transport.failNextUploads([.network])

        let r8 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r8, .retryable(.network))

        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .uploading)
        XCTAssertTrue(files.exists(files.ccaURL(messageId: "m1", index: 0)))
        XCTAssertEqual(mediaCrypto.sealCount, 1)
    }

    func testUploadMissingCcaIsPermanentAndNeverReencrypts() async throws {
        guard case let .sealed(_, text) = await sender.seal(to: "alice", media: [image()]) else { return XCTFail() }
        let before = try XCTUnwrap(store.message(id: "m1", peerId: "alice")?.media?.first)
        files.remove(files.ccaURL(messageId: "m1", index: 0))

        let r9 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r9, .permanent(.fileMissing))

        let after = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        XCTAssertEqual(after.media?.first?.state, .failed)
        XCTAssertEqual(after.media?.first?.blobSecret, before.blobSecret, "已分享的 secret 冻结")
        XCTAssertEqual(after.body, text)
        XCTAssertEqual(mediaCrypto.sealCount, 1, "已分享绝不重新加密")
        XCTAssertTrue(transport.requested.isEmpty)
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))

        // 用户点「!」:仍然只交给上传引擎,不加密
        _ = await sender.retry(messageId: "m1", peerId: "alice")
        XCTAssertEqual(mediaCrypto.sealCount, 1)
    }

    func testRetryAfterShareOnlyReschedules() async throws {
        transport.failNextUploads([.network])
        guard case let .sealed(_, text) = await sender.seal(to: "alice", media: [image()]) else { return XCTFail() }
        _ = await sender.upload(messageId: "m1")
        try store.updateMediaItem(messageId: "m1", peerId: "alice", index: 0) { $0.state = .failed }
        let secret = store.message(id: "m1", peerId: "alice")?.media?.first?.blobSecret

        let outcome = await sender.retry(messageId: "m1", peerId: "alice")

        XCTAssertEqual(outcome, .sealed(messageId: "m1", shareText: text))
        XCTAssertEqual(scheduled, ["m1", "m1"])
        XCTAssertEqual(mediaCrypto.sealCount, 1)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.body, text)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.blobSecret, secret)
        XCTAssertEqual(transport.requested.count, 1, "retry 本身不联网")

        // 之后上传引擎把 failed 项重新传上去
        let r10 = await sender.upload(messageId: "m1")
        XCTAssertEqual(r10, .done)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .sealed)
    }

    func testRetryBeforeShareReencrypts() async throws {
        mediaCrypto.failSeal(onCall: 1)
        let r11 = await sender.seal(to: "alice", media: [image()])
        XCTAssertEqual(r11, .failed(messageId: "m1"))
        XCTAssertFalse(sender.isShared("m1"))
        XCTAssertEqual(scheduled, [])

        let outcome = await sender.retry(messageId: "m1", peerId: "alice")

        guard case let .sealed(_, text) = outcome else { return XCTFail("应重新加密并封缄") }
        XCTAssertEqual(mediaCrypto.sealCount, 2)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.body, text)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .uploading)
        XCTAssertEqual(scheduled, ["m1"])
        XCTAssertTrue(transport.requested.isEmpty)
    }

    func testCcaFileProtectionIsUntilFirstAuth() async throws {
        let recorder = WriteRecorder()
        let recordingFiles = MediaFiles(root: base.appendingPathComponent("media")) { data, url, options in
            recorder.record(url, options)
            try data.write(to: url, options: options)
        }
        let sender = makeSender(files: recordingFiles)

        guard case .sealed = await sender.seal(to: "alice", media: [image(1), image(2)]) else { return XCTFail() }

        // seal 真正交给系统的保护级别:.cca 锁屏后后台上传仍要能读;明文 .bin 保持原级别
        for index in 0..<2 {
            let cca = try XCTUnwrap(recorder.options(for: recordingFiles.ccaURL(messageId: "m1", index: index)),
                                    "seal 必须经注入的 writer 写 .cca")
            XCTAssertTrue(cca.contains(.completeFileProtectionUntilFirstUserAuthentication))
            XCTAssertFalse(cca.contains(.completeFileProtectionUnlessOpen))
            let bin = try XCTUnwrap(recorder.options(for: recordingFiles.binURL(messageId: "m1", index: index)))
            XCTAssertTrue(bin.contains(.completeFileProtectionUnlessOpen))
        }
        // 设备上还能读到实际属性
        let attrs = try FileManager.default.attributesOfItem(atPath: recordingFiles.ccaURL(messageId: "m1", index: 0).path)
        if let level = attrs[.protectionKey] as? FileProtectionType {
            XCTAssertEqual(level, .completeUntilFirstUserAuthentication)
        }
    }

    // MARK: - Task 5 fix round 1

    /// M1 / M5:加密完成后封帧失败——没有分享文本,项标 failed 给红 !,不交给上传。
    func testSealMediaFailureAfterEncryptionLeavesUnsharedAndRetryable() async throws {
        let sender = makeSender(crypto: HookedThreadCrypto { throw SessionStoreError.noSession("alice") })

        let outcome = await sender.seal(to: "alice", media: [image(1), image(2)])

        XCTAssertEqual(outcome, .failed(messageId: "m1"))
        let msg = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))
        XCTAssertEqual(msg.body, "")
        XCTAssertEqual(msg.media?.map(\.state), [.failed, .failed])
        XCTAssertTrue(msg.outgoingMediaNeedsRetry)
        XCTAssertFalse(sender.isShared("m1"))
        XCTAssertEqual(scheduled, [])
        XCTAssertTrue(transport.requested.isEmpty)
    }

    /// M1:`setBody` 落盘失败——内存里也不能留下分享文本(否则 isShared 为真、之后别的落库
    /// 会把它连带写进磁盘,消息被当成「已分享」而用户从没拿到过文本)。
    func testSetBodyPersistFailureLeavesBodyEmpty() async throws {
        let chatDir = self.chatDir
        let sender = makeSender(crypto: HookedThreadCrypto {
            try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: chatDir.path)
        })
        defer { try? setChatStoreReadOnly(false) }

        let outcome = await sender.seal(to: "alice", media: [image()])

        XCTAssertEqual(outcome, .failed(messageId: "m1"))
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.body, "")
        XCTAssertFalse(sender.isShared("m1"))
        XCTAssertEqual(scheduled, [])

        try setChatStoreReadOnly(false)
        // 之后任何一次落库都不会把分享文本带进磁盘
        try store.updateMediaItem(messageId: "m1", peerId: "alice", index: 0) { $0.state = .failed }
        let reloaded = try ChatStore(directory: chatDir)
        XCTAssertEqual(reloaded.message(id: "m1", peerId: "alice")?.body, "")
        XCTAssertEqual(reloaded.message(id: "m1", peerId: "alice")?.outgoingMediaNeedsRetry, true)

        // 重试(磁盘已恢复、换正常的封帧):还没分享,正常封缄
        guard case .sealed = await self.sender.retry(messageId: "m1", peerId: "alice") else { return XCTFail() }
        XCTAssertTrue(self.sender.isShared("m1"))
        XCTAssertEqual(scheduled, ["m1"])
    }

    /// M5:「已上传」没落成就不能删 `.cca`,并报可重试;磁盘恢复后再完成一次即可。
    func testCompleteItemSaveFailureKeepsCcaAndIsRetryable() async throws {
        _ = await sender.seal(to: "alice", media: [image()])
        let cca = files.ccaURL(messageId: "m1", index: 0)
        try setChatStoreReadOnly(true)
        defer { try? setChatStoreReadOnly(false) }

        let outcome = sender.completeItem(messageId: "m1", index: 0, httpStatus: 200, error: nil)

        XCTAssertEqual(outcome, .retryable(.storage))
        XCTAssertTrue(files.exists(cca), "没落成 sealed 就删 .cca = 已分享的消息再也传不上去")
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .uploading,
                       "内存不能先于磁盘变成 sealed")

        try setChatStoreReadOnly(false)
        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: 412, error: nil), .done)
        XCTAssertFalse(files.exists(cca))
        XCTAssertEqual(try ChatStore(directory: chatDir).message(id: "m1", peerId: "alice")?.media?.first?.state, .sealed)
    }

    /// M2:另一条完成路径已经把这项落成 sealed,迟到的 413 不得把它降成 failed。
    func testLate413DoesNotDemoteSealedItem() async throws {
        _ = await sender.seal(to: "alice", media: [image()])
        XCTAssertEqual(sender.completeItem(messageId: "m1", index: 0, httpStatus: 200, error: nil), .done)

        _ = sender.completeItem(messageId: "m1", index: 0, httpStatus: 413, error: nil)

        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.first?.state, .sealed)
        XCTAssertNil(activity.notice)
    }

    /// M2:upload 等待期间另一条路径传完了后面某项(sealed 并删了 `.cca`)——轮到它时不是「文件丢失」。
    func testItemCompletedElsewhereDuringUploadIsNotMarkedMissing() async throws {
        _ = await sender.seal(to: "alice", media: [image(1), image(2)])
        let sender = self.sender!
        transport.setUploadHook {
            await MainActor.run {
                _ = sender.completeItem(messageId: "m1", index: 1, httpStatus: 200, error: nil)
            }
        }

        let outcome = await sender.upload(messageId: "m1")

        XCTAssertEqual(outcome, .done)
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.map(\.state), [.sealed, .sealed])
    }

    /// 两项的 `.cca` 都丢了:两项都标 failed(不能因为第一项已记下失败就跳过第二项)。
    func testEveryMissingCcaIsMarkedFailed() async throws {
        _ = await sender.seal(to: "alice", media: [image(1), image(2)])
        files.remove(files.ccaURL(messageId: "m1", index: 0))
        files.remove(files.ccaURL(messageId: "m1", index: 1))

        let outcome = await sender.upload(messageId: "m1")

        XCTAssertEqual(outcome, .permanent(.fileMissing))
        XCTAssertEqual(store.message(id: "m1", peerId: "alice")?.media?.map(\.state), [.failed, .failed])
    }

    func testNoticeMapping() {
        XCTAssertEqual(MediaNotice.text(for: MediaTransportError.rateLimited), L10n.mediaFailureRateLimited)
        XCTAssertEqual(MediaNotice.text(for: NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError)),
                       L10n.mediaFailureStorageFull)
        XCTAssertEqual(MediaNotice.text(for: NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))), L10n.mediaFailureStorageFull)
        XCTAssertNil(MediaNotice.text(for: MediaTransportError.network))
        XCTAssertEqual(MediaNotice.text(for: SessionStoreError.noSession("x")), L10n.mediaFailureSessionLost)
    }
}

/// 记录每次写盘传给系统的选项。
private final class WriteRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var writes: [String: Data.WritingOptions] = [:]

    func record(_ url: URL, _ options: Data.WritingOptions) { lock.withLock { writes[url.standardizedFileURL.path] = options } }
    func options(for url: URL) -> Data.WritingOptions? { lock.withLock { writes[url.standardizedFileURL.path] } }
}

/// 在 `sealMedia` 返回 wire 之前先跑一段钩子(可抛错模拟封帧失败,或弄坏磁盘模拟 setBody 落盘失败)。
private struct HookedThreadCrypto: ThreadCrypto {
    let beforeSealMedia: @Sendable () throws -> Void
    private let inner = EchoThreadCrypto()

    init(_ beforeSealMedia: @escaping @Sendable () throws -> Void) { self.beforeSealMedia = beforeSealMedia }

    func sealText(peerId: String, text: String) async throws -> String { try await inner.sealText(peerId: peerId, text: text) }
    func sealMedia(peerId: String, items: [MediaItem]) async throws -> String {
        try beforeSealMedia()
        return try await inner.sealMedia(peerId: peerId, items: items)
    }
    func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? { await inner.openWire(wire, candidates: candidates) }
}
