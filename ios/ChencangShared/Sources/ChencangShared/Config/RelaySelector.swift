import Foundation

/// 给 `MediaTransport` 排中转顺序:上次成功的那台(若配置单仍列着)排最前,其余保持配置顺序。
public final class RelaySelector: @unchecked Sendable {
    public static let goodKey = "cc.relay.good.v1"

    private let relays: () -> [String]
    private let defaults: UserDefaults

    public init(relays: @escaping () -> [String], defaults: UserDefaults) {
        self.relays = relays
        self.defaults = defaults
    }

    public func ordered() -> [String] {
        let all = relays()
        if let good = defaults.string(forKey: Self.goodKey), all.contains(good) {
            return [good] + all.filter { $0 != good }
        }
        return all
    }

    public func markGood(_ base: String) {
        if defaults.string(forKey: Self.goodKey) == base { return }
        defaults.set(base, forKey: Self.goodKey)
    }

    /// 生产用:读当前配置单的中转,粘性记录放进 App Group。
    public static func production() -> RelaySelector {
        guard let d = UserDefaults(suiteName: SharedAppGroupDefaults.suiteName) else {
            fatalError("App Group \(SharedAppGroupDefaults.suiteName) not available — check entitlements.")
        }
        return RelaySelector(relays: { ConfigRepository.shared.current().relays }, defaults: d)
    }
}
