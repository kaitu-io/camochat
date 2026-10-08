import XCTest
@testable import ChencangShared

/// 假后台会话里的一个上传任务。
final class FakeUploadTask: UploadTaskHandle {
    /// 会话内唯一的任务编号(系统的 `URLSessionTask.taskIdentifier`)。
    private static var nextIdentifier = 1
    let taskIdentifier: Int
    var taskDescription: String?
    let request: URLRequest
    let file: URL
    var resumed = false
    var cancelled = false
    var finished = false
    var onCancel: ((FakeUploadTask) -> Void)?

    /// `identifier` 给定时模拟「同一个系统任务、另一个包装对象」(委托回调/`allTasks` 递来的新对象)。
    init(request: URLRequest, file: URL, description: String? = nil, identifier: Int? = nil) {
        if let identifier {
            taskIdentifier = identifier
        } else {
            taskIdentifier = Self.nextIdentifier
            Self.nextIdentifier += 1
        }
        self.request = request
        self.file = file
        self.taskDescription = description
    }

    func resume() { resumed = true }
    func cancel() {
        cancelled = true
        onCancel?(self)
    }
}

/// 假后台会话:记录建过的任务;`allTasks` 只返回还没结束、没被取消的(含「上个进程留下的」)。
final class FakeUploadSession: UploadSession {
    var created: [FakeUploadTask] = []
    var allTasksCalls = 0
    /// 模拟系统替上一个进程续着传的任务(本进程没建过)。
    var preexisting: [FakeUploadTask] = []
    var onCancel: ((FakeUploadTask) -> Void)?

    func uploadTask(with request: URLRequest, fromFile file: URL) -> UploadTaskHandle {
        let task = FakeUploadTask(request: request, file: file)
        task.onCancel = { [weak self] in self?.onCancel?($0) }
        created.append(task)
        return task
    }

    func allTasks() async -> [UploadTaskHandle] {
        allTasksCalls += 1
        return (preexisting + created).filter { !$0.cancelled && !$0.finished }
    }

    func addPreexisting(_ description: String) -> FakeUploadTask {
        let task = FakeUploadTask(request: URLRequest(url: URL(string: "https://upload.test/x")!),
                                  file: URL(fileURLWithPath: "/dev/null"), description: description)
        task.onCancel = { [weak self] in self?.onCancel?($0) }
        preexisting.append(task)
        return task
    }
}

@MainActor
final class BackgroundUploaderTests: XCTestCase {
    private var base: URL!
    private var store: ChatStore!
    private var files: MediaFiles!
    private var activity: MediaActivity!
    private var transport: FakeMediaTransport!
    private var mediaCrypto: CountingMediaCrypto!
    private var sender: MediaSender!
    private var session: FakeUploadSession!
    private var uploader: BackgroundUploader!
    private var foreground = true
    private var sleeps: [TimeInterval] = []
    /// 每次后台任务包裹时,进入/退出时 presign 请求数的快照。
    private var backgroundRuns: [(before: Int, after: Int)] = []
    private var idCounter = 0

    override func setUp() async throws {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-bg-uploader-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: base.appendingPathComponent("chat"))
        files = MediaFiles(root: base.appendingPathComponent("media"))
        activity = MediaActivity()
        transport = FakeMediaTransport()
        mediaCrypto = CountingMediaCrypto()
        session = FakeUploadSession()
        foreground = true
        sleeps = []
        backgroundRuns = []
        idCounter = 0
        sender = MediaSender(store: store, crypto: EchoThreadCrypto(), mediaCrypto: mediaCrypto,
                             transport: transport, files: files, activity: activity,
                             uploadScheduler: { _ in },
                             now: { Date(timeIntervalSince1970: 1_000) },
                             newId: { [unowned self] in self.idCounter += 1; return "m\(self.idCounter)" })
        uploader = makeUploader()
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: base)
    }

    private func makeUploader() -> BackgroundUploader {
        BackgroundUploader(session: session, sender: sender, store: store, files: files, activity: activity,
                           isForeground: { [unowned self] in self.foreground },
                           backgroundTask: { [unowned self] work in
                               let before = self.transport.requested.count
                               await work()
                               self.backgroundRuns.append((before, self.transport.requested.count))
                           },
                           sleep: { [unowned self] seconds in self.sleeps.append(seconds) })
    }

    private func image(_ fill: UInt8 = 0xAB) -> PreparedMedia {
        PreparedMedia(kind: .image, plaintext: Data(repeating: fill, count: 2_000), durMs: 0, width: 10, height: 10)
    }

    /// 封缄(零网络)一条 n 张图的消息,返回其 id。
    private func seal(_ count: Int = 1, to peer: String = "alice") async throws -> String {
        let outcome = await sender.seal(to: peer, media: (0..<count).map { image(UInt8($0)) })
        guard case let .sealed(id, _) = outcome else { throw XCTSkip("封缄失败:\(outcome)") }
        return id
    }

    private func state(_ id: String, _ index: Int = 0, peer: String = "alice") -> MediaItem.State? {
        store.message(id: id, peerId: peer)?.media?.first { $0.index == index }?.state
    }

    private func setState(_ id: String, _ index: Int, _ state: MediaItem.State, peer: String = "alice") throws {
        try store.updateMediaItem(messageId: id, peerId: peer, index: index) { $0.state = state }
    }

    /// 结束一个假任务并把结果交给上传引擎(就像后台会话的委托回调)。
    @discardableResult
    private func complete(_ description: String, status: Int?, error: Error? = nil) async -> Task<Void, Never>? {
        let task = session.created.last { $0.taskDescription == description && !$0.finished }
        task?.finished = true
        let follow = uploader.handleCompletion(taskDescription: description, httpStatus: status, error: error,
                                               taskIdentifier: task?.taskIdentifier)
        await follow?.value
        return follow
    }

    private var liveDescriptions: [String] {
        session.created.filter { !$0.finished && !$0.cancelled }.compactMap(\.taskDescription)
    }

    // MARK: - enqueue

    func testEnqueueCreatesOneTaskPerPendingItemWithSafeDescription() async throws {
        let id = try await seal(2)
        XCTAssertTrue(session.created.isEmpty, "封缄零网络、不建任务")

        await uploader.enqueue(messageId: id).value

        // presign 并发,建任务顺序不定:按 description 排序再逐项核对
        let created = session.created.sorted { ($0.taskDescription ?? "") < ($1.taskDescription ?? "") }
        XCTAssertEqual(created.map(\.taskDescription), ["\(id):0", "\(id):1"])
        XCTAssertTrue(created.allSatisfy(\.resumed))
        let items = try XCTUnwrap(store.message(id: id, peerId: "alice")?.media)
        for (task, item) in zip(created, items) {
            let description = try XCTUnwrap(task.taskDescription)
            XCTAssertFalse(description.contains(item.blobId), "description 不含 blob id")
            XCTAssertFalse(description.contains(item.blobSecret.base64EncodedString()), "description 不含 secret")
            XCTAssertFalse(description.contains(item.blobSecret.map { String(format: "%02x", $0) }.joined()))
            XCTAssertEqual(task.file, files.ccaURL(messageId: id, index: item.index), "从 .cca 文件上传")
            XCTAssertEqual(task.request.httpMethod, "PUT")
            XCTAssertEqual(task.request.value(forHTTPHeaderField: "If-None-Match"), "*")
            XCTAssertEqual(task.request.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
            XCTAssertNil(task.request.httpBody, "后台上传只能从文件读 body")
        }
        XCTAssertEqual(transport.requested.count, 2, "每项一次新 presign")
        XCTAssertEqual(backgroundRuns.count, 1)
        XCTAssertEqual(backgroundRuns.first?.before, 0)
        XCTAssertEqual(backgroundRuns.first?.after, 2, "presign 包在 beginBackgroundTask 里")
        XCTAssertEqual(items.map(\.state), [.uploading, .uploading])
    }

    func testEnqueueSkipsSealedAndTurnsFailedBackToUploading() async throws {
        let id = try await seal(3)
        try setState(id, 0, .sealed)
        try setState(id, 2, .failed)

        await uploader.enqueue(messageId: id).value

        // presign 并发,建任务顺序不定
        XCTAssertEqual(Set(session.created.compactMap(\.taskDescription)), ["\(id):1", "\(id):2"])
        XCTAssertEqual(session.created.count, 2)
        XCTAssertEqual(state(id, 0), .sealed)
        XCTAssertEqual(state(id, 2), .uploading, "重新交给引擎的项要显示上传中")
    }

    func testEnqueueTwiceDoesNotDuplicateTasks() async throws {
        let id = try await seal()
        let first = uploader.enqueue(messageId: id)
        let second = uploader.enqueue(messageId: id)
        await first.value
        await second.value
        await uploader.enqueue(messageId: id).value

        XCTAssertEqual(session.created.count, 1)
        XCTAssertEqual(transport.requested.count, 1)
    }

    func testEnqueueUnsharedMessageDoesNothing() async throws {
        try store.append(ChatMessage(id: "raw", peerId: "alice", direction: .outgoing, body: "",
                                     timestamp: Date(), status: .sealed, kind: .image,
                                     media: [MediaItem(index: 0, kind: .image, durMs: 0, width: 1, height: 1,
                                                       byteLen: 1, blobSecret: Data([1]), blobId: "b",
                                                       state: .uploading)]))
        await uploader.enqueue(messageId: "raw").value
        XCTAssertTrue(session.created.isEmpty)
        XCTAssertTrue(transport.requested.isEmpty)
    }

    func testMissingCcaMarksFailedWithoutFlicker() async throws {
        let id = try await seal()
        try setState(id, 0, .failed)
        files.remove(files.ccaURL(messageId: id, index: 0))

        await uploader.enqueue(messageId: id).value

        XCTAssertTrue(session.created.isEmpty)
        XCTAssertTrue(transport.requested.isEmpty)
        XCTAssertEqual(state(id), .failed, "文件丢失的项不重新加密、不重传")
    }

    func testMessageDeletedDuringPresignCreatesNoTask() async throws {
        let id = try await seal()
        let store = self.store!
        transport.setRequestHook {
            await MainActor.run { try? store.remove(id: id, peerId: "alice") }
        }

        await uploader.enqueue(messageId: id).value

        XCTAssertTrue(session.created.isEmpty, "presign 途中删了消息:不再建任务读它的 .cca")
        XCTAssertNil(store.message(id: id, peerId: "alice"))
    }

    // MARK: - handleCompletion

    func testCompletion412MarksSealed() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value

        let follow = await complete("\(id):0", status: 412)

        XCTAssertNil(follow)
        XCTAssertEqual(state(id), .sealed)
        XCTAssertFalse(files.exists(files.ccaURL(messageId: id, index: 0)), "上传成功后才删 .cca")
        XCTAssertEqual(session.created.count, 1)
    }

    func testCompletion2xxMarksSealedAndClearsProgress() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        uploader.handleProgress(taskDescription: "\(id):0", fraction: 0.4)
        XCTAssertEqual(activity.progressValue(messageId: id, index: 0), 0.4)

        await complete("\(id):0", status: 201)

        XCTAssertEqual(state(id), .sealed)
        XCTAssertNil(activity.progressValue(messageId: id, index: 0))
    }

    func testCompletion403InForegroundReenqueues() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        foreground = true

        let follow = await complete("\(id):0", status: 403)

        XCTAssertNotNil(follow)
        XCTAssertEqual(session.created.count, 2, "立即重新 presign 再传")
        XCTAssertEqual(transport.requested.count, 2)
        XCTAssertEqual(liveDescriptions, ["\(id):0"])
        XCTAssertEqual(state(id), .uploading)
        XCTAssertTrue(sleeps.isEmpty, "403 不退避")
        let items = try XCTUnwrap(store.message(id: id, peerId: "alice")?.media)
        XCTAssertEqual(transport.requested.map(\.blobId), [items[0].blobId, items[0].blobId], "同一份密文,不重新加密")
    }

    func testCompletion403InBackgroundWaitsForReconcile() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        foreground = false

        let follow = await complete("\(id):0", status: 403)

        XCTAssertNil(follow)
        XCTAssertEqual(session.created.count, 1, "后台拿到 403 不立刻重传(URL 可能又会过期)")
        XCTAssertEqual(state(id), .uploading)

        foreground = true
        await uploader.reconcile()

        XCTAssertEqual(session.created.count, 2)
        XCTAssertEqual(liveDescriptions, ["\(id):0"])
    }

    func testRetryableFailureBacksOffThenReenqueues() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value

        await complete("\(id):0", status: nil, error: URLError(.notConnectedToInternet))
        XCTAssertEqual(sleeps, [10])
        XCTAssertEqual(session.created.count, 2)

        await complete("\(id):0", status: 500)
        XCTAssertEqual(sleeps, [10, 30])
        await complete("\(id):0", status: 429)
        XCTAssertEqual(sleeps, [10, 30, 60])
        XCTAssertEqual(session.created.count, 4)
        XCTAssertEqual(state(id), .uploading)
    }

    func testBackoffFiringInBackgroundLeavesItForReconcile() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        foreground = false

        await complete("\(id):0", status: 503)

        XCTAssertEqual(sleeps, [10])
        XCTAssertEqual(session.created.count, 1)
        XCTAssertEqual(state(id), .uploading)
    }

    func testFiveConsecutiveRetryableFailuresMarkItemFailed() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value

        for _ in 0..<4 { await complete("\(id):0", status: 302) }
        XCTAssertEqual(state(id), .uploading)
        XCTAssertEqual(session.created.count, 5)

        let follow = await complete("\(id):0", status: 302)

        XCTAssertNil(follow)
        XCTAssertEqual(state(id), .failed, "连续 5 次可重试失败 → 对方还看不到")
        XCTAssertEqual(session.created.count, 5)
        XCTAssertTrue(files.exists(files.ccaURL(messageId: id, index: 0)), ".cca 保留给手动重传")
    }

    func testRepeated403InForegroundAlsoGivesUpAfterFive() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value

        for _ in 0..<5 { await complete("\(id):0", status: 403) }

        XCTAssertEqual(state(id), .failed)
        XCTAssertEqual(session.created.count, 5)
    }

    func testUnknownClientErrorFailsImmediately() async throws {
        for status in [400, 404, 405] {
            let id = try await seal()
            await uploader.enqueue(messageId: id).value
            let follow = await complete("\(id):0", status: status)
            XCTAssertNil(follow)
            XCTAssertEqual(state(id), .failed, "HTTP \(status)")
            XCTAssertEqual(store.message(id: id, peerId: "alice")?.media?.first?.uploadFailure, .rejected,
                           "HTTP \(status) 记为永久被拒")
        }
        XCTAssertTrue(sleeps.isEmpty)
    }

    func testRejectedIsPersistedAndNotRetriedByReconcileOrManualRetry() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        await complete("\(id):0", status: 400)
        let reloaded = try ChatStore(directory: base.appendingPathComponent("chat"))
        XCTAssertEqual(reloaded.message(id: id, peerId: "alice")?.media?.first?.uploadFailure, .rejected, "已落盘")
        XCTAssertEqual(store.message(id: id, peerId: "alice")?.outgoingMediaStatus(files: files),
                       .permanentlyFailed(.rejected))
        let presigns = transport.requested.count

        await uploader.reconcile()
        await uploader.enqueue(messageId: id).value

        XCTAssertEqual(transport.requested.count, presigns, "终态:对账与重试都跳过")
        XCTAssertEqual(state(id), .failed)
    }

    func testExhaustedRetriesStayRetryable() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        for _ in 0..<5 { await complete("\(id):0", status: 500) }
        XCTAssertEqual(state(id), .failed)
        XCTAssertNil(store.message(id: id, peerId: "alice")?.media?.first?.uploadFailure, "5xx 不是永久原因")
        XCTAssertEqual(store.message(id: id, peerId: "alice")?.outgoingMediaStatus(files: files), .notVisibleToPeer)
    }

    func testRetryable4xxDoNotFailImmediately() async throws {
        for status in [408, 429] {
            let id = try await seal()
            await uploader.enqueue(messageId: id).value
            await complete("\(id):0", status: status)
            XCTAssertEqual(state(id), .uploading, "HTTP \(status)")
        }
    }

    func testTooLargeIsPermanentFailed() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value

        let follow = await complete("\(id):0", status: 413)

        XCTAssertNil(follow)
        XCTAssertEqual(state(id), .failed)
        XCTAssertEqual(session.created.count, 1)
    }

    func testManualEnqueueResetsFailureCounter() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        for _ in 0..<5 { await complete("\(id):0", status: 500) }
        XCTAssertEqual(state(id), .failed)

        await uploader.enqueue(messageId: id).value      // 用户点「对方还看不到 · 点击重新上传」
        XCTAssertEqual(state(id), .uploading)
        await complete("\(id):0", status: 500)

        XCTAssertEqual(state(id), .uploading, "手动重试后计数从零开始")
    }

    func testPresignFailureIsRetriedWithBackoff() async throws {
        let id = try await seal()
        transport.failNextRequests([.network])

        await uploader.enqueue(messageId: id).value
        XCTAssertTrue(session.created.isEmpty)
        XCTAssertEqual(sleeps, [10])
        // 退避任务在后台跑,再 enqueue 一次不会与它重复;给它时间完成。
        for _ in 0..<20 where session.created.isEmpty { await Task.yield() }
        XCTAssertEqual(session.created.map(\.taskDescription), ["\(id):0"])
    }

    func testPresignClientErrorFailsImmediately() async throws {
        let id = try await seal()
        transport.failNextRequests([.server(status: 400)])

        await uploader.enqueue(messageId: id).value

        XCTAssertTrue(session.created.isEmpty)
        XCTAssertEqual(state(id), .failed)
        XCTAssertEqual(store.message(id: id, peerId: "alice")?.media?.first?.uploadFailure, .rejected)
    }

    func testOwnCancellationCompletionIsIgnored() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value

        let follow = await complete("\(id):0", status: nil, error: URLError(.cancelled))

        XCTAssertNil(follow)
        XCTAssertEqual(state(id), .uploading)
        XCTAssertTrue(sleeps.isEmpty)
    }

    func testFinishEventsBeforeHandlerStoredCallsHandlerOnceStored() {
        uploader.handleEventsFinished()      // 系统先交付完本批事件,AppDelegate 才把 handler 递过来
        var calls = 0
        uploader.backgroundCompletionHandler = { calls += 1 }
        XCTAssertEqual(calls, 1, "handler 一到就调")
        XCTAssertNil(uploader.backgroundCompletionHandler)

        uploader.backgroundCompletionHandler = { calls += 1 }
        XCTAssertEqual(calls, 1, "挂起标记只消费一次:下一批的 handler 等下一次 finished")
        uploader.handleEventsFinished()
        XCTAssertEqual(calls, 2)
    }

    func testFinishEventsCallsSystemCompletionHandlerOnce() {
        var calls = 0
        uploader.backgroundCompletionHandler = { calls += 1 }
        uploader.handleEventsFinished()
        uploader.handleEventsFinished()
        XCTAssertEqual(calls, 1)
        XCTAssertNil(uploader.backgroundCompletionHandler)
    }

    func testPresignsAllItemsOfAMessageConcurrently() async throws {
        let id = try await seal(9)
        transport.requestDelayNanos = 50_000_000

        await uploader.enqueue(messageId: id).value

        XCTAssertEqual(transport.maxRequestsInFlight, 9, "9 项并发 presign,不是一项一项排队(封缄后尽快在前台建完任务)")
        XCTAssertEqual(session.created.count, 9)
        XCTAssertEqual(Set(session.created.compactMap(\.taskDescription)), Set((0..<9).map { "\(id):\($0)" }))
        XCTAssertEqual(backgroundRuns.count, 1)
        XCTAssertEqual(backgroundRuns.first?.after, 9, "全部 presign 与建任务都在同一个 beginBackgroundTask 里")
    }

    func testPresign403InForegroundKeepsRequeuedItemMarkedPresigning() async throws {
        let id = try await seal()
        transport.failNextRequests([.server(status: 403)])
        foreground = true

        await uploader.enqueue(messageId: id).value      // presign 403 → 前台立即重排同一项
        await uploader.enqueue(messageId: id).value      // 重排还在 presign 中:这次不能再发一次
        for _ in 0..<50 where session.created.isEmpty { await Task.yield() }

        XCTAssertEqual(session.created.count, 1)
        XCTAssertEqual(transport.requested.count, 2, "失败的那次 + 重排的那次,没有第三次")
    }

    func testLateFailureFromSupersededTaskKeepsNewerHandle() async throws {
        let id = try await seal()
        let stale = session.addPreexisting("\(id):0")   // 上个进程的任务,本进程已建了新任务取代它
        stale.finished = true
        await uploader.enqueue(messageId: id).value
        let current = try XCTUnwrap(session.created.first)

        let follow = uploader.handleCompletion(taskDescription: "\(id):0", httpStatus: 500, error: nil,
                                               taskIdentifier: stale.taskIdentifier)

        XCTAssertNil(follow)
        XCTAssertTrue(sleeps.isEmpty, "旧任务的失败不影响在跑的新任务")
        uploader.handleProgress(taskDescription: "\(id):0", fraction: 0.3)
        XCTAssertEqual(activity.progressValue(messageId: id, index: 0), 0.3, "新任务的句柄还在")
        await uploader.enqueue(messageId: id).value
        XCTAssertEqual(session.created.count, 1, "不会因为句柄被清而再建一个")
        XCTAssertFalse(current.cancelled)
    }

    func testLateSuccessFromSupersededTaskSealsAndCancelsNewer() async throws {
        let id = try await seal()
        let stale = session.addPreexisting("\(id):0")
        stale.finished = true
        await uploader.enqueue(messageId: id).value
        let current = try XCTUnwrap(session.created.first)

        uploader.handleCompletion(taskDescription: "\(id):0", httpStatus: 200, error: nil,
                                               taskIdentifier: stale.taskIdentifier)

        XCTAssertEqual(state(id), .sealed)
        XCTAssertTrue(current.cancelled, "对象已在桶里:新任务没必要再传")
    }

    /// 系统每次回调递来的可能是另一个包装对象:只要任务编号相同,就是本项当前的任务。
    func testCompletionFromDifferentWrapperOfCurrentTaskIsNotTreatedAsSuperseded() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        let current = try XCTUnwrap(session.created.first)
        let wrapper = FakeUploadTask(request: current.request, file: current.file, description: "\(id):0",
                                     identifier: current.taskIdentifier)
        current.finished = true

        let follow = uploader.handleCompletion(taskDescription: "\(id):0", httpStatus: 500, error: nil,
                                               taskIdentifier: wrapper.taskIdentifier)
        await follow?.value

        XCTAssertEqual(sleeps, [10], "当前任务的失败照常退避重排")
        XCTAssertEqual(session.created.count, 2)
    }

    func testSuccessOfCurrentTaskViaDifferentWrapperDoesNotCancelIt() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        let current = try XCTUnwrap(session.created.first)
        current.finished = true

        uploader.handleCompletion(taskDescription: "\(id):0", httpStatus: 200, error: nil,
                                  taskIdentifier: current.taskIdentifier)

        XCTAssertEqual(state(id), .sealed)
        XCTAssertFalse(current.cancelled)
    }

    /// 重启后:对账按 taskDescription 认领系统续传的任务,之后按任务编号认它的完成回调。
    func testReattachedTaskIsMatchedByIdentifierAfterRelaunch() async throws {
        let id = try await seal()
        let resumed = session.addPreexisting("\(id):0")
        await uploader.reconcile()
        XCTAssertTrue(session.created.isEmpty, "认领了,不重建")
        resumed.finished = true

        uploader.handleCompletion(taskDescription: "\(id):0", httpStatus: 200, error: nil,
                                  taskIdentifier: resumed.taskIdentifier)

        XCTAssertEqual(state(id), .sealed)
        XCTAssertFalse(resumed.cancelled, "认领的任务就是当前任务,不当作被取代")
    }

    // MARK: - 413(审查 M7)

    func testTooLargeIsPersistedAndNotRetriedByReconcile() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        await complete("\(id):0", status: 413)
        XCTAssertEqual(activity.notice, L10n.mediaFailureTooLarge)
        activity.consumeNotice()
        let item = try XCTUnwrap(store.message(id: id, peerId: "alice")?.media?.first)
        XCTAssertEqual(item.state, .failed)
        XCTAssertEqual(item.uploadFailure, .tooLarge)
        let reloaded = try ChatStore(directory: base.appendingPathComponent("chat"))
        XCTAssertEqual(reloaded.message(id: id, peerId: "alice")?.media?.first?.uploadFailure, .tooLarge, "已落盘")
        let presigns = transport.requested.count

        await uploader.reconcile()
        await uploader.reconcile()

        XCTAssertEqual(transport.requested.count, presigns, "对账不再重传注定 413 的项")
        XCTAssertEqual(session.created.count, 1)
        XCTAssertNil(activity.notice, "不会每次回前台都弹「文件太大」")
        XCTAssertEqual(state(id), .failed)
    }

    func testReconcileStillRetriesOtherItemsOfMessageWithTooLargeItem() async throws {
        let id = try await seal(2)
        await uploader.enqueue(messageId: id).value
        await complete("\(id):0", status: 413)
        await complete("\(id):1", status: 500)
        try setState(id, 1, .failed)
        session.created.forEach { $0.finished = true }

        await uploader.reconcile()

        XCTAssertEqual(liveDescriptions, ["\(id):1"])
    }

    func testManualRetrySkipsTooLargeButRetriesOtherItems() async throws {
        let id = try await seal(2)
        await uploader.enqueue(messageId: id).value
        await complete("\(id):0", status: 413)
        await complete("\(id):1", status: 400)
        try store.updateMediaItem(messageId: id, peerId: "alice", index: 1) { $0.uploadFailure = nil }
        let presigns = transport.requested.count

        await uploader.enqueue(messageId: id).value      // 用户点「对方还看不到 · 点击重新上传」

        XCTAssertEqual(transport.requested.count, presigns + 1, "只重传可重试的那一项")
        let item = try XCTUnwrap(store.message(id: id, peerId: "alice")?.media?.first)
        XCTAssertEqual(item.state, .failed, "413 是终态")
        XCTAssertEqual(item.uploadFailure, .tooLarge)
        XCTAssertEqual(state(id, 1), .uploading)
    }

    // MARK: - reconcile

    func testReconcileSkipsItemsWithLiveTask() async throws {
        let id = try await seal(2)
        _ = session.addPreexisting("\(id):0")     // 上个进程建的、系统还在续传

        await uploader.reconcile()

        XCTAssertEqual(session.created.map(\.taskDescription), ["\(id):1"])
        XCTAssertEqual(transport.requested.count, 1)
    }

    func testReconcileEnqueuesSharedUploadingAndFailed() async throws {
        let a = try await seal()
        let b = try await seal(to: "bob")
        try setState(b, 0, .failed, peer: "bob")
        let c = try await seal()
        try setState(c, 0, .sealed)

        await uploader.reconcile()

        XCTAssertEqual(Set(session.created.compactMap(\.taskDescription)), ["\(a):0", "\(b):0"])
        XCTAssertEqual(state(b, peer: "bob"), .uploading)
    }

    func testReconcileIgnoresUnshared() async throws {
        let raw = MediaItem(index: 0, kind: .image, durMs: 0, width: 1, height: 1, byteLen: 1,
                            blobSecret: Data([1]), blobId: "b", state: .uploading)
        var failed = raw
        failed.state = .failed
        try store.append(ChatMessage(id: "x", peerId: "alice", direction: .outgoing, body: "",
                                     timestamp: Date(), status: .sealed, kind: .image, media: [raw, failed]))
        try files.write(Data([1]), to: files.ccaURL(messageId: "x", index: 0))
        var incoming = raw
        incoming.state = .failed
        try store.append(ChatMessage(id: "y", peerId: "alice", direction: .incoming, body: "🔒 …",
                                     timestamp: Date(), status: .received, kind: .image, media: [incoming]))

        await uploader.reconcile()

        XCTAssertTrue(session.created.isEmpty)
        XCTAssertTrue(transport.requested.isEmpty)
        XCTAssertEqual(store.message(id: "x", peerId: "alice")?.media?.map(\.state), [.uploading, .failed])
    }

    func testReconcileResetsFailureCounter() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        for _ in 0..<4 { await complete("\(id):0", status: 500) }
        // 最后一次退避重排出来的任务也结束了(比如进程被挂起期间系统报错)
        for task in session.created { task.finished = true }

        await uploader.reconcile()
        await complete("\(id):0", status: 500)

        XCTAssertEqual(state(id), .uploading, "对账后计数从零开始")
    }

    // MARK: - cancel

    func testCancelCancelsTasksBeforeFileDeletion() async throws {
        let id = try await seal(2)
        await uploader.enqueue(messageId: id).value
        try setState(id, 1, .uploading)
        let other = try await seal()
        await uploader.enqueue(messageId: other).value
        // 上个进程留下的同消息任务(本进程没有它的句柄)也要取消。
        let leftover = session.addPreexisting("\(id):1")
        leftover.finished = false
        var ccaExistedAtCancel: [String: Bool] = [:]
        let files = self.files!
        session.onCancel = { task in
            let description = task.taskDescription ?? "?"
            let index = Int(description.split(separator: ":").last ?? "") ?? -1
            ccaExistedAtCancel[description] = files.exists(files.ccaURL(messageId: id, index: index))
        }
        let cleaner = ThreadCleaner(chatStore: store, mediaFiles: files,
                                    cancelUploads: { [uploader] in await uploader!.cancel(messageIds: $0) })

        try await cleaner.deleteMessage(id: id, peerId: "alice")

        XCTAssertEqual(ccaExistedAtCancel.count, 2, "本进程的任务与遗留任务都取消")
        XCTAssertTrue(ccaExistedAtCancel.values.allSatisfy { $0 }, "先取消任务,再删文件")
        XCTAssertFalse(files.exists(files.directory(messageId: id)))
        XCTAssertNil(store.message(id: id, peerId: "alice"))
        XCTAssertEqual(liveDescriptions, ["\(other):0"], "别的消息不受影响")
        XCTAssertTrue(leftover.cancelled)

        // 取消回调迟到:什么都不写回。
        let follow = uploader.handleCompletion(taskDescription: "\(id):0", httpStatus: nil, error: URLError(.cancelled))
        XCTAssertNil(follow)
        XCTAssertNil(store.message(id: id, peerId: "alice"))
    }

    func testClearThreadCancelsEveryMessageBeforeDeletingFiles() async throws {
        let a = try await seal()
        let b = try await seal()
        await uploader.enqueue(messageId: a).value
        await uploader.enqueue(messageId: b).value
        var cancelledWithFile: [Bool] = []
        let files = self.files!
        session.onCancel = { task in
            let id = String((task.taskDescription ?? "").split(separator: ":").first ?? "")
            cancelledWithFile.append(files.exists(files.ccaURL(messageId: id, index: 0)))
        }
        let cleaner = ThreadCleaner(chatStore: store, mediaFiles: files,
                                    cancelUploads: { [uploader] in await uploader!.cancel(messageIds: $0) })

        try await cleaner.clear(peerId: "alice")

        XCTAssertEqual(cancelledWithFile, [true, true])
        XCTAssertTrue(liveDescriptions.isEmpty)
        XCTAssertFalse(files.exists(files.directory(messageId: a)))
        XCTAssertFalse(files.exists(files.directory(messageId: b)))
    }

    func testClearThreadQueriesSessionOnceForAllMessages() async throws {
        let ids = [try await seal(), try await seal(), try await seal()]
        for id in ids { await uploader.enqueue(messageId: id).value }
        let before = session.allTasksCalls
        let cleaner = ThreadCleaner(chatStore: store, mediaFiles: files,
                                    cancelUploads: { [uploader] in await uploader!.cancel(messageIds: $0) })

        try await cleaner.clear(peerId: "alice")

        XCTAssertEqual(session.allTasksCalls - before, 1, "清空会话只问一次后台会话")
        XCTAssertTrue(liveDescriptions.isEmpty)
        for id in ids { XCTAssertFalse(files.exists(files.directory(messageId: id))) }
    }

    func testCompletionAfterDeletionWritesNothing() async throws {
        let id = try await seal()
        await uploader.enqueue(messageId: id).value
        try store.remove(id: id, peerId: "alice")

        let follow = await complete("\(id):0", status: 500)

        XCTAssertNil(follow)
        XCTAssertTrue(sleeps.isEmpty, "消息没了不再重排")
        XCTAssertNil(store.message(id: id, peerId: "alice"))
    }
}
