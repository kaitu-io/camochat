import XCTest
import Combine
@testable import ChencangShared

@MainActor
final class MediaDownloaderTests: XCTestCase {
    private var base: URL!
    private var store: ChatStore!
    private var files: MediaFiles!
    private var transport: FakeMediaTransport!
    private var activity: MediaActivity!
    private var clock = Date(timeIntervalSince1970: 1_000_000)
    private let sentAt = Date(timeIntervalSince1970: 1_000_000)

    override func setUp() async throws {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-downloader-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: base.appendingPathComponent("chat"))
        files = MediaFiles(root: base.appendingPathComponent("media"))
        transport = FakeMediaTransport()
        activity = MediaActivity()
        clock = sentAt
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: base)
    }

    private func makeDownloader() -> MediaDownloader {
        MediaDownloader(store: store, mediaCrypto: CoreMediaCrypto(), transport: transport, files: files,
                        activity: activity, now: { [unowned self] in self.clock })
    }

    /// 造一条收到的媒体消息,并让传输桩能按 blobId 返回真密文。
    @discardableResult
    private func seedIncoming(id: String, kind: MediaKind, plain: Data = Data(repeating: 3, count: 4_000),
                              serve: Bool = true) throws -> (MediaItem, Data) {
        let sealed = try CoreMediaCrypto().seal(plain, kind: kind)
        let item = MediaItem(index: 0, kind: kind, durMs: kind == .image ? 0 : 2_000, width: 10, height: 10,
                             byteLen: sealed.blob.count, blobSecret: sealed.secret, blobId: sealed.blobId,
                             state: .pending)
        try store.append(ChatMessage(id: id, peerId: "alice", direction: .incoming, body: "", timestamp: sentAt,
                                     status: .received, kind: kind.messageKind, media: [item]))
        if serve { transport.serve(blobId: sealed.blobId, .success(sealed.blob)) }
        return (item, sealed.blob)
    }

    private func state(_ id: String) -> MediaItem.State? {
        store.message(id: id, peerId: "alice")?.media?.first?.state
    }

    func testDownloadDecryptsToBinAndDeletesCca() async throws {
        let plain = Data((0..<4_000).map { UInt8($0 % 251) })
        try seedIncoming(id: "m1", kind: .image, plain: plain)

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .ready)
        XCTAssertEqual(try Data(contentsOf: files.binURL(messageId: "m1", index: 0)), plain)
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))
    }

    func testExpiredAtExactly24HoursMakesNoRequest() async throws {
        try seedIncoming(id: "m1", kind: .image)
        clock = sentAt.addingTimeInterval(86_400)

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .expired)
        XCTAssertEqual(transport.downloadCalls, [], "过期后不发请求")
    }

    func testOneSecondBefore24HoursStillDownloads() async throws {
        try seedIncoming(id: "m1", kind: .voice)
        clock = sentAt.addingTimeInterval(86_399)

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .ready)
    }

    // MARK: - 先分享、后上传 §2:awaiting(等待对方上传)

    func testClassifyGoneBoundary() {
        let receivedAt = Date(timeIntervalSince1970: 5_000_000)
        XCTAssertEqual(classifyGone(receivedAt: receivedAt, now: receivedAt), .awaiting)
        XCTAssertEqual(classifyGone(receivedAt: receivedAt, now: receivedAt.addingTimeInterval(23 * 3_600 + 59 * 60)),
                       .awaiting, "23 h 59 m → 等待对方上传")
        XCTAssertEqual(classifyGone(receivedAt: receivedAt, now: receivedAt.addingTimeInterval(86_399)), .awaiting)
        XCTAssertEqual(classifyGone(receivedAt: receivedAt, now: receivedAt.addingTimeInterval(86_400)), .expired,
                       "24 h 整 → 已过期")
    }

    /// 以前 403/404 一律「已过期」(终态);现在收到不满 24 h 的是「还没上传」。
    func testGoneWithin24hBecomesAwaiting() async throws {
        try seedIncoming(id: "m1", kind: .image, serve: false)   // 桩对未知 blob 返回 .gone
        clock = sentAt.addingTimeInterval(86_399)
        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)
        XCTAssertEqual(transport.downloadCalls.count, 1)
    }

    func testAwaitingRefetchSucceeds() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .voice, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting, "对方还没传完:仍是等待,不是已过期")

        transport.serve(blobId: item.blobId, .success(blob))
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .ready)
        XCTAssertEqual(transport.downloadCalls.count, 3)
    }

    /// 24 h 后仍在 awaiting 的项再被轮询:不发请求,直接已过期(轮询随之结束)。
    func testAwaitingPast24hExpiresWithoutRequest() async throws {
        try seedIncoming(id: "m1", kind: .image, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        clock = sentAt.addingTimeInterval(86_400)
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .expired)
        XCTAssertEqual(transport.downloadCalls.count, 1, "过期后不发请求")
    }

    /// 轮询重试时气泡保持「等待对方上传」不闪成下载中;同一项同时只发一个请求。
    func testAwaitingRefetchKeepsStateAndDedupesConcurrentFetch() async throws {
        try seedIncoming(id: "m1", kind: .image, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        let store = self.store!
        let observed = LockedBox<MediaItem.State?>(nil)
        transport.setDownloadHook {
            await MainActor.run {
                observed.value = store.message(id: "m1", peerId: "alice")?.media?.first?.state
            }
            await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        }
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(observed.value, .awaiting)
        XCTAssertEqual(transport.downloadCalls.count, 2, "在途时的第二次调用不发请求")
        XCTAssertEqual(state("m1"), .awaiting)
    }

    // MARK: - Fix round 1

    /// I1:轮询中(开始时是 awaiting)遇到断网/5xx/429:保持 awaiting、不提示,交给下一轮。
    func testAwaitingPollTransientErrorStaysAwaitingSilently() async throws {
        let (item, _) = try seedIncoming(id: "m1", kind: .image, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        for failure in [MediaTransportError.network, .server(status: 503), .rateLimited] {
            transport.serve(blobId: item.blobId, .failure(failure))
            await downloader.download(messageId: "m1", peerId: "alice", index: 0)
            XCTAssertEqual(state("m1"), .awaiting, "\(failure)")
            XCTAssertNil(activity.notice, "\(failure) 不提示")
        }
    }

    /// 终审 F4:轮询中遇到 3xx(代理重定向)、408(超时)、5xx 与断网/429 一样是暂时性的,
    /// 保持 awaiting、不提示;其它 4xx 仍按失败处理。
    func testAwaitingPoll3xx408And5xxAreTransient() async throws {
        let (item, _) = try seedIncoming(id: "m1", kind: .image, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        for status in [301, 302, 307, 408, 500, 502] {
            transport.serve(blobId: item.blobId, .failure(.server(status: status)))
            await downloader.download(messageId: "m1", peerId: "alice", index: 0)
            XCTAssertEqual(state("m1"), .awaiting, "HTTP \(status)")
            XCTAssertNil(activity.notice, "HTTP \(status) 不提示")
        }

        transport.serve(blobId: item.blobId, .failure(.server(status: 400)))
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .failed, "其它 4xx 不是暂时性的")
    }

    /// 首次下载(pending)遇到同样的错误仍是 failed(红 !,可点重下)。
    func testFirstFetchTransientErrorStillFails() async throws {
        let (item, _) = try seedIncoming(id: "m1", kind: .image)
        transport.serve(blobId: item.blobId, .failure(.server(status: 503)))
        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .failed)
    }

    /// I1 端到端:awaiting → 轮询遇断网 → 仍 awaiting 且下一轮照常再试 → 200 → ready。
    func testAwaitingPollNetworkErrorKeepsPollingThenReady() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .image, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        let time = VirtualTime()
        let store = self.store!
        let poller = AwaitingPoller(
            clock: { time.now.timeIntervalSince1970 },
            sleeper: { await time.sleep($0) },
            tryFetch: { id, index in await downloader.download(messageId: id, peerId: "alice", index: index) },
            isAwaiting: { id, index in
                AwaitingPoller.keepsPolling(store.message(id: id, peerId: "alice")?.media?
                    .first(where: { $0.index == index })?.state)
            }
        )
        defer { poller.onHidden() }
        poller.onVisible([])
        poller.track([("m1", 0)])

        transport.serve(blobId: item.blobId, .failure(.network))
        await time.advance(by: 10)
        XCTAssertEqual(transport.downloadCalls.count, 2)
        XCTAssertEqual(state("m1"), .awaiting)
        XCTAssertNil(activity.notice)

        transport.serve(blobId: item.blobId, .success(blob))
        await time.advance(by: 20)   // t = 30:下一轮
        XCTAssertEqual(transport.downloadCalls.count, 3)
        XCTAssertEqual(state("m1"), .ready)
        await time.advance(by: 600)
        XCTAssertEqual(transport.downloadCalls.count, 3, "拿到后不再请求")
    }

    /// M1:轮询真拿到了(开始收 blob)→ 切到下载中,气泡出进度环;下完 ready。
    func testAwaitingSwitchesToDownloadingOnFirstProgress() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .video, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        transport.serve(blobId: item.blobId, .success(blob))
        transport.downloadProgressMidway = 0.3
        let store = self.store!
        let observed = LockedBox<MediaItem.State?>(nil)
        transport.setDownloadHook {
            // 等进度回调排出的主 actor 任务真把它切到下载中(有上限,不靠让出次数)。
            await Self.waitOnMain { store.message(id: "m1", peerId: "alice")?.media?.first?.state == .downloading }
            await MainActor.run { observed.value = store.message(id: "m1", peerId: "alice")?.media?.first?.state }
        }
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(observed.value, .downloading)
        XCTAssertEqual(state("m1"), .ready)
    }

    /// M1:切到下载中后中途断网 → 回到 awaiting(仍在等待语境里),不提示。
    func testAwaitingDownloadFailingMidwayFallsBackToAwaiting() async throws {
        let (item, _) = try seedIncoming(id: "m1", kind: .video, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)

        transport.serve(blobId: item.blobId, .failure(.network))
        transport.downloadProgressMidway = 0.3
        let store = self.store!
        let observed = LockedBox<MediaItem.State?>(nil)
        // 进度 1 那次主 actor 任务(setProgress 与 markDownloading 同一段同步执行)跑过的信号。
        let finalProgressSeen = LockedBox(false)
        let progressWatch = activity.progress(for: MediaActivity.key(messageId: "m1", index: 0)).$value
            .sink { if $0 == 1 { finalProgressSeen.value = true } }
        defer { progressWatch.cancel() }
        transport.setDownloadHook {
            // 等进度回调排出的主 actor 任务真把它切到下载中(有上限,不靠让出次数)。
            await Self.waitOnMain { store.message(id: "m1", peerId: "alice")?.media?.first?.state == .downloading }
            await MainActor.run { observed.value = store.message(id: "m1", peerId: "alice")?.media?.first?.state }
        }
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        // 迟到的进度任务(桩在失败前也报了一次 1)不能再把它改回下载中:等它确实跑过再断言。
        try await waitUntil { finalProgressSeen.value }

        XCTAssertEqual(observed.value, .downloading)
        XCTAssertEqual(state("m1"), .awaiting)
        XCTAssertNil(activity.notice)
    }

    /// Fix round 2 N1:轮询那次已切到下载中时,会话被隐藏又回来 → 这一项仍被接回轮询(且不重复下载);
    /// 那次下载中途失败退回 awaiting 后,下一轮照常再试。
    func testDownloadingItemRejoinsPollingAfterHideShow() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .video, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        let time = VirtualTime()
        let store = self.store!
        let poller = AwaitingPoller(
            clock: { time.now.timeIntervalSince1970 },
            sleeper: { await time.sleep($0) },
            tryFetch: { id, index in await downloader.download(messageId: id, peerId: "alice", index: index) },
            isAwaiting: { id, index in
                AwaitingPoller.keepsPolling(store.message(id: id, peerId: "alice")?.media?
                    .first(where: { $0.index == index })?.state)
            }
        )
        defer { poller.onHidden() }

        // 这一轮开始收 blob(切到下载中),然后卡在半路。
        transport.serve(blobId: item.blobId, .failure(.network))
        transport.downloadProgressMidway = 0.3
        let released = LockedBox(false)
        transport.setDownloadHook {
            while !released.value { try? await Task.sleep(nanoseconds: 1_000_000) }
        }
        poller.onVisible([("m1", 0)])
        try await waitUntil { self.state("m1") == .downloading }
        XCTAssertEqual(transport.downloadCalls.count, 2)

        // 切后台再回来:按可见目标重新开始轮询。
        poller.onHidden()
        poller.onVisible(AwaitingPoller.targets(in: store.messages(for: "alice")))
        await time.settle()
        XCTAssertEqual(transport.downloadCalls.count, 2, "在途的那次不重复下载")

        // 那次下载中途断网 → 退回 awaiting;下一轮照常再试,拿到了。
        released.value = true
        try await waitUntil { self.state("m1") == .awaiting }
        transport.downloadProgressMidway = nil
        transport.serve(blobId: item.blobId, .success(blob))
        await time.advance(by: 10)
        XCTAssertEqual(transport.downloadCalls.count, 3)
        XCTAssertEqual(state("m1"), .ready)
    }

    /// 在非主 actor 的桩钩子里有上限地等一个主 actor 上的条件成立。
    nonisolated private static func waitOnMain(timeout: TimeInterval = 5,
                                               _ condition: @escaping @MainActor @Sendable () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while await !MainActor.run(body: condition) {
            guard Date() < deadline else { return XCTFail("等待超时") }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
    }

    private func waitUntil(_ condition: @escaping () -> Bool, timeout: TimeInterval = 5) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else { return XCTFail("等待超时") }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
    }

    func testTargetsIncludeDownloadingItems() {
        func message(_ id: String, _ state: MediaItem.State) -> ChatMessage {
            ChatMessage(id: id, peerId: "alice", direction: .incoming, body: "", timestamp: sentAt, status: .received,
                        kind: .image, media: [MediaItem(index: 0, kind: .image, durMs: 0, width: 1, height: 1,
                                                        byteLen: 1, blobSecret: Data(), blobId: "b", state: state)])
        }
        let messages = [message("a", .awaiting), message("d", .downloading), message("p", .pending),
                        message("r", .ready), message("f", .failed)]
        XCTAssertEqual(AwaitingPoller.targets(in: messages).map(\.0), ["a", "d"])
    }

    func testKeepsPollingWhileAwaitingOrDownloading() {
        XCTAssertTrue(AwaitingPoller.keepsPolling(.awaiting))
        XCTAssertTrue(AwaitingPoller.keepsPolling(.downloading), "轮询里切到下载中的那一次还没结束")
        for state: MediaItem.State? in [.ready, .expired, .failed, .corrupt, .pending, nil] {
            XCTAssertFalse(AwaitingPoller.keepsPolling(state))
        }
    }

    /// 冷启动复位:awaiting 原样保留(downloading 仍回 pending)。
    func testAwaitingSurvivesColdStartReset() throws {
        let item = MediaItem(index: 0, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 10,
                             blobSecret: Data(repeating: 1, count: 32), blobId: "b", state: .awaiting)
        try store.append(ChatMessage(id: "m1", peerId: "alice", direction: .incoming, body: "", timestamp: sentAt,
                                     status: .received, kind: .image, media: [item]))
        try store.resetInterruptedTransfers()
        let reloaded = try ChatStore(directory: base.appendingPathComponent("chat"))
        XCTAssertEqual(reloaded.message(id: "m1", peerId: "alice")?.media?.first?.state, .awaiting)
    }

    /// 终审 F3:200 但长度与帧内 byte_len 对不上 = 传输问题,不是「文件已损坏」终态:首次下载 → failed(红 !,可再下)。
    func testLengthMismatchIsNetworkFailureNotCorrupt() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .image)
        let downloader = makeDownloader()
        for bad in [blob + Data([0]), blob.dropLast()] {
            try store.updateMediaItem(messageId: "m1", peerId: "alice", index: 0) { $0.state = .pending }
            transport.serve(blobId: item.blobId, .success(Data(bad)))
            await downloader.download(messageId: "m1", peerId: "alice", index: 0)
            XCTAssertEqual(state("m1"), .failed)
            XCTAssertFalse(files.exists(files.binURL(messageId: "m1", index: 0)))
            XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))
        }

        transport.serve(blobId: item.blobId, .success(blob))
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .ready, "再下就好")
    }

    /// 轮询中长度不对:与断网同样处理,保持 awaiting、不提示。
    func testAwaitingLengthMismatchStaysAwaitingSilently() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .image, serve: false)
        let downloader = makeDownloader()
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .awaiting)

        transport.serve(blobId: item.blobId, .success(blob.dropLast()))
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .awaiting)
        XCTAssertNil(activity.notice)
    }

    func testWrongKindIsCorrupt() async throws {
        // 把图片 blob 当语音引用发来:kind 绑定校验失败 → 文件已损坏
        let sealed = try CoreMediaCrypto().seal(Data(repeating: 1, count: 100), kind: .image)
        let item = MediaItem(index: 0, kind: .voice, durMs: 1_000, width: 0, height: 0, byteLen: sealed.blob.count,
                             blobSecret: sealed.secret, blobId: sealed.blobId, state: .pending)
        try store.append(ChatMessage(id: "m1", peerId: "alice", direction: .incoming, body: "", timestamp: sentAt,
                                     status: .received, kind: .voice, media: [item]))
        transport.serve(blobId: sealed.blobId, .success(sealed.blob))

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .corrupt)
        XCTAssertFalse(files.exists(files.ccaURL(messageId: "m1", index: 0)))
    }

    func testNetworkFailureIsRetryable() async throws {
        let (item, blob) = try seedIncoming(id: "m1", kind: .image)
        transport.serve(blobId: item.blobId, .failure(.network))
        let downloader = makeDownloader()

        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .failed)

        transport.serve(blobId: item.blobId, .success(blob))
        await downloader.download(messageId: "m1", peerId: "alice", index: 0)
        XCTAssertEqual(state("m1"), .ready)
    }

    func testRateLimitedIsRetryable() async throws {
        let (item, _) = try seedIncoming(id: "m1", kind: .image)
        transport.serve(blobId: item.blobId, .failure(.rateLimited))

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .failed)
    }

    func testServerErrorIsRetryable() async throws {
        let (item, _) = try seedIncoming(id: "m1", kind: .image)
        transport.serve(blobId: item.blobId, .failure(.server(status: 500)))

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertEqual(state("m1"), .failed)
    }

    func testMessageDeletedDuringDownloadLeavesNoFilesAndNoCrash() async throws {
        try seedIncoming(id: "m1", kind: .image)
        let store = self.store!
        let files = self.files!
        transport.setDownloadHook {
            try? await ThreadCleaner(chatStore: store, mediaFiles: files, cancelUploads: { _ in })
                .deleteMessage(id: "m1", peerId: "alice")
        }

        await makeDownloader().download(messageId: "m1", peerId: "alice", index: 0)

        XCTAssertNil(store.message(id: "m1", peerId: "alice"))
        XCTAssertFalse(files.exists(files.binURL(messageId: "m1", index: 0)))
        XCTAssertFalse(files.exists(files.directory(messageId: "m1")), "删掉的消息不能留下媒体目录")
    }

    func testAutoDownloadFetchesVoiceAndImagesSkipsVideoAndCapsConcurrencyAtTwo() async throws {
        for i in 0..<5 { try seedIncoming(id: "img\(i)", kind: .image) }
        try seedIncoming(id: "vid", kind: .video)
        transport.downloadDelayNanos = 50_000_000

        await makeDownloader().autoDownload(peerId: "alice")

        for i in 0..<5 { XCTAssertEqual(state("img\(i)"), .ready) }
        XCTAssertEqual(state("vid"), .pending, "视频点击后才下载")
        XCTAssertEqual(transport.maxInFlight, 2)
        XCTAssertEqual(transport.downloadCalls.count, 5)
    }

    // MARK: - 终审修复波

    /// 终审 I6:进线程那一次(retryFailed)把上次网络失败的语音/图片再下一次;线程里新到消息触发的
    /// 那几次(默认)不重打失败项。
    func testAutoDownloadRetriesFailedOnlyOnThreadEntry() async throws {
        let (item, blob) = try seedIncoming(id: "img", kind: .image)
        transport.serve(blobId: item.blobId, .failure(.network))
        let downloader = makeDownloader()
        await downloader.autoDownload(peerId: "alice")
        XCTAssertEqual(state("img"), .failed)

        transport.serve(blobId: item.blobId, .success(blob))
        await downloader.autoDownload(peerId: "alice")
        XCTAssertEqual(state("img"), .failed, "新消息触发的自动下载不重打失败项")
        XCTAssertEqual(transport.downloadCalls.count, 1)

        await downloader.autoDownload(peerId: "alice", retryFailed: true)
        XCTAssertEqual(state("img"), .ready, "进线程时失败项自动重下一次")
        XCTAssertEqual(transport.downloadCalls.count, 2)
    }

    /// 终审 minor 14:帧内 byte_len 超过这类媒体的 `.cca` 上限 → 直接「文件已损坏」,不发请求。
    func testOversizedByteLenIsCorruptWithoutRequest() async throws {
        let sealed = try CoreMediaCrypto().seal(Data(repeating: 1, count: 100), kind: .image)
        let item = MediaItem(index: 0, kind: .image, durMs: 0, width: 10, height: 10,
                             byteLen: MediaKind.image.maxBlobLen + 1,
                             blobSecret: sealed.secret, blobId: sealed.blobId, state: .pending)
        try store.append(ChatMessage(id: "big", peerId: "alice", direction: .incoming, body: "", timestamp: sentAt,
                                     status: .received, kind: .image, media: [item]))
        transport.serve(blobId: sealed.blobId, .success(sealed.blob))

        await makeDownloader().download(messageId: "big", peerId: "alice", index: 0)

        XCTAssertEqual(state("big"), .corrupt)
        XCTAssertEqual(transport.downloadCalls, [], "超上限不发请求")
    }

    func testByteLenAtKindLimitStillRequests() async throws {
        let (item, _) = try seedIncoming(id: "ok", kind: .video)
        XCTAssertLessThanOrEqual(item.byteLen, MediaKind.video.maxBlobLen)
        await makeDownloader().download(messageId: "ok", peerId: "alice", index: 0)
        XCTAssertEqual(state("ok"), .ready)
    }

    /// 终审 F3:点了视频下载,`download` 返回后**从存储重新读**这一项再判定。旧写法用闭包里捕获的
    /// `item`(状态还是 pending),判定恒为 false——这里同时钉住旧写法确实会漏。
    func testVideoAutoPlayDecidesOnFreshStateAfterDownload() async throws {
        let (captured, _) = try seedIncoming(id: "v1", kind: .video)
        let tapped = VideoAutoPlay.Pending(messageId: "v1", index: 0)

        await makeDownloader().download(messageId: "v1", peerId: "alice", index: 0)

        XCTAssertFalse(VideoAutoPlay.shouldOpen(pending: tapped, messageId: "v1", item: captured),
                       "捕获的旧 item 状态不是 ready——旧的 onChange 写法永远打不开")
        let fresh = VideoAutoPlay.itemToOpen(pending: tapped, tapped: tapped, peerId: "alice", store: store)
        XCTAssertEqual(fresh?.state, .ready)
        XCTAssertEqual(fresh?.kind, .video)

        XCTAssertNil(VideoAutoPlay.itemToOpen(pending: nil, tapped: tapped, peerId: "alice", store: store),
                     "离开线程(标记已清)后不自动播放")
        XCTAssertNil(VideoAutoPlay.itemToOpen(pending: VideoAutoPlay.Pending(messageId: "other", index: 0),
                                              tapped: tapped, peerId: "alice", store: store),
                     "用户又点了别的视频:只播最后点的那个")
    }

    func testVideoAutoPlayDoesNotOpenWhenDownloadFailed() async throws {
        let (item, _) = try seedIncoming(id: "v1", kind: .video)
        transport.serve(blobId: item.blobId, .failure(.network))
        let tapped = VideoAutoPlay.Pending(messageId: "v1", index: 0)

        await makeDownloader().download(messageId: "v1", peerId: "alice", index: 0)

        XCTAssertNil(VideoAutoPlay.itemToOpen(pending: tapped, tapped: tapped, peerId: "alice", store: store))
    }

    /// 终审 minor 12:明文 `.bin` 用 completeFileProtectionUnlessOpen,密文 `.cca` 不变。
    func testPlaintextWritesUseFileProtectionUnlessOpen() {
        XCTAssertTrue(MediaFiles.writingOptions(for: files.binURL(messageId: "m", index: 0))
            .contains(.completeFileProtectionUnlessOpen))
        XCTAssertFalse(MediaFiles.writingOptions(for: files.ccaURL(messageId: "m", index: 0))
            .contains(.completeFileProtectionUnlessOpen))
        XCTAssertTrue(MediaFiles.writingOptions(for: files.ccaURL(messageId: "m", index: 0)).contains(.atomic))
    }
}

/// 测试里跨并发域记一个值。
final class LockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: T
    init(_ value: T) { _value = value }
    var value: T {
        get { lock.withLock { _value } }
        set { lock.withLock { _value = newValue } }
    }
}
