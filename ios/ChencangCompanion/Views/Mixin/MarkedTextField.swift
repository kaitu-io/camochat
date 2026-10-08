import SwiftUI
import UIKit
import ChencangShared

/// 组合态感知的单行输入框:输入法组合(`markedTextRange != nil`,如拼音中间态)进行中只显示、
/// 不回调、不改写;组合结束(或失焦)后才把最终文本交给 `settle`,由它返回要显示的规整文本。
struct MarkedTextField: UIViewRepresentable {
    let placeholder: String
    let initialText: String
    let accessibilityLabelText: String
    let accessibilityId: String
    var alignment: NSTextAlignment = .natural
    var autofocus = false
    /// 组合已结束时调用:传入当前文本,返回应显示的文本(与传入不同则被改写)。
    let settle: (String) -> String

    /// 进入 window 后才聚焦一次(sheet 弹出时 `makeUIView` 阶段 view 还没上屏)。
    final class FocusField: UITextField {
        var autofocusOnce = false
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard autofocusOnce, window != nil else { return }
            autofocusOnce = false
            becomeFirstResponder()
        }
    }

    func makeUIView(context: Context) -> UITextField {
        let field = FocusField()
        field.autofocusOnce = autofocus
        field.placeholder = placeholder
        field.text = initialText
        field.textAlignment = alignment
        field.borderStyle = .roundedRect
        field.autocorrectionType = .no
        field.font = UIFontMetrics.default.scaledFont(for: .systemFont(ofSize: Moyu.FontSize.body))
        field.adjustsFontForContentSizeCategory = true
        field.accessibilityLabel = accessibilityLabelText
        field.accessibilityIdentifier = accessibilityId
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingDidEnd)
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return field
    }

    func updateUIView(_ uiView: UITextField, context: Context) {
        context.coordinator.settle = settle
        uiView.placeholder = placeholder
    }

    func makeCoordinator() -> Coordinator { Coordinator(settle: settle) }

    final class Coordinator: NSObject {
        var settle: (String) -> String
        init(settle: @escaping (String) -> String) { self.settle = settle }

        @objc func changed(_ field: UITextField) {
            guard field.markedTextRange == nil else { return }
            let current = field.text ?? ""
            let next = settle(current)
            if next != current { field.text = next }
        }
    }
}
