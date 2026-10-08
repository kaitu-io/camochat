import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// 配对分享卡片的文案选择(纯逻辑,可测)。
public enum PairingCardText {
    /// 昵称去首尾空白;空 → 匿名版标题。
    public static func title(isResponse: Bool, name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if isResponse {
            return trimmed.isEmpty ? L10n.shareCardResponseTitleAnonymous : L10n.shareCardResponseTitle(trimmed)
        }
        return trimmed.isEmpty ? L10n.shareCardInviteTitleAnonymous : L10n.shareCardInviteTitle(trimmed)
    }

    public static func note(isResponse: Bool) -> String {
        isResponse ? L10n.shareCardResponseNote : L10n.shareCardInviteNote
    }

    /// 顶部品牌条右侧的步骤标签:邀请是两步里的第 1 步,回执是最后一步。
    public static func step(isResponse: Bool) -> String {
        isResponse ? L10n.shareCardStepResponse : L10n.shareCardStepInvite
    }

    /// 卖点只在邀请卡上出现(回执卡的重点是「还差一步」)。
    public static func pitch(isResponse: Bool) -> String? {
        isResponse ? nil : L10n.shareCardInvitePitch
    }
}

/// 配对邀请 / 回执的分享卡片(360×480 pt,不随暗色模式变化)。屏幕预览和导出 PNG 画的是同一个 View。
/// 字号直接取 token 基准值、不走 Dynamic Type——卡片是定尺寸的图片,版面不能随系统字号变。
public struct PairingCardView: View {
    let isResponse: Bool
    let name: String
    let link: String

    public init(isResponse: Bool, name: String, link: String) {
        self.isResponse = isResponse
        self.name = name
        self.link = link
    }

    public var body: some View {
        // 内容在品牌条下方的区域里垂直居中:回执卡内容少,不留一大片底部空白。
        VStack(spacing: 0) {
            header
            main.frame(maxHeight: .infinity)
        }
        .frame(width: Moyu.Size.shareCardWidth, height: Moyu.Size.shareCardHeight, alignment: .top)
        .background(Moyu.Palette.shareCardBackground)
    }

    /// 顶部通栏品牌条(高 `shareCardHeader`)+ 内容区,内容按自然高度排版、不留撑高的 Spacer:
    /// 最坏情况(英文匿名邀请 + 卖点 2 行 + 说明 3 行、20 字昵称标题 2 行)也要放进 480pt,
    /// 说明文字绝不截断;标题、品牌条宁可缩字号也不裁。内容区上下内边距 `Space.s`,左右 `Space.xl`。
    var content: some View {
        VStack(spacing: 0) {
            header
            main
        }
    }

    private var main: some View {
        VStack(spacing: Moyu.Space.xs) {
            Text(PairingCardText.title(isResponse: isResponse, name: name))
                .font(.system(size: Moyu.FontSize.title, weight: .semibold))
                .foregroundStyle(Moyu.Palette.shareCardText)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            if let pitch = PairingCardText.pitch(isResponse: isResponse) {
                Text(pitch)
                    .font(.system(size: Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.shareCardTextSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            qrBox
                .padding(.vertical, Moyu.Space.s)
            Text(L10n.shareCardScanHint)
                .font(.system(size: Moyu.FontSize.callout, weight: .bold))
                .foregroundStyle(Moyu.Palette.shareCardText)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Text(PairingCardText.note(isResponse: isResponse))
                .font(.system(size: Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.shareCardTextSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Moyu.Space.xl)
        .padding(.top, Moyu.Space.s)
        .padding(.bottom, Moyu.Space.s)
    }

    /// 通栏品牌条:左品牌(半粗)、右步骤标签(粗),单行,放不下就缩(不换行、不裁)。
    private var header: some View {
        HStack(spacing: Moyu.Space.s) {
            Text(L10n.shareCardBrand)
                .font(.system(size: Moyu.FontSize.callout, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 0)
            Text(PairingCardText.step(isResponse: isResponse))
                .font(.system(size: Moyu.FontSize.callout, weight: .bold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .foregroundStyle(Moyu.Palette.shareCardHeaderText)
        .padding(.horizontal, Moyu.Space.xl)
        .frame(width: Moyu.Size.shareCardWidth, height: Moyu.Size.shareCardHeader)
        .background(Moyu.Palette.shareCardHeaderBackground)
    }

    private var qrBox: some View {
        RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous)
            .fill(Moyu.Palette.qrQuietZone)
            .frame(width: Moyu.Size.shareCardQr, height: Moyu.Size.shareCardQr)
            .overlay(qrImage)
            .clipShape(RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
    }

    @ViewBuilder
    private var qrImage: some View {
        #if canImport(UIKit)
        // 图自带 4 个模块的静区,再留出 4pt 内缩让圆角不被方图盖住;整数像素模块、按 1 点 = 3 像素原尺寸居中显示(不缩放)。
        let inset: CGFloat = 4
        if let image = QRCodeView.makeImage(from: link, quietModules: 4,
                                            maxPixels: Int((Moyu.Size.shareCardQr - 2 * inset) * 3), displayScale: 3) {
            Image(uiImage: image).interpolation(.none)
        }
        #else
        EmptyView()
        #endif
    }
}

#if canImport(UIKit)
/// 把卡片画成 1080×1440 的 PNG 级图片(360×480 pt × 3)。
public enum PairingCardRenderer {
    @MainActor
    public static func image(isResponse: Bool, name: String, link: String) -> UIImage? {
        let renderer = ImageRenderer(content: PairingCardView(isResponse: isResponse, name: name, link: link))
        renderer.scale = 3
        return renderer.uiImage
    }
}
#endif
