import Foundation

/// 头像编辑的纯值模型(spec §3.4 / 规 E)。不碰存储:界面把 `storedGlyph` / `storedColor`
/// 写回 App Group(`MyProfileKeys`)。字符判定全部委托 `normalizeAvatarGlyph`,不在此另写。
public struct AvatarEditorModel: Equatable {
    public static var tooComplexMessage: String { L10n.meAvatarTooComplex }

    /// 已存的字;nil = 跟随昵称首字素。
    public private(set) var storedGlyph: String?
    /// 已存的色板下标;nil = 自动(按指纹)。
    public private(set) var storedColor: Int?
    public private(set) var error: String?
    private let myName: String
    private let myFingerprintHex: String?

    public init(storedGlyph: String?, storedColor: Int?, myName: String, myFingerprintHex: String?) {
        self.storedGlyph = (storedGlyph?.isEmpty ?? true) ? nil : storedGlyph
        self.storedColor = storedColor
        self.myName = myName
        self.myFingerprintHex = myFingerprintHex
    }

    public var preview: AvatarSpec {
        myAvatar(storedGlyph: storedGlyph, storedColor: storedColor, myName: myName, myFingerprintHex: myFingerprintHex)
    }

    /// 输入框占位 = 没有已存字时会显示的默认字。
    public var placeholderGlyph: String { firstGrapheme(myName) ?? L10n.meAvatarDefaultGlyph }

    /// 输入框内容变化。`isComposing`(输入法组合/marked text 进行中)时什么都不做:组合中间态
    /// (拼音字母等)不是用户的最终选择,不规整、不报错、不落盘;组合结束后再以最终文本调一次。
    /// 先去掉尾随空白/换行(粘贴「山青\n」),再只看最后一个字素:空 = 清除(跟随昵称);
    /// 过于复杂 = 保留原值并报错;其余非法输入忽略。
    public mutating func inputGlyph(_ raw: String, isComposing: Bool = false) {
        if isComposing { return }
        var trimmed = Substring(raw)
        while let last = trimmed.last, last.unicodeScalars.allSatisfy({ $0.properties.isWhitespace }) {
            trimmed = trimmed.dropLast()
        }
        guard let last = lastGrapheme(String(trimmed)) else {
            storedGlyph = nil
            error = nil
            return
        }
        switch normalizeAvatarGlyph(last) {
        case .ok(let g):
            storedGlyph = g
            error = nil
        case .empty:
            storedGlyph = nil
            error = nil
        case .tooComplex:
            error = Self.tooComplexMessage
        case .invalid:
            break
        }
    }

    /// 选色:0…7 或 nil(自动);越界忽略。
    public mutating func pickColor(_ index: Int?) {
        if let index {
            guard (0..<8).contains(index) else { return }
            storedColor = index
        } else {
            storedColor = nil
        }
    }
}

/// 昵称输入框的「输入时截断」:输入法组合(marked text)进行中不动文本(返回 nil = 不改),
/// 组合结束后才按 `clampMyNameInput` 截断;返回 nil 也表示无需改写。
public func rewrittenNameInput(_ raw: String, isComposing: Bool) -> String? {
    if isComposing { return nil }
    let clamped = clampMyNameInput(raw)
    return clamped == raw ? nil : clamped
}
