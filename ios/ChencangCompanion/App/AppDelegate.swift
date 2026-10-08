import UIKit
import ChencangShared

/// 只为后台上传会话存在(先分享、后上传 spec §1.1):系统在后台把 App 拉起来交付上传结果时,
/// 把它给的 completion handler 交给上传引擎,等本批事件全部处理完(`urlSessionDidFinishEvents`)再调用。
///
/// 会话本身在 `ChencangCompanionApp.init` 里随 `MixinAppModel` 建好(早于任何系统回调),
/// 这里不再重复创建——同一 identifier 一个进程只能有一个会话。
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// `ChencangCompanionApp.init` 设置;该 init 先于任何 UIApplicationDelegate 回调执行。
    @MainActor static var uploader: BackgroundUploader?

    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundUploader.identifier, let uploader = Self.uploader else {
            completionHandler()
            return
        }
        uploader.backgroundCompletionHandler = completionHandler
    }
}
