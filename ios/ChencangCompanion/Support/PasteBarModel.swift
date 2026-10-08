import ChencangShared
import SwiftUI

/// 粘贴条的可见状态。只看 `hasStrings` + `changeCount`(不读内容,免得触发系统「允许粘贴」弹窗);
/// 回前台、tab / 对话页出现、点掉或粘贴之后、陈仓自己写了剪贴板(`AppPasteboard.consumedDidChange`)之后重算。
@MainActor
final class PasteBarModel: ObservableObject {
    @Published private(set) var visible = false
    private var consumedObserver: NSObjectProtocol?

    init() {
        consumedObserver = NotificationCenter.default.addObserver(
            forName: AppPasteboard.consumedDidChange, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.recompute() }
        }
    }

    deinit {
        if let consumedObserver { NotificationCenter.default.removeObserver(consumedObserver) }
    }

    func recompute() {
        let show = PasteBarGate.shouldShow(
            hasStrings: AppPasteboard.hasStrings, changeCount: AppPasteboard.changeCount, consumed: AppPasteboard.consumed)
        if visible != show { visible = show }
    }

    /// 登记当前剪贴板为已消费并收起(点 ✕,或粘贴按钮回调的第一步)。
    func consume() {
        AppPasteboard.markConsumed()
        recompute()
    }
}

/// 一次粘贴条轻提示。带 id:连续两次同样的文字也能重新触发。
struct PasteBarNotice: Equatable {
    let id = UUID()
    let text: String
}
