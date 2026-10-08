import SwiftUI
import SafariServices

/// 系统 Safari 视图:只用于用户主动点开的公开网页(服务器源码页)。
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
