import XCTest
@testable import ChencangShared

/// 终审复审的两处并发修复:放音激活的作废令牌(复审 1)、转码一路失败时不让另一路挂死(复审 2)。
final class GatedSerialQueueTests: XCTestCase {
    /// 复现场景:放音激活排在队列里(前面有一块正在执行的活),这时用户按下录音 → 令牌作废 →
    /// 录音的激活排在后面。放音那块轮到执行时必须跳过,不能把会话切回 .playback。
    func testCancelledWhileQueuedSkipsWorkAndLaterWorkStillRuns() async throws {
        let queue = GatedSerialQueue(label: "test.gated")
        let log = Log()
        let gate = DispatchSemaphore(value: 0)
        queue.async { gate.wait() }                        // 占住队列

        let flag = CancellationFlag()
        async let playback = queue.run(unlessCancelled: flag) { log.append("playback") }
        try await Task.sleep(nanoseconds: 50_000_000)      // 确保放音那块已经排进队列
        flag.cancel()                                      // 按下录音:先作废放音
        async let record: Void = queue.run { log.append("playAndRecord") }
        gate.signal()

        let activated = await playback
        try await record
        XCTAssertFalse(activated, "作废后的放音激活不应执行")
        XCTAssertEqual(log.entries, ["playAndRecord"], "只有录音的激活碰了会话")
    }

    func testCancelledBeforeQueueingNeverEnqueues() async {
        let queue = GatedSerialQueue(label: "test.gated")
        let flag = CancellationFlag()
        flag.cancel()
        let log = Log()
        let ran = await queue.run(unlessCancelled: flag) { log.append("x") }
        XCTAssertFalse(ran)
        XCTAssertEqual(log.entries, [])
    }

    func testLiveFlagRunsAndReportsThrowAsFalse() async {
        let queue = GatedSerialQueue(label: "test.gated")
        let log = Log()
        let ok = await queue.run(unlessCancelled: CancellationFlag()) { log.append("ok") }
        XCTAssertTrue(ok)
        struct Boom: Error {}
        let failed = await queue.run(unlessCancelled: CancellationFlag()) { throw Boom() }
        XCTAssertFalse(failed)
        XCTAssertEqual(log.entries, ["ok"])
    }
}

final class TranscodeCoordinatorTests: XCTestCase {
    /// 一路 append 失败、另一路永远等不到回调(被取消的 writer 输入不会再叫它):
    /// 必须先取消 reader/writer,再把另一路直接收掉,整体返回失败,而不是挂死。
    func testFirstFailureCancelsAndReleasesTheStuckStage() async {
        let failing = FakeStage(behaviour: .failAfter(0.02))
        let stuck = FakeStage(behaviour: .never)
        let cancels = Counter()
        let ok = await withTimeout(seconds: 5) {
            await TranscodeCoordinator.runAll([failing, stuck]) { cancels.increment() }
        }
        XCTAssertEqual(ok, false, "应在超时前以失败返回")
        XCTAssertEqual(cancels.value, 1, "reader/writer 只取消一次")
        XCTAssertTrue(stuck.aborted, "卡住的那一路被收掉")
        XCTAssertFalse(failing.aborted)
    }

    func testAllSucceedDoesNotCancel() async {
        let cancels = Counter()
        let ok = await TranscodeCoordinator.runAll([FakeStage(behaviour: .succeedAfter(0.01)),
                                                     FakeStage(behaviour: .succeedAfter(0.02))]) { cancels.increment() }
        XCTAssertTrue(ok)
        XCTAssertEqual(cancels.value, 0)
    }

    func testBothFailingCancelsOnceAndIgnoresLateReports() async {
        let cancels = Counter()
        let a = FakeStage(behaviour: .failAfter(0.01))
        let b = FakeStage(behaviour: .failAfter(0.05))    // 已被收掉后才迟到地报失败:应被忽略
        let ok = await TranscodeCoordinator.runAll([a, b]) { cancels.increment() }
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertFalse(ok)
        XCTAssertEqual(cancels.value, 1)
    }

    /// writer 在某一路等 isReadyForMoreMediaData 时自己失败(比如并行跑时编码器会话被系统收回):
    /// 所有路都不会再有回调。健康检查发现 reader/writer 失败 → 取消一次、把所有卡住的路收掉、返回失败。
    func testUnhealthySessionReleasesEveryStuckStage() async {
        let a = FakeStage(behaviour: .never)
        let b = FakeStage(behaviour: .never)
        let healthy = Flag()
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) { healthy.clear() }
        let cancels = Counter()
        let ok = await TranscodeCoordinator.runAll([a, b], healthPollInterval: 0.02,
                                                   isHealthy: { healthy.value }) { cancels.increment() }
        XCTAssertFalse(ok)
        XCTAssertEqual(cancels.value, 1)
        XCTAssertTrue(a.aborted)
        XCTAssertTrue(b.aborted)
    }

    func testHealthySessionWithHealthCheckStillSucceeds() async {
        let cancels = Counter()
        let ok = await TranscodeCoordinator.runAll([FakeStage(behaviour: .succeedAfter(0.05))],
                                                   healthPollInterval: 0.01, isHealthy: { true }) { cancels.increment() }
        XCTAssertTrue(ok)
        XCTAssertEqual(cancels.value, 0)
    }

    private func withTimeout<T: Sendable>(seconds: Double, _ work: @escaping @Sendable () async -> T) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { await work() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}

private final class FakeStage: TranscodeStage, @unchecked Sendable {
    enum Behaviour { case succeedAfter(Double), failAfter(Double), never }
    private let behaviour: Behaviour
    private let lock = NSLock()
    private var _aborted = false
    var aborted: Bool { lock.withLock { _aborted } }

    init(behaviour: Behaviour) { self.behaviour = behaviour }

    func start(_ done: @escaping @Sendable (Bool) -> Void) {
        switch behaviour {
        case let .succeedAfter(delay):
            DispatchQueue.global().asyncAfter(deadline: .now() + delay) { done(true) }
        case let .failAfter(delay):
            DispatchQueue.global().asyncAfter(deadline: .now() + delay) { done(false) }
        case .never:
            break
        }
    }

    func abort() { lock.withLock { _aborted = true } }
}

private final class Log: @unchecked Sendable {
    private let lock = NSLock()
    private var _entries: [String] = []
    var entries: [String] { lock.withLock { _entries } }
    func append(_ s: String) { lock.withLock { _entries.append(s) } }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = 0
    var value: Int { lock.withLock { _value } }
    func increment() { lock.withLock { _value += 1 } }
}

private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = true
    var value: Bool { lock.withLock { _value } }
    func clear() { lock.withLock { _value = false } }
}
