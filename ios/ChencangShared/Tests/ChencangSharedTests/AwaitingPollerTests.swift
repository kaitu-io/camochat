import XCTest
@testable import ChencangShared

/// 虚拟时间:`sleep` 登记一个唤醒点,`advance` 按时间顺序逐个唤醒。取消时立即返回(同 `Task.sleep`)。
@MainActor
final class VirtualTime {
    private(set) var now = Date(timeIntervalSince1970: 1_000_000)
    let origin = Date(timeIntervalSince1970: 1_000_000)
    private var sleepers: [(wake: Date, id: Int, cont: CheckedContinuation<Void, Never>)] = []
    private var nextId = 0

    var elapsed: TimeInterval { now.timeIntervalSince(origin) }

    func sleep(_ interval: TimeInterval) async {
        nextId += 1
        let id = nextId
        let wake = now.addingTimeInterval(interval)
        await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                if Task.isCancelled { cont.resume(); return }
                sleepers.append((wake, id, cont))
            }
        } onCancel: {
            Task { @MainActor in self.cancel(id) }
        }
    }

    private func cancel(_ id: Int) {
        guard let i = sleepers.firstIndex(where: { $0.id == id }) else { return }
        sleepers.remove(at: i).cont.resume()
    }

    /// 让被唤醒的任务跑到下一个挂起点。
    func settle() async {
        for _ in 0..<200 { await Task.yield() }
    }

    func advance(by interval: TimeInterval) async {
        let target = now.addingTimeInterval(interval)
        await settle()
        while let next = sleepers.filter({ $0.wake <= target })
            .min(by: { ($0.wake, $0.id) < ($1.wake, $1.id) }) {
            sleepers.removeAll { $0.id == next.id }
            now = next.wake
            next.cont.resume()
            await settle()
        }
        now = target
        await settle()
    }
}

@MainActor
final class FetchLog {
    var calls: [(id: String, index: Int, at: TimeInterval)] = []
    func times(_ id: String, _ index: Int = 0) -> [TimeInterval] {
        calls.filter { $0.id == id && $0.index == index }.map(\.at)
    }
}

@MainActor
final class AwaitingPollerTests: XCTestCase {
    private var time: VirtualTime!
    private var log: FetchLog!
    private var awaiting: Set<String> = []
    private var poller: AwaitingPoller!

    override func setUp() async throws {
        time = VirtualTime()
        log = FetchLog()
        awaiting = ["m1#0", "m2#0", "m3#0", "vid#0"]
        let time = self.time!
        let log = self.log!
        poller = AwaitingPoller(
            clock: { time.now.timeIntervalSince1970 },
            sleeper: { await time.sleep($0) },
            tryFetch: { id, index in
                await MainActor.run { log.calls.append((id, index, time.elapsed)) }
            },
            isAwaiting: { [unowned self] id, index in self.awaiting.contains("\(id)#\(index)") }
        )
    }

    override func tearDown() async throws {
        poller.onHidden()
        await time.settle()
    }

    func testConstantsMatchSpec() {
        XCTAssertEqual(AwaitingPoller.delays, [10, 20, 30, 60])
        XCTAssertEqual(AwaitingPoller.window, 1800)
    }

    /// 可见后立即试一次,之后 10 s、20 s、30 s、60 s,再往后恒 60 s(累计 0/10/30/60/120/180…)。
    func testPollerSchedule() async {
        poller.onVisible([("m1", 0)])
        await time.advance(by: 200)
        XCTAssertEqual(log.times("m1"), [0, 10, 30, 60, 120, 180])
        XCTAssertFalse(poller.windowExpired("m1", 0))
    }

    /// 30 分钟后停止自动轮询,气泡改「还没收到文件 · 点击重试」。
    func testPollerStopsAfterWindow() async {
        poller.onVisible([("m1", 0)])
        await time.advance(by: 1_799)
        XCTAssertFalse(poller.windowExpired("m1", 0))
        let beforeWindow = log.times("m1")
        XCTAssertEqual(beforeWindow.last, 1_740)
        XCTAssertEqual(beforeWindow.count, 4 + 28)

        await time.advance(by: 1)
        XCTAssertTrue(poller.windowExpired("m1", 0))
        await time.advance(by: 3_600)
        XCTAssertEqual(log.times("m1"), beforeWindow, "窗口过后不再请求")
    }

    /// 会话不可见 / App 进后台:全部停止。
    func testPollerStopsWhenHidden() async {
        poller.onVisible([("m1", 0), ("m2", 0)])
        await time.advance(by: 15)
        XCTAssertEqual(log.times("m1"), [0, 10])
        poller.onHidden()
        await time.advance(by: 3_600)
        XCTAssertEqual(log.times("m1"), [0, 10])
        XCTAssertEqual(log.times("m2"), [0, 10])

        poller.track([("m3", 0)])
        await time.advance(by: 600)
        XCTAssertEqual(log.times("m3"), [], "不可见时新进入等待的项也不轮询")
    }

    /// 再次可见(进会话 / 回前台 / 点「点击重试」):立即试一次并重开 30 分钟窗口。
    func testPollerRestartsOnVisible() async {
        poller.onVisible([("m1", 0)])
        await time.advance(by: 1_800)
        XCTAssertTrue(poller.windowExpired("m1", 0))
        let count = log.calls.count

        await time.advance(by: 200)   // t = 2000
        poller.onVisible([("m1", 0)])
        XCTAssertFalse(poller.windowExpired("m1", 0))
        await time.advance(by: 10)
        XCTAssertEqual(Array(log.times("m1").suffix(2)), [2_000, 2_010])
        XCTAssertEqual(log.calls.count, count + 2)

        await time.advance(by: 1_790)   // t = 3800 = 重开窗口 + 1800
        XCTAssertTrue(poller.windowExpired("m1", 0))
    }

    /// 点某一项重试只重开这一项,其它项的节奏不受影响(相册各项独立)。
    func testRetryOneItemLeavesOthersAlone() async {
        poller.onVisible([("m1", 0), ("m2", 0)])
        await time.advance(by: 15)
        poller.onVisible([("m2", 0)])
        await time.advance(by: 20)   // t = 35
        XCTAssertEqual(log.times("m1"), [0, 10, 30])
        XCTAssertEqual(log.times("m2"), [0, 10, 15, 25])
    }

    /// 拿到了(不再是 awaiting)就不再请求这一项;同一相册里其它项照常轮询。
    func testPollerStopsItemOnceNoLongerAwaiting() async {
        poller.onVisible([("m1", 0), ("m1", 1)])
        awaiting.insert("m1#1")
        await time.advance(by: 15)
        awaiting.remove("m1#0")
        await time.advance(by: 100)
        XCTAssertEqual(log.times("m1", 0), [0, 10])
        XCTAssertEqual(log.times("m1", 1), [0, 10, 30, 60])
        XCTAssertFalse(poller.windowExpired("m1", 0))
    }

    /// 刚请求过、刚进入等待的项(自动下载/点视频之后):不立即再试,从 10 s 起轮询。
    func testTrackStartsScheduleWithoutImmediateFetch() async {
        poller.onVisible([])
        await time.advance(by: 5)
        poller.track([("m1", 0)])
        poller.track([("m1", 0)])   // 重复登记不重开窗口、不多请求
        await time.advance(by: 30)   // t = 35
        XCTAssertEqual(log.times("m1"), [15, 35])
    }

    /// 视频维持点了才下:pending 的视频不在轮询目标里;点了、得到 awaiting 后才进入轮询。
    func testVideoAwaitingPollsOnlyAfterTap() async throws {
        func item(_ kind: MediaKind, _ state: MediaItem.State) -> MediaItem {
            MediaItem(index: 0, kind: kind, durMs: 1_000, width: 1, height: 1, byteLen: 10,
                      blobSecret: Data(), blobId: "b", state: state)
        }
        func message(_ id: String, _ direction: ChatMessage.Direction, _ media: MediaItem) -> ChatMessage {
            ChatMessage(id: id, peerId: "alice", direction: direction, body: "", timestamp: time.now,
                        status: direction == .incoming ? .received : .shared, kind: media.kind.messageKind, media: [media])
        }
        var messages = [
            message("m1", .incoming, item(.voice, .awaiting)),
            message("vid", .incoming, item(.video, .pending)),
            message("m2", .incoming, item(.image, .ready)),
            message("out", .outgoing, item(.image, .uploading)),
        ]
        XCTAssertEqual(AwaitingPoller.targets(in: messages).map(\.0), ["m1"])

        poller.onVisible(AwaitingPoller.targets(in: messages))
        await time.advance(by: 60)
        XCTAssertEqual(log.times("vid"), [], "没点过的视频不自动请求")

        // 用户点了视频:下载得到 403 → awaiting,随后进入轮询。
        messages[1] = message("vid", .incoming, item(.video, .awaiting))
        poller.track(AwaitingPoller.targets(in: messages))
        await time.advance(by: 30)   // t = 90
        XCTAssertEqual(log.times("vid"), [70, 90])
        XCTAssertEqual(log.times("m1"), [0, 10, 30, 60], "已在轮询的项不被重开")

        // 离开后再进会话:等待中的视频(一定是点过的)同样立即再试。
        poller.onHidden()
        poller.onVisible(AwaitingPoller.targets(in: messages))
        await time.settle()
        XCTAssertEqual(log.times("vid").last, 90)
        XCTAssertEqual(log.times("vid").count, 3)
    }

    /// N2:循环醒来时这一项正处在下载中(比如点重试那次刚切过去)→ 照常继续,不判停;
    /// 那次失败退回 awaiting 后下一轮再试。
    func testLoopWakingDuringDownloadingKeepsPolling() async {
        var states: [String: MediaItem.State] = ["m1": .awaiting]
        let time = self.time!
        let log = self.log!
        let poller = AwaitingPoller(
            clock: { time.now.timeIntervalSince1970 },
            sleeper: { await time.sleep($0) },
            tryFetch: { id, index in await MainActor.run { log.calls.append((id, index, time.elapsed)) } },
            isAwaiting: { id, _ in AwaitingPoller.keepsPolling(states[id]) }
        )
        defer { poller.onHidden() }
        poller.onVisible([])
        poller.track([("m1", 0)])
        await time.advance(by: 5)
        states["m1"] = .downloading
        await time.advance(by: 10)   // t = 15:10 s 那一轮醒来时是下载中
        states["m1"] = .awaiting
        await time.advance(by: 20)   // t = 35
        XCTAssertEqual(log.times("m1"), [10, 30])
        states["m1"] = .ready
        await time.advance(by: 600)
        XCTAssertEqual(log.times("m1"), [10, 30], "拿到后停止")
    }

    // MARK: - UAT O4:30 分钟窗口必须按时收口

    /// 在途的那次再试迟迟不回(弱网连不上 / 排在下载并发闸后面):窗口照样在 30 分钟整收口;
    /// 那次很晚才回(仍是 403)也不再开新的一轮。
    func testWindowClosesOnTimeWhileARetryIsStillInFlight() async {
        let time = self.time!
        let log = self.log!
        let gate = FetchGate()
        let poller = AwaitingPoller(
            clock: { time.now.timeIntervalSince1970 },
            sleeper: { await time.sleep($0) },
            tryFetch: { id, index in
                let n = await MainActor.run { () -> Int in
                    log.calls.append((id, index, time.elapsed))
                    return log.calls.count
                }
                if n > 2 { await gate.wait() }
            },
            isAwaiting: { _, _ in true }
        )
        defer { poller.onHidden() }
        poller.onVisible([("m1", 0)])
        await time.advance(by: 1_799)
        XCTAssertFalse(poller.windowExpired("m1", 0))
        await time.advance(by: 1)
        XCTAssertTrue(poller.windowExpired("m1", 0), "在途的那次还没回,窗口照样收口")
        XCTAssertEqual(log.times("m1"), [0, 10, 30])

        gate.open()
        await time.settle()
        await time.advance(by: 3_600)
        XCTAssertEqual(log.times("m1"), [0, 10, 30], "晚回的那次之后不再请求")
        XCTAssertTrue(poller.windowExpired("m1", 0))
    }

    /// 时钟被往回拨(自动校时 / 用户改时间)不能把窗口拉长。
    func testClockJumpingBackwardsDoesNotStretchTheWindow() async {
        let time = self.time!
        let log = self.log!
        var offset: TimeInterval = 0
        let poller = AwaitingPoller(
            clock: { time.now.timeIntervalSince1970 - offset },
            sleeper: { await time.sleep($0) },
            tryFetch: { id, index in await MainActor.run { log.calls.append((id, index, time.elapsed)) } },
            isAwaiting: { _, _ in true }
        )
        defer { poller.onHidden() }
        poller.onVisible([("m1", 0)])
        await time.advance(by: 100)
        offset = 600
        await time.advance(by: 1_700)   // t = 1800
        XCTAssertTrue(poller.windowExpired("m1", 0))
        let before = log.times("m1")
        await time.advance(by: 1_200)
        XCTAssertEqual(log.times("m1"), before)
    }

    /// 线程一直可见:收集器反复重发同一批等待项(状态来回变、别的消息进来都会重发)不重开窗口;
    /// 窗口过后的重发也不重开。
    func testRepeatedTrackNeverReopensTheWindow() async {
        poller.onVisible([("m1", 0)])
        var t: TimeInterval = 5
        while t < 1_800 {
            poller.track([("m1", 0)])
            await time.advance(by: 5)
            t += 5
        }
        await time.advance(by: 1_800 - time.elapsed)
        XCTAssertTrue(poller.windowExpired("m1", 0))
        let before = log.times("m1")
        for _ in 0..<3 { poller.track([("m1", 0)]) }
        await time.advance(by: 1_800)
        XCTAssertEqual(log.times("m1"), before)
        XCTAssertTrue(poller.windowExpired("m1", 0))
    }
}

/// 让某次再试一直挂着,直到测试放行。
@MainActor
final class FetchGate {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters = []
    }
}
