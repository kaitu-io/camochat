import SwiftUI
import UIKit
import ChencangShared

/// 「我的昵称」改名弹层(spec §3.4)。不用 alert:alert 里放不下组合态感知的输入框(见 `MarkedTextField`),
/// 因此与头像编辑同一风格用 sheet,文案与标识保持不变。截断只在组合结束后做;保存前先让输入框收起键盘,
/// 把尚未确认的组合文本提交。空串合法(= 清除昵称);含控制字符等非法值保存键不可点、不写盘。
struct NameEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var name: String
    @State private var draft: String

    init(name: Binding<String>) {
        _name = name
        _draft = State(initialValue: name.wrappedValue)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Moyu.Space.l) {
                Text(L10n.meNameTitle)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                MarkedTextField(
                    placeholder: "",
                    initialText: name,
                    accessibilityLabelText: L10n.meNameTitle,
                    accessibilityId: "me-name-field",
                    autofocus: true,
                    settle: { text in
                        let next = rewrittenNameInput(text, isComposing: false) ?? text
                        if draft != next { draft = next }
                        return next
                    }
                )
                Text(L10n.meNameLimit)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: Moyu.Space.xl) {
                    Button(L10n.commonCancel) { dismiss() }
                        .frame(minWidth: Moyu.Size.touchMin, minHeight: Moyu.Size.touchMin)
                    Button(L10n.commonSave) { save() }
                        .fontWeight(.semibold)
                        .frame(minWidth: Moyu.Size.touchMin, minHeight: Moyu.Size.touchMin)
                        .disabled(normalizeMyName(draft) == nil)
                        .accessibilityIdentifier("me-name-save")
                }
                .tint(Moyu.Palette.accentPrimary)
            }
            .padding(Moyu.Space.l)
        }
        .background(Moyu.Palette.surfaceBase)
        .presentationDetents([.medium, .large])
    }

    private func save() {
        // 收起键盘 = 提交组合中的文本,editingDidEnd 会同步更新 draft。
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        guard let normalized = normalizeMyName(draft) else { return }
        if name != normalized { name = normalized }
        dismiss()
    }
}
