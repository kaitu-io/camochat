import Foundation

/// 对方昵称是不可信的展示文本（来自邀请 / 回执）：去控制字符与双向控制符，裁空白，
/// 按本机昵称同一上限（`clampMyNameInput`）截断；清洗后为空视为「没有名字」。
/// 与 Android `PeerName.sanitize` 同规则。
public enum PeerName {
    private static let zwj: Unicode.Scalar = "\u{200D}" // 表情序列（👨‍👩‍👧）靠它连接，保留

    public static func sanitize(_ raw: String?) -> String? {
        guard let raw else { return nil }
        var kept = String.UnicodeScalarView()
        for s in raw.unicodeScalars where !isStripped(s) { kept.append(s) }
        let trimmed = String(kept).trimmingCharacters(in: .whitespacesAndNewlines)
        let clamped = clampMyNameInput(trimmed).trimmingCharacters(in: .whitespacesAndNewlines)
        return clamped.isEmpty ? nil : clamped
    }

    /// 控制字符（Cc）、格式控制符（Cf：双向控制符、零宽字符、BOM、U+061C）与行/段分隔符；ZWJ 例外。
    private static func isStripped(_ s: Unicode.Scalar) -> Bool {
        if s == zwj { return false }
        switch s.properties.generalCategory {
        case .control, .format, .lineSeparator, .paragraphSeparator: return true
        default: return false
        }
    }
}
