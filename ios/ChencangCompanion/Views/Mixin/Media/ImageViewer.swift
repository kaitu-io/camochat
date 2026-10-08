import SwiftUI
import Photos
import UIKit
import ChencangShared

struct IdentifiedURL: Identifiable {
    let id = UUID()
    let url: URL
    /// 看图页「转发」要转发的这一张;视频等不需要的场景为 nil。
    var forward: ThreadForwardRequest? = nil
}

/// 转发请求:源消息 + 要转发的下标(`nil` = 整条消息全部)。
struct ThreadForwardRequest {
    let messageId: String
    let indices: [Int]?
}

/// 全屏看图:双指缩放 + 「保存到相册」(仅写入权限 addOnly,首次点保存时才申请)。
struct ImageViewer: View {
    let url: URL
    /// 点「转发」:调用方负责先关掉本页再弹选人面板(同一时刻只能有一个 presentation)。
    let onForward: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var saveResult: String?

    var body: some View {
        NavigationStack {
            ZoomableImage(url: url)
                .ignoresSafeArea(edges: .bottom)
                .background(Moyu.Palette.mediaViewerBackground)
                .toolbarBackground(Moyu.Palette.mediaViewerBackground, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                .toolbarColorScheme(.dark, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.commonClose) { dismiss() }
                    }
                    if let onForward {
                        ToolbarItem(placement: .primaryAction) {
                            Button(L10n.commonForward) { onForward() }
                        }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Button(L10n.mediaSaveToAlbum) { Task { await save() } }
                    }
                }
                // 有意复用:纯黑底上前景恒用浅色(textPrimary 在暗色轨下的值),不随系统明暗翻成黑字
                .tint(Moyu.Palette.textPrimary)
                .alert(saveResult ?? "", isPresented: Binding(
                    get: { saveResult != nil },
                    set: { if !$0 { saveResult = nil } }
                )) {
                    Button(L10n.commonOk) { saveResult = nil }
                }
        }
        .environment(\.colorScheme, .dark)
    }

    private func save() async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            saveResult = L10n.mediaPhotosWriteDenied
            return
        }
        guard let data = try? Data(contentsOf: url) else {
            saveResult = L10n.mediaSaveFailed
            return
        }
        // 按字节嗅探定 (数据, UTI, 文件名)(不信扩展名);WebP 先转 HEIC。
        guard let plan = PhotoSavePlan.make(for: data) else {
            saveResult = L10n.mediaSaveFailed
            return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let options = PHAssetResourceCreationOptions()
                options.uniformTypeIdentifier = plan.uti
                options.originalFilename = plan.filename
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: plan.data, options: options)
            }
            saveResult = L10n.mediaSaved
        } catch {
            saveResult = L10n.mediaSaveFailed
        }
    }
}

private struct ZoomableImage: UIViewRepresentable {
    let url: URL
    /// 全屏看图解码上限:4096 像素长边——比任何手机屏幕的物理像素都大,放大 4 倍也够清楚。
    static let maxDecodePixel: CGFloat = 4096

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.delegate = context.coordinator
        // 收到的图片由对端决定尺寸,不能整张原尺寸解码(几万像素边长能把内存撑爆被系统杀掉):
        // 后台用 ImageIO 按长边 ≤ 4096 像素解码(终审 minor 4),解好再放进来。
        let imageView = UIImageView()
        let url = self.url
        Task { @MainActor [weak imageView] in
            let image = await MediaThumbnailer.image(at: url, maxPixel: Self.maxDecodePixel)
            imageView?.image = image
        }
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
        ])
        context.coordinator.imageView = imageView
        return scroll
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {}

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
    }
}
