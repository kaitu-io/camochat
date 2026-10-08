import ChencangShared
import SwiftUI
import UIKit

/// 对话页顶部横幅:一句话 + 可选动作(仅「核对」)+ 关闭。样式与粘贴条同族(accentContainer)。
struct ThreadBannerBar: View {
    let banner: ThreadBanner
    let onAction: () -> Void
    let onDismiss: () -> Void

    private var text: String {
        switch banner {
        case .waitingPeer: return L10n.threadBannerWaitingPeer
        case .sayHi: return L10n.threadBannerSayHi
        case .verifyLater: return L10n.verifyLaterBanner
        }
    }

    var body: some View {
        HStack(spacing: Moyu.Space.s) {
            Text(text)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if banner == .verifyLater {
                Button(L10n.verifyLaterBannerAction, action: onAction)
                    .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                    .tint(Moyu.Palette.accentPrimary)
                    .frame(minHeight: Moyu.Size.touchMin)
                    .accessibilityIdentifier("thread-banner-action")
            }
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .frame(minWidth: Moyu.Size.touchMin, minHeight: Moyu.Size.touchMin)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L10n.pasteBarDismissCd)
            .accessibilityIdentifier("thread-banner-dismiss")
        }
        .padding(.leading, Moyu.Space.l)
        .frame(minHeight: Moyu.Size.touchMin)
        .background(Moyu.Palette.accentContainer)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("thread-banner")
        .onAppear { UIAccessibility.post(notification: .announcement, argument: text) }
    }
}
