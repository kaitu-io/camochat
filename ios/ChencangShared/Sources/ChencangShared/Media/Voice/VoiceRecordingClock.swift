import Foundation
import CoreGraphics

/// 按住说话的时间规则(spec §3.2):< 1 秒「说话时间太短」;第 50 秒起倒计时;60 秒自动结束并发送。
/// 纯值类型,时间由调用方传入,便于单测。
public struct VoiceRecordingClock: Equatable, Sendable {
    /// spec §3.2:低于 1 秒判定「说话时间太短」;该阈值没有对应的 core/MediaLimits 上限,独立命名常量。
    public static let minDuration: TimeInterval = 1
    /// spec §3.2:第 50 秒起倒计时;同上,独立命名常量。
    public static let countdownFrom: TimeInterval = 50
    /// 与 `MediaLimits.maxDurationMs`(帧内 `dur_ms` 硬上限)同源,避免两处数字漂移。
    public static let maxDuration: TimeInterval = TimeInterval(MediaLimits.maxDurationMs) / 1_000

    /// 上滑超过语音浮层一半高度进入「松开 取消」区(与浮层尺寸同源,不另立数值)。
    public static var cancelSlideDistance: CGFloat { Moyu.Size.voiceOverlay / 2 }

    public enum Phase: Equatable, Sendable {
        case recording
        case countdown(secondsLeft: Int)
        case mustStop
    }

    public enum Outcome: Equatable, Sendable {
        case cancelled
        case tooShort
        case send
    }

    public let startedAt: Date

    public init(startedAt: Date) {
        self.startedAt = startedAt
    }

    public func phase(at now: Date) -> Phase {
        let elapsed = now.timeIntervalSince(startedAt)
        if elapsed >= Self.maxDuration { return .mustStop }
        if elapsed >= Self.countdownFrom {
            return .countdown(secondsLeft: Int((Self.maxDuration - elapsed).rounded(.up)))
        }
        return .recording
    }

    public func finish(at now: Date, cancelled: Bool) -> Outcome {
        if cancelled { return .cancelled }
        return now.timeIntervalSince(startedAt) < Self.minDuration ? .tooShort : .send
    }

    public static func isCancelSlide(translationHeight: CGFloat) -> Bool {
        translationHeight < -cancelSlideDistance
    }
}

/// 按住说话的「本次按压」令牌:按下时**同步**领一个令牌,松手即作废。
/// 录音器的异步启动(等权限框、建引擎)在真正 `engine.start()` 前核对令牌,
/// 作废就不开麦——手指在启动完成前就松开时,引擎不会被遗留在运行状态。
public struct VoiceSessionGate: Equatable, Sendable {
    private var generation = 0
    private var active: Int?

    public init() {}

    public mutating func press() -> Int {
        generation += 1
        active = generation
        return generation
    }

    public mutating func release() {
        active = nil
    }

    public func isCurrent(_ token: Int) -> Bool {
        active == token
    }
}
