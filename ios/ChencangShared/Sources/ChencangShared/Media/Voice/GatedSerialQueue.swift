import Foundation

/// 可作废的令牌(线程安全):一次放音请求一份,换条/停止/按下录音时作废。
public final class CancellationFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false

    public init() {}

    public func cancel() { lock.withLock { cancelled = true } }
    public var isCancelled: Bool { lock.withLock { cancelled } }
}

/// 串行后台队列,支持「令牌作废就不做」(终审复审 1):AVAudioSession 的激活/停用排在这条队列上。
/// 放音的激活可能在队列里排着的时候,用户已经按下了录音——录音的 `.playAndRecord` 激活排在后面,
/// 如果放音那块照样执行,会把会话切回 `.playback`。所以排队前查一次令牌,轮到执行、真正碰会话前再查一次。
public final class GatedSerialQueue: @unchecked Sendable {
    private let queue: DispatchQueue

    public init(label: String) {
        queue = DispatchQueue(label: label, qos: .userInitiated)
    }

    /// 在队列上执行并等结果。
    public func run(_ work: @escaping @Sendable () throws -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async { continuation.resume(with: Result { try work() }) }
        }
    }

    /// 令牌仍有效才执行:排队前、执行前各查一次。返回是否真的执行成功(作废或抛错都是 false)。
    public func run(unlessCancelled flag: CancellationFlag,
                    _ work: @escaping @Sendable () throws -> Void) async -> Bool {
        guard !flag.isCancelled else { return false }
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            queue.async {
                guard !flag.isCancelled else { return continuation.resume(returning: false) }
                continuation.resume(returning: (try? work()) != nil)
            }
        }
    }

    /// 排进队列就返回。
    public func async(_ work: @escaping @Sendable () -> Void) {
        queue.async(execute: work)
    }
}
