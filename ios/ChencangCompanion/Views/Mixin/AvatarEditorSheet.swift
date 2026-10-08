import SwiftUI
import UIKit
import ChencangShared

/// 「我的头像」编辑弹层(spec §3.4):预览 → 字 → 底色(八色 + 自动)→ 完成。选择即存(写回调用方持有的
/// `@AppStorage` 绑定,即 App Group 里的 `MyProfileKeys`),下滑或「完成」关闭。资料只存本机,不进握手。
/// 「字」输入框组合态感知(`MarkedTextField`):拼音等组合进行中不规整、不落盘。
struct AvatarEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var glyph: String
    @Binding var color: Int?

    @State private var model: AvatarEditorModel

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 4)

    init(glyph: Binding<String>, color: Binding<Int?>, myName: String, fingerprintHex: String?) {
        _glyph = glyph
        _color = color
        _model = State(initialValue: AvatarEditorModel(
            storedGlyph: glyph.wrappedValue, storedColor: color.wrappedValue,
            myName: myName, myFingerprintHex: fingerprintHex
        ))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Moyu.Space.l) {
                Text(L10n.meAvatarTitle)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                MoyuAvatarView(spec: model.preview, size: Moyu.Size.avatarProfile)
                    .accessibilityHidden(true)
                glyphRow
                colorSection
                Button(L10n.commonDone) {
                    // 先收键盘:提交输入框里未确认的组合文本并规整落盘,再关闭。
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    dismiss()
                }
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .tint(Moyu.Palette.accentPrimary)
                    .frame(minWidth: Moyu.Size.touchMin, minHeight: Moyu.Size.touchMin)
                    .accessibilityIdentifier("me-avatar-done")
            }
            .padding(Moyu.Space.l)
        }
        .background(Moyu.Palette.surfaceBase)
        .presentationDetents([.medium, .large])
    }

    private var glyphRow: some View {
        VStack(alignment: .leading, spacing: Moyu.Space.xs) {
            HStack {
                Text(L10n.meAvatarGlyphLabel)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
                MarkedTextField(
                    placeholder: model.placeholderGlyph,
                    initialText: model.storedGlyph ?? "",
                    accessibilityLabelText: L10n.meAvatarGlyphLabel,
                    accessibilityId: "me-avatar-glyph",
                    alignment: .center,
                    settle: { applyGlyph($0) }
                )
                .frame(width: Moyu.Size.glyphField, height: Moyu.Size.touchMin)
                Spacer()
            }
            if let error = model.error {
                Text(error)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.statusDanger)
            } else {
                Text(L10n.meAvatarGlyphHint)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var colorSection: some View {
        VStack(alignment: .leading, spacing: Moyu.Space.s) {
            Text(L10n.meAvatarColor)
                .font(moyuFont(Moyu.FontSize.callout))
                .foregroundStyle(Moyu.Palette.textSecondary)
            LazyVGrid(columns: columns, spacing: 0) {
                ForEach(0..<8, id: \.self) { swatch($0) }
            }
            Button { applyColor(nil) } label: {
                Text(L10n.meAvatarAuto)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .fontWeight(model.storedColor == nil ? .semibold : .regular)
                    .foregroundStyle(Moyu.Palette.accentPrimary)
                    .frame(minWidth: Moyu.Size.touchMin, minHeight: Moyu.Size.touchMin)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L10n.meAvatarColorAutoCd)
            .accessibilityAddTraits(model.storedColor == nil ? [.isButton, .isSelected] : .isButton)
            .accessibilityIdentifier("me-avatar-color-auto")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 视觉是 swatch 尺寸,可点区域 ≥ `touchMin`。
    private func swatch(_ index: Int) -> some View {
        let selected = model.storedColor == index
        return Button { applyColor(index) } label: {
            MoyuAvatarView(spec: AvatarSpec(glyph: "", paletteIndex: index), size: Moyu.Size.avatarSwatch)
                .padding(Moyu.Space.xs)
                .overlay(
                    Circle().strokeBorder(
                        selected ? Moyu.Palette.accentPrimary : Color.clear,
                        lineWidth: Moyu.Size.ringSelected
                    )
                )
                .frame(maxWidth: .infinity, minHeight: Moyu.Size.touchMin)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.meAvatarColorNCd(String(index + 1)))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("me-avatar-color-\(index)")
    }

    /// 仅在组合结束后由输入框调用:规整 → 落盘,返回输入框应显示的文本(只留合法的一个字素)。
    private func applyGlyph(_ raw: String) -> String {
        var next = model
        next.inputGlyph(raw, isComposing: false)
        if next != model { model = next }
        let stored = next.storedGlyph ?? ""
        if glyph != stored { glyph = stored }
        return stored
    }

    private func applyColor(_ index: Int?) {
        var next = model
        next.pickColor(index)
        if next != model { model = next }
        if color != next.storedColor { color = next.storedColor }
    }
}
