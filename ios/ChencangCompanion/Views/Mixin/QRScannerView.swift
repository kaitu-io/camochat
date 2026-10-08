import SwiftUI
import PhotosUI
import CoreImage
import AVFoundation
import UIKit
import ChencangShared

/// 扫码组件(Task 8 配对向导「扫码」步骤依赖)。三层结构:
/// `ScannerController`(裸 AVFoundation 会话) → `ScannerRepresentable`
/// (`UIViewControllerRepresentable` 包装) → `QRScannerView`(SwiftUI 外壳,
/// 管相机权限三态 + 取景框遮罩)。扫描结果只是本地字符串一次性回调给调用方
/// (配对邀请 URL),不发起任何网络请求——零网络不变量不受影响。
final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onResult: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "app.chencang.companion.qrscanner.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    /// 布尔闸:防止 metadataOutput 在 session 真正停下之前的相邻帧里重复触发
    /// onResult / stopRunning——只放行第一个识别到的二维码。
    private var didEmitResult = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else { return }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer

        // session 启停在后台队列——`startRunning`/`stopRunning` 是阻塞调用,
        // 放主线程会卡住扫码界面的首帧渲染。
        sessionQueue.async { [session] in
            session.startRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didEmitResult,
            let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
            let value = object.stringValue
        else { return }
        didEmitResult = true
        onResult?(value)
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    deinit {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }
}

private struct ScannerRepresentable: UIViewControllerRepresentable {
    let onResult: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onResult = onResult
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerController, context: Context) {
        // 无状态可更新——onResult 在创建时已绑定。
    }
}

/// 从图片里识别二维码(系统 CIDetector,高精度);取第一个能解出文本的码,没有则 nil。
/// 先把长边缩到 2048 像素以内,免得大照片占内存;调用方应放在后台线程。
enum QRImageDecoder {
    static let maxSide: CGFloat = 2048

    static func decode(_ image: UIImage) -> String? {
        guard let ci = downscaled(image),
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil,
                                        options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        else { return nil }
        return detector.features(in: ci)
            .compactMap { ($0 as? CIQRCodeFeature)?.messageString }
            .first
    }

    private static func downscaled(_ image: UIImage) -> CIImage? {
        guard let ci = CIImage(image: image) else { return nil }
        let longest = max(ci.extent.width, ci.extent.height)
        guard longest > maxSide else { return ci }
        let scale = maxSide / longest
        return ci.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    }
}

struct QRScannerView: View {
    let onResult: (String) -> Void
    let onCancel: () -> Void

    @State private var authStatus: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    /// 「从相册选」:系统照片选择器(只给用户选中的那一张,不要相册权限)。
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var photoHasNoCode = false

    /// 取景框边长——spec 尚无对应 L3 token 的组件级字面量,先照抄 spec 原值,
    /// token 回填是后续计划(见 task 报告 concerns)。
    private let viewfinderSize: CGFloat = 260

    /// 遮罩色:相机取景惯例用不透明黑压暗四周,但设计 token 体系没有裸黑值,
    /// `Moyu.Palette.surfaceSunken.opacity(0.6)` 在浅色模式下太浅(发灰不发黑,
    /// 压不住取景框外的画面)。改用 `Moyu.Palette.textPrimary.opacity(0.5)`——
    /// 该 token 深浅轨自动适配(浅色系统下深、深色系统下浅),仍读作「压暗」,
    /// 是本组件对 token 规则的既定例外(计划已裁定),非新引入的裸色。
    private var maskColor: Color { Moyu.Palette.textPrimary.opacity(0.5) }

    /// 「取消」标签色:第二个既定例外,与 `maskColor` 同格式记录。需要在
    /// `maskColor`(随 textPrimary 明暗轨反转)上保持可读对比,但检查过
    /// `Moyu.Palette` 目录没有契约匹配「浮层遮罩上的标签」的现成 token:
    /// `Moyu.Palette.accentOnPrimary` 契约上专配 accentPrimary 实底(App 内其余
    /// 用法——ConversationThreadView、ActionCardView、MoyuAvatarView——无一
    /// 例外都是实底),数值上凑巧够对比但语义不对。这里就地按例外声明:
    /// 浅色系统 maskColor 偏深→标签用白;深色系统 maskColor 偏浅→标签用近黑
    /// (0x0B0D0E,取自 primitive `night-950`,与 maskColor 的 textPrimary 暗轨
    /// 同一深浅方向反着走),不是新拍的裸色,是有出处的一对已有色值。
    private var cancelLabelColor: Color {
        Color(
            UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? UIColor(red: 0x0B / 255.0, green: 0x0D / 255.0, blue: 0x0E / 255.0, alpha: 1.0)
                    : .white
            })
    }

    init(onResult: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.onResult = onResult
        self.onCancel = onCancel
    }

    var body: some View {
        Group {
            switch authStatus {
            case .authorized:
                scannerBody
            case .notDetermined:
                // 这一态还没有相机画面(系统权限弹窗尚未回应),不是「取景中」,
                // 语义上和拒绝态一样都是「无相机内容」的普通页面背景——直接走
                // surfaceBase,不需要相机取景惯例的压暗遮罩,也就不需要例外。
                Moyu.Palette.surfaceBase
                    .ignoresSafeArea()
                    .task {
                        let granted = await AVCaptureDevice.requestAccess(for: .video)
                        authStatus = granted ? .authorized : .denied
                    }
            default:
                // .denied 与 .restricted(家长控制等)同走拒绝态文案。
                deniedBody
            }
        }
    }

    /// 「从相册选」按钮:相机拒绝态与取景态都有。
    private var photoPicker: some View {
        PhotosPicker(selection: $pickedPhoto, matching: .images) {
            Text(L10n.scannerPickPhoto)
                .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, Moyu.Space.s)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier("scanner-pick-photo")
        .onChange(of: pickedPhoto) { item in
            guard let item else { return }
            Task { await decodePicked(item) }
        }
    }

    private var noCodeNotice: some View {
        Text(L10n.scannerPhotoNoCode)
            .font(moyuFont(Moyu.FontSize.callout))
            .foregroundStyle(Moyu.Palette.statusDanger)
            .multilineTextAlignment(.center)
    }

    /// 选中的图里认出二维码就走和相机扫到一样的回调;认不出留在扫码页提示。
    private func decodePicked(_ item: PhotosPickerItem) async {
        let data = try? await item.loadTransferable(type: Data.self)
        pickedPhoto = nil
        // 解码和检测都在后台线程;结果回主线程交给 `onResult`。
        let text: String? = await Task.detached(priority: .userInitiated) {
            guard let data, let image = UIImage(data: data) else { return nil }
            return QRImageDecoder.decode(image)
        }.value
        if let text {
            photoHasNoCode = false
            onResult(text)
        } else {
            photoHasNoCode = true
        }
    }

    private var scannerBody: some View {
        ZStack {
            ScannerRepresentable(onResult: onResult)
                .ignoresSafeArea()

            GeometryReader { proxy in
                let size = proxy.size
                let holeOriginX = max(0, (size.width - viewfinderSize) / 2)
                let holeOriginY = max(0, (size.height - viewfinderSize) / 2)

                ZStack {
                    // 四片遮罩:上下左右各一块半透明矩形,围出中央取景框的镂空。
                    Rectangle()
                        .fill(maskColor)
                        .frame(width: size.width, height: holeOriginY)
                        .position(x: size.width / 2, y: holeOriginY / 2)
                    Rectangle()
                        .fill(maskColor)
                        .frame(width: size.width, height: holeOriginY)
                        .position(x: size.width / 2, y: size.height - holeOriginY / 2)
                    Rectangle()
                        .fill(maskColor)
                        .frame(width: holeOriginX, height: viewfinderSize)
                        .position(x: holeOriginX / 2, y: size.height / 2)
                    Rectangle()
                        .fill(maskColor)
                        .frame(width: holeOriginX, height: viewfinderSize)
                        .position(x: size.width - holeOriginX / 2, y: size.height / 2)

                    RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous)
                        .stroke(Moyu.Palette.accentPrimary, lineWidth: 2)
                        .frame(width: viewfinderSize, height: viewfinderSize)
                        .position(x: size.width / 2, y: size.height / 2)
                }
            }
            .ignoresSafeArea()

            VStack {
                HStack {
                    Button(action: onCancel) {
                        Text(L10n.commonCancel)
                            .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                            .foregroundStyle(cancelLabelColor)
                            .padding(.horizontal, Moyu.Space.m)
                            .padding(.vertical, Moyu.Space.s)
                    }
                    Spacer()
                }
                .padding(.top, Moyu.Space.l)
                .padding(.horizontal, Moyu.Space.l)
                Spacer()
                if photoHasNoCode { noCodeNotice.padding(.bottom, Moyu.Space.s) }
                photoPicker
                    .padding(.horizontal, Moyu.Space.xl)
                    .padding(.bottom, Moyu.Space.xl)
            }
        }
    }

    private var deniedBody: some View {
        VStack(spacing: Moyu.Space.l) {
            Image(systemName: "camera.fill")
                .font(moyuFont(40))
                .foregroundStyle(Moyu.Palette.textTertiary)
            Text(L10n.pairingScanNoCamera)
                .font(moyuFont(Moyu.FontSize.body))
                .foregroundStyle(Moyu.Palette.textPrimary)
                .multilineTextAlignment(.center)

            VStack(spacing: Moyu.Space.m) {
                if photoHasNoCode { noCodeNotice }
                photoPicker
                Button {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                    UIApplication.shared.open(url)
                } label: {
                    Text(L10n.mediaGoToSettings)
                        .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Moyu.Space.s)
                }
                .buttonStyle(.borderedProminent)
                .tint(Moyu.Palette.accentPrimary)

                Button(action: onCancel) {
                    Text(L10n.commonBack)
                        .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Moyu.Space.s)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(Moyu.Space.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Moyu.Palette.surfaceBase)
    }
}
