import Foundation

/// 持久化「最近一次采纳的信封」与「最近一次刷新成功时间」(App Group,Action Extension 也能读)。
public final class ConfigStore: @unchecked Sendable {
    public static let envelopeKey = "cc.config.envelope.v1"
    public static let lastSuccessKey = "cc.config.lastSuccess.v1"

    private let defaults: AppGroupDefaults

    public init(defaults: AppGroupDefaults) { self.defaults = defaults }

    public func envelope() -> Data? { defaults.data(forKey: Self.envelopeKey) }

    public func saveEnvelope(_ e: Data) { defaults.set(e, forKey: Self.envelopeKey) }

    /// 毫秒时间戳;从未成功为 0。
    public func lastSuccessAt() -> Int64 {
        defaults.data(forKey: Self.lastSuccessKey)
            .flatMap { String(data: $0, encoding: .utf8) }
            .flatMap { Int64($0) } ?? 0
    }

    public func markSuccess(at: Int64) {
        defaults.set(Data(String(at).utf8), forKey: Self.lastSuccessKey)
    }
}
