import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import ChencangShared

/// 系统照片选择器(spec §3.3):不需要相册权限,images + videos,最多 9 个。
struct PhotoPicker: UIViewControllerRepresentable {
    let onPicked: ([PickedMedia]) -> Void
    /// 选中的素材全部读取失败(审查修复 6):不能悄悄地什么都不做,得让用户知道选择没有生效。
    let onFailed: () -> Void
    /// 选择流程结束(选完/取消都算)——父视图据此把 `showPhotoPicker` 收回 false,
    /// 镜像 `CameraPicker` 已有的做法;不再在这里自己 `dismiss(animated:)`,那样会让
    /// SwiftUI 的 `isPresented` 绑定跟系统 picker 各自为政,下一次点「相册」可能不再弹出
    /// (审查修复 2)。
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = MediaLimits.maxItemsPerFrame
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked, onFailed: onFailed, onDone: onDone) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: ([PickedMedia]) -> Void
        let onFailed: () -> Void
        let onDone: () -> Void

        init(onPicked: @escaping ([PickedMedia]) -> Void, onFailed: @escaping () -> Void, onDone: @escaping () -> Void) {
            self.onPicked = onPicked
            self.onFailed = onFailed
            self.onDone = onDone
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            // 无论选完还是取消,选择流程本身都结束了——父视图靠这个收起 sheet 绑定。
            onDone()
            guard !results.isEmpty else { return }
            let providers = results.map(\.itemProvider)
            Task { @MainActor in
                var picks: [PickedMedia] = []
                for provider in providers {
                    if let pick = await Self.load(provider) { picks.append(pick) }
                }
                // 选了东西但一个都没读出来(权限/格式/系统临时故障):不能假装什么都没发生
                // (审查修复 6)。
                if picks.isEmpty {
                    onFailed()
                } else {
                    onPicked(picks)
                }
            }
        }

        static func load(_ provider: NSItemProvider) async -> PickedMedia? {
            if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                return await withCheckedContinuation { continuation in
                    provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                        guard let url else {
                            continuation.resume(returning: nil)
                            return
                        }
                        // 回调返回后系统会删掉这个临时文件,先拷走;VideoPreparer 用完即删。
                        let ext = url.pathExtension.isEmpty ? "mov" : url.pathExtension
                        let copy = FileManager.default.temporaryDirectory
                            .appendingPathComponent("cc-pick-\(UUID().uuidString).\(ext)")
                        do {
                            try FileManager.default.copyItem(at: url, to: copy)
                            continuation.resume(returning: .video(copy))
                        } catch {
                            continuation.resume(returning: nil)
                        }
                    }
                }
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                return await withCheckedContinuation { continuation in
                    provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                        continuation.resume(returning: data.map(PickedMedia.image))
                    }
                }
            }
            return nil
        }
    }
}
