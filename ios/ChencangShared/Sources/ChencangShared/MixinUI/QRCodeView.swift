import SwiftUI
import CoreImage.CIFilterBuiltins
#if canImport(UIKit)
import UIKit
#endif

/// Renders a string as a QR code via the built-in CoreImage generator (no
/// third-party dependency — the iOS counterpart to Android's ZXing-backed
/// `PairingQr`). Migrated here (M4 Task 8) from the now-retired single-role
/// invite screen, which originally inlined it; the pairing wizard's 出示幕
/// (`PairingWizardView.ShowAct`) is now the consumer.
public struct QRCodeView: View {
    let text: String
    let size: CGFloat

    public init(text: String, size: CGFloat = 200) {
        self.text = text
        self.size = size
    }

    private static let context = CIContext()
    private static let filter = CIFilter.qrCodeGenerator()

    public var body: some View {
        #if canImport(UIKit)
        if let image = Self.makeImage(from: text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .accessibilityLabel(L10n.pairingQrCd)
        } else {
            Color.clear.frame(width: size, height: size)
        }
        #else
        // macOS (swift test host): no UIKit, no renderer needed — tests never
        // inspect pixels, only that construction doesn't crash.
        Color.clear.frame(width: size, height: size)
        #endif
    }

    #if canImport(UIKit)
    static func makeImage(from text: String) -> UIImage? {
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// 分享卡片用:四周补 `quietModules` 个白模块的静区,再按**整数倍**放大到不超过 `maxPixels`
    /// (每个模块都是整数像素,不插值,扫码更稳)。边长补成偶数像素,居中到偶数像素的容器里不会落在半个像素上。
    /// 返回图的 `scale` 为 `displayScale`,即按 点 = 像素 / displayScale 显示时与位图 1:1。
    static func makeImage(from text: String, quietModules: Int, maxPixels: Int, displayScale: CGFloat) -> UIImage? {
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let modules = Int(output.extent.width)
        let total = modules + 2 * quietModules
        let factor = max(1, maxPixels / total)
        let side = (total * factor + 1) / 2 * 2
        let white = CIImage(color: .white).cropped(to: CGRect(x: 0, y: 0, width: side, height: side))
        let scaled = output
            .transformed(by: CGAffineTransform(translationX: CGFloat(quietModules), y: CGFloat(quietModules)))
            .samplingNearest()
            .transformed(by: CGAffineTransform(scaleX: CGFloat(factor), y: CGFloat(factor)))
            .composited(over: white)
            .cropped(to: CGRect(x: 0, y: 0, width: side, height: side))
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg, scale: displayScale, orientation: .up)
    }
    #endif
}
