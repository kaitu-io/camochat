import SwiftUI
import UIKit
import UniformTypeIdentifiers
import ChencangShared

/// 「拍摄」(R8):系统相机,拍照或录像(≤ 60 秒),录像再走 720p 导出。不做 App 内相机。
struct CameraPicker: UIViewControllerRepresentable {
    let onPicked: (PickedMedia) -> Void
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        picker.videoMaximumDuration = MediaLimits.maxVideoSeconds
        picker.videoQuality = .typeHigh
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked, onDone: onDone) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPicked: (PickedMedia) -> Void
        let onDone: () -> Void

        init(onPicked: @escaping (PickedMedia) -> Void, onDone: @escaping () -> Void) {
            self.onPicked = onPicked
            self.onDone = onDone
        }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let url = info[.mediaURL] as? URL {
                let copy = FileManager.default.temporaryDirectory
                    .appendingPathComponent("cc-cam-\(UUID().uuidString).\(url.pathExtension.isEmpty ? "mov" : url.pathExtension)")
                if (try? FileManager.default.copyItem(at: url, to: copy)) != nil {
                    onPicked(.video(copy))
                }
            } else if let image = info[.originalImage] as? UIImage {
                // 最高质量交给 ImagePreparer 统一缩放、压缩、去元数据。12 MP 的 JPEG 编码要几百毫秒,
                // 放后台做(终审 minor 3),编好再回主线程交出去。
                let onPicked = self.onPicked
                Task.detached(priority: .userInitiated) {
                    guard let data = image.jpegData(compressionQuality: 1) else { return }
                    await MainActor.run { onPicked(.image(data)) }
                }
            }
            onDone()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onDone()
        }
    }
}
