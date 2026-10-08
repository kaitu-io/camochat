import Foundation

/// 粘贴条该不该出现。不读剪贴板内容(读 `.string` 会触发系统「允许粘贴」弹窗):
/// 输入只有 `UIPasteboard.hasStrings` 和 `changeCount`;与上次已消费的 changeCount 相同就不再提示。
///
/// 与 Android 不同,changeCount 永远有值(没有「未知」)。它在设备重启后会归零重计,
/// 极端情况下新计数恰好等于旧的已消费值会漏提示一次——可接受(用户仍可用向导里的粘贴按钮)。
public enum PasteBarGate {
    public static func shouldShow(hasStrings: Bool, changeCount: Int, consumed: Int?) -> Bool {
        hasStrings && changeCount != consumed
    }
}

/// 「已消费的剪贴板 changeCount」存取。陈仓自己写剪贴板后、用户点掉粘贴条后都登记到这里。
public struct PasteBarConsumedStore {
    public static let key = "cc.pasteBar.consumedChangeCount.v1"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public var consumed: Int? {
        get { defaults.object(forKey: Self.key) as? Int }
        nonmutating set { defaults.set(newValue, forKey: Self.key) }
    }
}
