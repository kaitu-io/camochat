import SwiftUI
import UIKit

/// `UIActivityViewController` 的 SwiftUI 包装,专供封缄卡「分享到微信」(文字与媒体)用——
/// 真正的送达仍是人肉:分享面板列出微信等目标 App,用户手动选一个把 wire
/// 文本递出去,陈仓本身没有、也不会有一条自建信道。
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    /// 面板报告的结果(终审 minor 9,已裁决):`true` = 用户真的把内容交给了某个目标(微信/拷贝…),
    /// `false` = 取消。取消分享扩展后回到面板再选别的,会先报一次 false 再报一次 true——
    /// 每次都原样上报,「任一 true 即标、只标一次」由 `ThreadViewModel.shareReported` 判定。
    var onComplete: ((Bool) -> Void)? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        let onComplete = self.onComplete
        controller.completionWithItemsHandler = { activityType, completed, _, _ in
            // 面板里选「拷贝」是陈仓自己写剪贴板:登记 changeCount,回前台不弹粘贴条。
            if completed, activityType == .copyToPasteboard { AppPasteboard.markConsumed() }
            onComplete?(completed)
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
        // 无状态可更新——items 在 present 时已定,后续 body 重算不需要同步。
    }
}
