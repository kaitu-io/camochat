import ChencangShared
import UIKit

/// 陈仓对系统剪贴板的唯一出入口。写入后登记当时的 changeCount,回到前台时粘贴条就不会
/// 把「自己刚复制的内容」当成外来内容提示。刻意不读 `.string`/`.strings`/`.items`:
/// 读内容会触发系统「允许粘贴」弹窗,内容只在用户点系统粘贴按钮时才读。
enum AppPasteboard {
    private static var store: PasteBarConsumedStore { PasteBarConsumedStore() }

    static func write(_ text: String) {
        UIPasteboard.general.string = text
        markConsumed()
    }

    static var hasStrings: Bool { UIPasteboard.general.hasStrings }

    static var changeCount: Int { UIPasteboard.general.changeCount }

    static var consumed: Int? { store.consumed }

    /// 登记已消费后发出;粘贴条状态据此重算(陈仓自己写了剪贴板 → 粘贴条立刻收起)。
    static let consumedDidChange = Notification.Name("app.chencang.pasteboard.consumedDidChange")

    /// 把当前 changeCount 登记为已消费(写入后、分享面板选了「拷贝」后、用户关掉粘贴条后)。
    static func markConsumed() {
        store.consumed = UIPasteboard.general.changeCount
        NotificationCenter.default.post(name: consumedDidChange, object: nil)
    }
}
