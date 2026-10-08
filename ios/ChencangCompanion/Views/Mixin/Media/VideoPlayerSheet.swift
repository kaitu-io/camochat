import SwiftUI
import AVKit
import ChencangShared

/// 全屏播放本地解密后的视频(`media/<id>/<index>.mp4` 硬链接)。
/// 以 `.fullScreenCover` 呈现:纯黑底 + 左上关闭按钮(系统 AVPlayerViewController 在 cover 里没有关闭入口)。
struct VideoPlayerSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            Moyu.Palette.mediaViewerBackground.ignoresSafeArea()
            PlayerController(url: url).ignoresSafeArea()
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(moyuFont(Moyu.FontSize.title))
                    // 有意复用:纯黑底上的前景恒用浅色(textPrimary 在暗色轨下的值)
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .padding(Moyu.Space.m)
            }
            .accessibilityLabel(L10n.commonClose)
        }
        .environment(\.colorScheme, .dark)
    }
}

private struct PlayerController: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        let controller = AVPlayerViewController()
        controller.view.backgroundColor = UIColor(Moyu.Palette.mediaViewerBackground)
        let player = AVPlayer(url: url)
        controller.player = player
        player.play()
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}
}
