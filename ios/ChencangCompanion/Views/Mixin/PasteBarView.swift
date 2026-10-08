import ChencangShared
import SwiftUI
import UIKit

/// 「你刚复制了内容 [粘贴] ✕」。内容只经系统 `PasteButton` 交给 `onPaste`(没有「允许粘贴」弹窗);
/// 回调里的第一步是登记已消费,由调用方(`MixinAppModel.handlePasteBar`)做。
struct PasteBarView: View {
    let onPaste: (String?) -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: Moyu.Space.s) {
            Text(L10n.pasteBarText)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            PasteButton(payloadType: String.self) { strings in
                let first = strings.first
                Task { @MainActor in onPaste(first) }
            }
            .labelStyle(.titleOnly)
            .buttonBorderShape(.capsule)
            .tint(Moyu.Palette.accentPrimary)
            .accessibilityIdentifier("paste-bar-paste")
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .frame(minWidth: Moyu.Size.touchMin, minHeight: Moyu.Size.touchMin)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L10n.pasteBarDismissCd)
            .accessibilityIdentifier("paste-bar-dismiss")
        }
        .padding(.leading, Moyu.Space.l)
        .frame(minHeight: Moyu.Size.touchMin)
        .background(Moyu.Palette.accentContainer)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("paste-bar")
        .onAppear { UIAccessibility.post(notification: .announcement, argument: L10n.pasteBarText) }
    }
}

/// 状态驱动的粘贴条:可见才画。
struct PasteBarHost: View {
    @EnvironmentObject private var model: MixinAppModel
    @ObservedObject var state: PasteBarModel

    var body: some View {
        if state.visible {
            PasteBarView(
                onPaste: { text in Task { await model.handlePasteBar(text) } },
                onDismiss: state.consume)
        }
    }
}

extension View {
    /// 会话 / 联系人 tab 的粘贴条:贴在 tab 内容的底边、也就是 tab 栏正上方(spec 4.2「两端同形的底部浮条」,
    /// ✕ 远离 tab 图标);用 `safeAreaInset`,列表自动让出底部,内容不被盖住。「我」tab 不挂。
    func pasteBarAboveTabBar(_ state: PasteBarModel) -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) { PasteBarHost(state: state) }
    }
}

/// 粘贴条的轻提示:整个 App 只有这一处(根上的 overlay)。显示时就把 `model.pasteBarNotice` 清掉,不会重放;
/// 停留两拍 Motion.seal 后淡出,连续触发以最后一次为准。不带成功图标(这是失败提示)。
struct PasteBarToast: View {
    @ObservedObject var model: MixinAppModel
    @State private var shown: String?
    @State private var token = 0

    var body: some View {
        Group {
            if let shown {
                Text(shown)
                    .font(moyuFont(Moyu.FontSize.callout, weight: .medium))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, Moyu.Space.m)
                    .padding(.vertical, Moyu.Space.s)
                    .background(Moyu.Palette.surfaceRaised, in: Capsule())
                    .overlay(Capsule().strokeBorder(Moyu.Palette.borderHairline, lineWidth: Moyu.Space.xs / 4)) // 有意复用:发丝线宽
                    .padding(.bottom, Moyu.Space.xxl)
                    .padding(.horizontal, Moyu.Space.l)
                    .transition(.opacity)
                    .allowsHitTesting(false)
                    .accessibilityIdentifier("paste-bar-toast")
            }
        }
        .animation(.easeOut(duration: Moyu.Motion.standard / 1000), value: shown)
        .onChange(of: model.pasteBarNotice) { notice in
            guard let notice else { return }
            model.pasteBarNotice = nil
            token += 1
            let mine = token
            shown = notice.text
            UIAccessibility.post(notification: .announcement, argument: notice.text)
            Task {
                try? await Task.sleep(nanoseconds: UInt64(Moyu.Motion.seal) * 2 * 1_000_000)
                if token == mine { shown = nil }
            }
        }
    }
}
