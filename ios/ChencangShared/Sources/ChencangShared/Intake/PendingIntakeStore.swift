import Foundation

/// 扩展 → 主 App 的配对码交接槽。用户在扩展里点了「打开陈仓添加」时,扩展把配对码放进来,
/// 主 App 打开 `camo://intake` 后取走交给向导。
///
/// 只存配对码(公开内容),绝不存会话消息或明文。单键 JSON `{wire, createdAt}`,后写覆盖前写;
/// `take` 总是清槽,超过 `maxAge` 的视为过期丢弃。
public struct PendingIntakeStore {
    public static let key = "cc.pendingIntake.v1"
    public static let maxAge: TimeInterval = 1800

    private struct Slot: Codable {
        let wire: String
        let createdAt: Date
    }

    private let defaults: AppGroupDefaults
    private let now: () -> Date

    public init(defaults: AppGroupDefaults = SharedAppGroupDefaults(), now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
    }

    public func put(wire: String) throws {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .millisecondsSince1970
        defaults.set(try enc.encode(Slot(wire: wire, createdAt: now())), forKey: Self.key)
    }

    public func take() -> String? {
        guard let data = defaults.data(forKey: Self.key) else { return nil }
        defaults.set(nil, forKey: Self.key)
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .millisecondsSince1970
        guard let slot = try? dec.decode(Slot.self, from: data),
              now().timeIntervalSince(slot.createdAt) <= Self.maxAge
        else { return nil }
        return slot.wire
    }
}
