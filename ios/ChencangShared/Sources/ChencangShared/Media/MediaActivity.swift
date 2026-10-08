import Foundation

/// 需要直接给用户看的错误(预处理拦截等)实现此协议。
public protocol UserFacingMediaError: Error {
    var userMessage: String { get }
}

/// 错误 → 提示文案(spec §5.1)。返回 nil 表示不弹提示(气泡上的红 ! / 已过期 / 已损坏 已足够)。
public enum MediaNotice {
    public static var rateLimited: String { L10n.mediaFailureRateLimited }
    public static var storageFull: String { L10n.mediaFailureStorageFull }
    /// 气泡上的「文件已损坏」同一句;语音解不开时也用它提示(终审 minor 7)。
    public static var corruptFile: String { L10n.mediaFailureCorrupt }
    public static var forwardFailed: String { L10n.mediaForwardFailed }

    public static func text(for error: Error) -> String? {
        if let e = error as? UserFacingMediaError { return e.userMessage }
        if let e = error as? MediaTransportError {
            switch e {
            case .rateLimited: return rateLimited
            case .tooLarge: return MediaLayout.tooLargeText
            case .gone, .network, .server, .rejected: return nil
            }
        }
        if error is SessionStoreError { return L10n.mediaFailureSessionLost }
        let ns = error as NSError
        if ns.domain == NSCocoaErrorDomain && ns.code == NSFileWriteOutOfSpaceError { return storageFull }
        if ns.domain == NSPOSIXErrorDomain && ns.code == Int(ENOSPC) { return storageFull }
        return nil
    }
}

/// 单个媒体条目的传输进度(0…1;nil = 没在传)。每个条目一份,只有显示这一项进度环的那个
/// 小视图订阅它——一次进度跳动只让那一个环重绘,不再让整条线程重新求值(终审 F2c / I5)。
@MainActor
public final class MediaProgress: ObservableObject {
    @Published public private(set) var value: Double?

    init() {}

    func set(_ newValue: Double?) {
        guard newValue != value else { return }
        value = newValue
    }
}

/// 传输进度与一次性提示,线程 UI 订阅。进度只在内存,不落盘。
///
/// 进度**不是** `MediaActivity` 自己的 `@Published`:以前是一个共享的 `[key: Double]` 字典,
/// 每个媒体行和整条线程都订阅它,一次网络层进度回调就让整条线程失效重算——30 MB 视频
/// 能触发约 2000 次,正是 F2 线程布局死循环的放大器。现在每个条目一份 `MediaProgress`,
/// 本对象只发布 `notice`(提示框,很少变)。
@MainActor
public final class MediaActivity: ObservableObject {
    @Published public var notice: String?
    /// 不发布:取用(`progress(for:)`)时按需建,之后同一 key 一直是同一份对象,
    /// 订阅它的进度环才能收到后续变化。条目数量级很小(一条线程几十个媒体),不回收。
    private var models: [String: MediaProgress] = [:]

    public init() {}

    public static func key(messageId: String, index: Int) -> String { "\(messageId)#\(index)" }

    public func progress(for key: String) -> MediaProgress {
        if let model = models[key] { return model }
        let model = MediaProgress()
        models[key] = model
        return model
    }

    public func progressValue(messageId: String, index: Int) -> Double? {
        models[Self.key(messageId: messageId, index: index)]?.value
    }

    public func setProgress(_ value: Double, messageId: String, index: Int) {
        progress(for: Self.key(messageId: messageId, index: index)).set(value)
    }

    public func clearProgress(messageId: String, index: Int) {
        models[Self.key(messageId: messageId, index: index)]?.set(nil)
    }

    public func post(_ error: Error) {
        if let text = MediaNotice.text(for: error) { notice = text }
    }

    public func post(message: String) {
        notice = message
    }

    public func consumeNotice() {
        notice = nil
    }
}

/// 进度节流(终审 F2c / I5):网络层的进度回调可能每秒上百次,每次都起一个 MainActor
/// 任务既淹没主 actor 又让界面狂刷。一次传输配一个节流器,在**回调线程上**先判定,
/// 只有放行的那次才跳主 actor:第一次必放行,之后至少间隔 `minInterval`(默认 0.1 秒,
/// 即 ≤ 10 Hz),到 1(传完)那一下总放行。线程安全、`Sendable`。
public final class ProgressThrottle: @unchecked Sendable {
    public static let defaultMinInterval: TimeInterval = 0.1

    private let lock = NSLock()
    private let minInterval: TimeInterval
    private let now: @Sendable () -> TimeInterval
    private var lastEmit: TimeInterval?
    private var finished = false

    public init(minInterval: TimeInterval = ProgressThrottle.defaultMinInterval,
                now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.minInterval = minInterval
        self.now = now
    }

    public func shouldEmit(_ value: Double) -> Bool {
        lock.withLock {
            if finished { return false }
            let t = now()
            if value >= 1 {
                finished = true
                lastEmit = t
                return true
            }
            if let lastEmit, t - lastEmit < minInterval { return false }
            lastEmit = t
            return true
        }
    }
}
