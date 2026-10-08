import SwiftUI
import UIKit
import ChencangShared

/// 「你的名字」问名字幕:引导第二幕、首次配对向导里共用。输入框沿用 `MarkedTextField`(组合输入安全,截断只在
/// 组合结束后做),与改名弹层同一规则;空输入 = 跳过。回调拿到的是原始输入,规范化由调用方的 VM 做。
struct NamePromptView: View {
    let onContinue: (String) -> Void
    let onSkip: () -> Void

    @State private var draft = ""

    var body: some View {
        VStack(spacing: Moyu.Space.xl) {
            Spacer()

            VStack(spacing: Moyu.Space.s) {
                Text(L10n.namePromptTitle)
                    .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                Text(L10n.namePromptHint)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .multilineTextAlignment(.center)
            }

            MarkedTextField(
                placeholder: "",
                initialText: "",
                accessibilityLabelText: L10n.namePromptTitle,
                accessibilityId: "name-prompt-field",
                autofocus: true,
                settle: { text in
                    let next = rewrittenNameInput(text, isComposing: false) ?? text
                    if draft != next { draft = next }
                    return next
                }
            )

            Spacer()

            VStack(spacing: Moyu.Space.s) {
                Button {
                    // 收起键盘 = 提交组合中的文本,editingDidEnd 会同步更新 draft。
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    onContinue(draft)
                } label: {
                    Text(L10n.namePromptContinue)
                        .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Moyu.Space.s)
                }
                .buttonStyle(.borderedProminent)
                .tint(Moyu.Palette.accentPrimary)
                .accessibilityIdentifier("name-prompt-continue")

                Button(action: onSkip) {
                    Text(L10n.namePromptSkip)
                        .font(moyuFont(Moyu.FontSize.body))
                        .frame(maxWidth: .infinity, minHeight: Moyu.Size.touchMin)
                }
                .tint(Moyu.Palette.accentPrimary)
                .accessibilityIdentifier("name-prompt-skip")
            }
        }
    }
}
