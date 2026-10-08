import XCTest
@testable import ChencangShared

final class VoiceRecordingClockTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000)
    private var clock: VoiceRecordingClock { VoiceRecordingClock(startedAt: t0) }
    private func at(_ s: TimeInterval) -> Date { t0.addingTimeInterval(s) }

    func testTooShortUnderOneSecond() {
        XCTAssertEqual(clock.finish(at: at(0.99), cancelled: false), .tooShort)
        XCTAssertEqual(clock.finish(at: at(1.0), cancelled: false), .send)
    }

    func testCancelWins() {
        XCTAssertEqual(clock.finish(at: at(30), cancelled: true), .cancelled)
        XCTAssertEqual(clock.finish(at: at(0.2), cancelled: true), .cancelled)
    }

    func testCountdownFromFiftySecondsAndStopAtSixty() {
        XCTAssertEqual(clock.phase(at: at(49.9)), .recording)
        XCTAssertEqual(clock.phase(at: at(50)), .countdown(secondsLeft: 10))
        XCTAssertEqual(clock.phase(at: at(55.5)), .countdown(secondsLeft: 5))
        XCTAssertEqual(clock.phase(at: at(59.99)), .countdown(secondsLeft: 1))
        XCTAssertEqual(clock.phase(at: at(60)), .mustStop)
    }

    func testCancelSlideThreshold() {
        let d = VoiceRecordingClock.cancelSlideDistance
        XCTAssertTrue(VoiceRecordingClock.isCancelSlide(translationHeight: -(d + 1)))
        XCTAssertFalse(VoiceRecordingClock.isCancelSlide(translationHeight: -(d - 1)))
        XCTAssertFalse(VoiceRecordingClock.isCancelSlide(translationHeight: d + 50), "下滑不算取消")
    }

    func testLiftBeforeStartCompletesInvalidatesThePress() {
        var gate = VoiceSessionGate()
        let first = gate.press()
        XCTAssertTrue(gate.isCurrent(first))
        gate.release()                                  // 手指在异步启动完成前松开
        XCTAssertFalse(gate.isCurrent(first), "启动流程随后核对令牌,必须放弃开麦")

        let second = gate.press()                       // 立刻又按下
        XCTAssertFalse(gate.isCurrent(first), "旧的启动流程不能借新按压复活")
        XCTAssertTrue(gate.isCurrent(second))
    }

    func testFrameBufferEmitsWhole960SampleFramesAndPadsTail() {
        var buffer = VoiceFrameBuffer()
        XCTAssertEqual(buffer.append([Float](repeating: 1, count: 500)).count, 0)
        let frames = buffer.append([Float](repeating: 1, count: 1_500))   // 累计 2000 → 2 帧,余 80
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames.map(\.count), [960, 960])
        let tail = buffer.flush()
        XCTAssertEqual(tail?.count, 960)
        XCTAssertEqual(tail?.prefix(80).allSatisfy { $0 == 1 }, true)
        XCTAssertEqual(tail?.suffix(880).allSatisfy { $0 == 0 }, true)
        XCTAssertNil(buffer.flush())
    }
}
