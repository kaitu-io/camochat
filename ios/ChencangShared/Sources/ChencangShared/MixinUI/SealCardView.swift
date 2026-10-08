import SwiftUI

/// spec §4.1:加密卡(Seal Card)——产品的签名视觉。加密消息永远不以裸乱码示人,
/// 发送侧呈现为一张加密卡:sealed →(分享面板取消)notSent /(复制)copied。
/// 卡片的内容与状态由 `ThreadViewModel.SealCard` 决定,这里只负责画。
/// (扩展「陈仓解密」有自己的卡片 `ActionCardView`,不再复用这里的进行中/失败态。)
public enum SealCardState: Equatable {
    case sealed
    case copied
    /// 分享面板被取消:没交出去,卡片留着可再次分享/复制。
    case notSent
}

/// spec §5.2「发过一次后记住偏好,主 action 前置」——调用方把上次用户选的
/// action(经 `@AppStorage`)传进来,决定分享/复制两个按钮谁排前面。
public enum SealCardAction: String {
    case share
    case copy
}

public struct SealCardView: View {
    let state: SealCardState
    let summary: String
    let caption: String
    let primaryAction: SealCardAction
    let onShare: () -> Void
    let onCopy: () -> Void
    /// 右上角关闭(收起卡片,不标记);nil 不画关闭按钮。
    let onClose: (() -> Void)?

    public init(
        state: SealCardState,
        summary: String = "",
        caption: String = "",
        primaryAction: SealCardAction = .share,
        onShare: @escaping () -> Void = {},
        onCopy: @escaping () -> Void = {},
        onClose: (() -> Void)? = nil
    ) {
        self.state = state
        self.summary = summary
        self.caption = caption
        self.primaryAction = primaryAction
        self.onShare = onShare
        self.onCopy = onCopy
        self.onClose = onClose
    }

    private var borderColor: Color { Moyu.Palette.accentPrimary.opacity(0.3) }

    private var leadingIcon: (name: String, color: Color) {
        switch state {
        case .sealed: return ("lock.fill", Moyu.Palette.accentPrimary)
        case .copied: return ("checkmark.circle.fill", Moyu.Palette.accentPrimary)
        case .notSent: return ("arrow.uturn.up.circle.fill", Moyu.Palette.statusWarn)
        }
    }

    private var captionColor: Color {
        state == .notSent ? Moyu.Palette.statusWarn : Moyu.Palette.textSecondary
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Moyu.Space.s) {
            cardRow
            actionsRow
        }
    }

    private var cardRow: some View {
        HStack(spacing: Moyu.Space.m) {
            // 锁 icon 16pt 是 spec §4.1 的组件级字面量(尚无对应 L3 token),
            // 仍经 moyuFont 走 Dynamic Type 缩放,不是裸 .font(.system(size:))。
            Image(systemName: leadingIcon.name)
                .font(moyuFont(16, weight: .semibold))
                .foregroundStyle(leadingIcon.color)
            VStack(alignment: .leading, spacing: Moyu.Space.xs) {
                if !summary.isEmpty {
                    Text(summary)
                        .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                        .foregroundStyle(Moyu.Palette.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Text(caption)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(captionColor)
            }
            Spacer(minLength: 0)
            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(moyuFont(Moyu.FontSize.caption, weight: .semibold))
                        .foregroundStyle(Moyu.Palette.textTertiary)
                        .padding(Moyu.Space.s)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.commonClose)
            }
        }
        .padding(.horizontal, Moyu.Space.m)
        .frame(minHeight: 56) // spec §4.1「高 56」——容器不写固定高度,用 min-height。
        .background(Moyu.Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous)
                .strokeBorder(borderColor, lineWidth: 1)
        )
        // 还没发(面板被取消)时点卡片正文 = 再分享一次;关闭按钮是 Button,点它不会触发这里。
        .contentShape(RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
        .onTapGesture { if state == .notSent { onShare() } }
        .accessibilityAddTraits(state == .notSent ? .isButton : [])
    }

    private var actionsRow: some View {
        HStack(spacing: Moyu.Space.s) {
            if primaryAction == .copy {
                copyButton
                shareButton
            } else {
                shareButton
                copyButton
            }
        }
    }

    private var shareButton: some View {
        Button(action: onShare) {
            Text(L10n.commonShare)
                .moyuActionLabelStyle(foreground: Moyu.Palette.accentOnPrimary)
                .background(Moyu.Palette.accentPrimary, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private var copyButton: some View {
        Button(action: onCopy) {
            Label(state == .copied ? L10n.commonCopied : L10n.commonCopy, systemImage: state == .copied ? "checkmark" : "doc.on.doc")
                .moyuActionLabelStyle(foreground: Moyu.Palette.accentPrimary)
                .background(Moyu.Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: Moyu.Radius.input, style: .continuous)
                        .strokeBorder(Moyu.Palette.borderHairline, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

private extension View {
    /// actions 行按钮的共用文字样式(Text 与 Label 都是 View,一份实现两处复用)。
    func moyuActionLabelStyle(foreground: Color) -> some View {
        self.font(moyuFont(Moyu.FontSize.callout, weight: .medium))
            .foregroundStyle(foreground)
            .padding(.horizontal, Moyu.Space.m)
            .padding(.vertical, Moyu.Space.s)
    }
}
