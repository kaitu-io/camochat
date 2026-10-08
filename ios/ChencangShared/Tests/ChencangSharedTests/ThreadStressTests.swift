import XCTest
import Combine
@testable import ChencangShared

/// 终审 F2 / I5:线程在连续发语音、进度狂刷时不能让主 actor 饿死,也不能让整条线程跟着每次进度失效。
///
/// 为什么在这一层测:F2 的死循环发生在 SwiftUI 的 LazyVStack 布局里,SwiftPM 的 macOS 单测宿主不了
/// App 目标里的 UIKit/SwiftUI 线程屏;这里钉住的是喂给那张屏的**失效源**——进度发布频率(节流 ≤ 10 Hz)、
/// 进度只落在各自条目的 `MediaProgress` 上(`MediaActivity` 与 `ChatStore` 不因进度发布)、以及在
/// 40+ 条消息的线程里连发 10 条语音时主 actor 始终能按时调度。布局本身由模拟器上的 XCUITest
/// `UATThreadStressTests`(DEBUG 压力钩子)与真机 `UATSendTests.test12VoiceBurstBackToBack` 覆盖。
@MainActor
final class ThreadStressTests: XCTestCase {
    private var base: URL!
    private var store: ChatStore!
    private var files: MediaFiles!
    private var activity: MediaActivity!
    private var bag: Set<AnyCancellable> = []

    override func setUp() async throws {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-stress-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: base.appendingPathComponent("chat"))
        files = MediaFiles(root: base.appendingPathComponent("media"))
        activity = MediaActivity()
        bag = []
    }

    override func tearDown() async throws {
        bag = []
        try? FileManager.default.removeItem(at: base)
    }

    // MARK: - 节流

    func testThrottleEmitsFirstThenAtMostEveryHundredMillisAndAlwaysTheEnd() {
        final class Clock: @unchecked Sendable { var t: TimeInterval = 0 }
        let clock = Clock()
        let throttle = ProgressThrottle(now: { clock.t })
        var emitted: [Double] = []
        for i in 1...1_000 {
            clock.t = Double(i) * 0.001            // 1 kHz 回调,共 1 秒
            let v = Double(i) / 1_000
            if throttle.shouldEmit(v) { emitted.append(v) }
        }
        XCTAssertEqual(emitted.first, 0.001, "第一次必放行")
        XCTAssertEqual(emitted.last, 1, "传完那一下必放行")
        XCTAssertLessThanOrEqual(emitted.count, 12, "1 秒内 ≤ 10 Hz(+ 首尾)")
        XCTAssertFalse(throttle.shouldEmit(1), "传完之后的迟到回调不再放行")
    }

    // MARK: - 进度的失效范围

    func testProgressOnlyInvalidatesItsOwnItem() {
        var activityChanges = 0
        activity.objectWillChange.sink { activityChanges += 1 }.store(in: &bag)
        let a = activity.progress(for: MediaActivity.key(messageId: "m1", index: 0))
        let b = activity.progress(for: MediaActivity.key(messageId: "m2", index: 0))
        var aChanges = 0, bChanges = 0
        a.objectWillChange.sink { aChanges += 1 }.store(in: &bag)
        b.objectWillChange.sink { bChanges += 1 }.store(in: &bag)

        for v in stride(from: 0.1, through: 1.0, by: 0.1) {
            activity.setProgress(v, messageId: "m1", index: 0)
        }
        activity.setProgress(1.0, messageId: "m1", index: 0)   // 相同值不重复发布
        activity.clearProgress(messageId: "m1", index: 0)

        XCTAssertEqual(activityChanges, 0, "进度不再让 MediaActivity(整条线程都订阅它)失效")
        XCTAssertEqual(aChanges, 11, "10 次不同值 + 1 次清除")
        XCTAssertEqual(bChanges, 0, "别的条目不受影响")
        XCTAssertNil(activity.progressValue(messageId: "m1", index: 0))
        XCTAssertTrue(a === activity.progress(for: MediaActivity.key(messageId: "m1", index: 0)),
                      "同一 key 始终是同一份对象,订阅它的进度环才收得到后续变化")
    }

    // MARK: - 线程时间胶囊与行

    func testRowsFoldTimeChipIntoOneRowPerMessage() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let messages = [0, 60, 60 * 12, 60 * 13].enumerated().map { i, offset in
            ChatMessage(id: "m\(i)", peerId: "p", direction: .incoming, body: "x",
                        timestamp: t0.addingTimeInterval(TimeInterval(offset)), status: .received)
        }
        let rows = ThreadFormat.rows(messages)
        XCTAssertEqual(rows.map(\.id), ["m0", "m1", "m2", "m3"], "每条消息恰好一行,id 就是消息 id")
        XCTAssertEqual(rows.map(\.showsTimeChip), [true, false, true, false])
    }

    func testIncomingFailedShowsDownloadRetryOutgoingDoesNot() {
        XCTAssertTrue(MediaLayout.showsDownloadRetry(direction: .incoming, state: .failed))
        XCTAssertFalse(MediaLayout.showsDownloadRetry(direction: .outgoing, state: .failed), "发送失败走原来的红 !")
        for state: MediaItem.State in [.pending, .downloading, .ready, .expired, .corrupt] {
            XCTAssertFalse(MediaLayout.showsDownloadRetry(direction: .incoming, state: state))
        }
    }

    // MARK: - 压力:40+ 条消息的线程里背靠背连发 10 条语音

    func testTenVoiceNotesBackToBackInLongThreadDoNotStarveMainActor() async throws {
        // 40 条混合历史:文字 + 已下载的图片
        let t0 = Date(timeIntervalSince1970: 1_000)
        for i in 0..<44 {
            let isMedia = i % 4 == 0
            try store.append(ChatMessage(
                id: "h\(i)", peerId: "alice", direction: i % 2 == 0 ? .incoming : .outgoing,
                body: isMedia ? "" : "历史消息 \(i)", timestamp: t0.addingTimeInterval(TimeInterval(i * 30)),
                status: i % 2 == 0 ? .received : .shared, kind: isMedia ? .image : .text,
                media: isMedia ? [MediaItem(index: 0, kind: .image, durMs: 0, width: 800, height: 600, byteLen: 1,
                                            blobSecret: Data(repeating: 1, count: 32), blobId: "b\(i)", state: .ready)]
                               : nil))
        }
        let transport = TickingTransport(ticks: 400)
        var counter = 0
        let sender = MediaSender(store: store, crypto: EchoThreadCrypto(), mediaCrypto: CountingMediaCrypto(),
                                 transport: transport, files: files, activity: activity,
                                 uploadScheduler: { _ in },
                                 now: { t0.addingTimeInterval(10_000) },
                                 newId: { counter += 1; return "v\(counter)" })

        var storeChanges = 0, activityChanges = 0
        store.objectWillChange.sink { storeChanges += 1 }.store(in: &bag)
        activity.objectWillChange.sink { activityChanges += 1 }.store(in: &bag)
        var progressChanges = 0
        for i in 1...10 {
            activity.progress(for: MediaActivity.key(messageId: "v\(i)", index: 0))
                .objectWillChange.sink { progressChanges += 1 }.store(in: &bag)
        }

        // 主 actor 心跳:每 5 ms 醒一次,记录最大间隔
        let heartbeat = Heartbeat()
        let beat = Task { @MainActor in
            var last = ProcessInfo.processInfo.systemUptime
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000)
                let now = ProcessInfo.processInfo.systemUptime
                heartbeat.maxGap = max(heartbeat.maxGap, now - last)
                last = now
            }
        }

        let start = ProcessInfo.processInfo.systemUptime
        // 与界面一致:每松手一次起一个 Task 发送,彼此不等
        let outcomes = await withTaskGroup(of: MediaSender.Outcome.self) { group -> [MediaSender.Outcome] in
            for i in 0..<10 {
                let voice = PreparedMedia(kind: .voice, plaintext: Data(repeating: UInt8(i), count: 15_000),
                                          durMs: 5_000, width: 0, height: 0)
                // 先封缄(可分享),再由上传引擎传:这里直接顺序调 upload 模拟调度器
                group.addTask {
                    let outcome = await sender.send(to: "alice", media: [voice])
                    if case let .sealed(id, _) = outcome { _ = await sender.upload(messageId: id) }
                    return outcome
                }
            }
            var all: [MediaSender.Outcome] = []
            for await outcome in group { all.append(outcome) }
            return all
        }
        let elapsed = ProcessInfo.processInfo.systemUptime - start
        // 让还在路上的进度回调跑完再统计
        try await Task.sleep(nanoseconds: 50_000_000)
        beat.cancel()

        XCTAssertEqual(outcomes.filter { if case .sealed = $0 { return true } else { return false } }.count, 10)
        XCTAssertEqual(store.messages(for: "alice").count, 54)
        XCTAssertLessThan(elapsed, 10, "10 条语音应在 10 秒内全部封缄,实际 \(elapsed) 秒")
        XCTAssertLessThan(heartbeat.maxGap, 0.5, "主 actor 心跳最大间隔 \(heartbeat.maxGap) 秒:被饿住了")
        XCTAssertEqual(activityChanges, 0, "进度与状态变化都不经过 MediaActivity")
        // 每条语音:落库 1 + encrypting 1 + uploading 1 + sealed 1 + 写 body 1 = 5 次左右,与进度次数无关
        XCTAssertLessThanOrEqual(storeChanges, 10 * 6, "ChatStore 发布次数 \(storeChanges) 与进度跳动次数无关")
        // 400 次进度回调/条 × 10 条 = 4000;节流后每条 ≤ 首次 + 每 100 ms 一次 + 结尾 + 清除
        let perItemBudget = Int(elapsed / ProgressThrottle.defaultMinInterval) + 4
        XCTAssertLessThanOrEqual(progressChanges, 10 * perItemBudget,
                                 "进度发布 \(progressChanges) 次,4000 次回调没被节流")
        XCTAssertEqual(transport.totalTicks, 4_000)
    }
}

@MainActor
private final class Heartbeat {
    var maxGap: TimeInterval = 0
}

/// 上传时像 URLSession 一样在后台线程密集回调进度(每条 `ticks` 次)。
private final class TickingTransport: MediaTransporting, @unchecked Sendable {
    let ticks: Int
    private let lock = NSLock()
    private var _totalTicks = 0
    var totalTicks: Int { lock.withLock { _totalTicks } }

    init(ticks: Int) { self.ticks = ticks }

    func requestUploadUrl(blobId: String, byteLen: Int, kind: MediaKind) async throws -> URL {
        URL(string: "https://upload.test/\(blobId)")!
    }

    func upload(url: URL, body: Data, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        for i in 1...ticks {
            onProgress(Double(i) / Double(ticks))
            lock.withLock { _totalTicks += 1 }
            if i % 40 == 0 { try? await Task.sleep(nanoseconds: 2_000_000) }
        }
    }

    func download(blobId: String, onProgress: @escaping @Sendable (Double) -> Void) async throws -> Data {
        throw MediaTransportError.gone
    }
}
