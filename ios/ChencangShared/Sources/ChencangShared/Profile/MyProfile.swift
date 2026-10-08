import Foundation

/// 「我」的资料(昵称、印章头像)的校验与存储键。只存本机,不进握手、不进日志、不进备份。
/// 规则与 Android 逐条一致(跨端用例表 N / C / G)。

/// 昵称字节上限(UTF-8)。
private let myNameMaxBytes = 24
/// 头像字符字节上限(UTF-8)。
private let avatarGlyphMaxBytes = 8

/// 提交时的昵称规范化。返回 nil = 拒绝(含控制字符或超过 24 字节);空串合法(= 未设置)。
public func normalizeMyName(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.unicodeScalars.contains(where: { $0.properties.generalCategory == .control }) {
        return nil
    }
    let nfc = trimmed.precomposedStringWithCanonicalMapping
    return nfc.utf8.count > myNameMaxBytes ? nil : nfc
}

/// 输入时的截断:逐字素累加字节,不超过 24 字节;不裁空白。
public func clampMyNameInput(_ raw: String) -> String {
    var out = ""
    var bytes = 0
    for ch in raw {
        let n = String(ch).utf8.count
        if bytes + n > myNameMaxBytes { break }
        out.append(ch)
        bytes += n
    }
    return out
}

public enum AvatarGlyphResult: Equatable {
    case ok(String)
    case empty
    case tooComplex
    case invalid
}

/// 头像字符校验:NFC 后恰好一个字素且 ≤ 8 字节。空白 / 控制字符不算字符。
public func normalizeAvatarGlyph(_ raw: String) -> AvatarGlyphResult {
    if raw.isEmpty { return .empty }
    let nfc = raw.precomposedStringWithCanonicalMapping
    guard nfc.count == 1, let ch = nfc.first else { return .invalid }
    // 含任一空白 / 控制标量即非法(与 Android `any` 一致)。
    let blank = ch.unicodeScalars.contains {
        $0.properties.isWhitespace || $0.properties.generalCategory == .control
    }
    if blank { return .invalid }
    return nfc.utf8.count > avatarGlyphMaxBytes ? .tooComplex : .ok(nfc)
}

public func firstGrapheme(_ s: String) -> String? {
    s.first.map { String($0) }
}

public func lastGrapheme(_ s: String) -> String? {
    s.last.map { String($0) }
}

/// 「我」资料在 App Group 里的键(逐字,跨进程共享)。
public enum MyProfileKeys {
    public static let name = "cc.myName.v1"
    public static let avatarGlyph = "cc.myAvatarGlyph.v1"
    public static let avatarColor = "cc.myAvatarColor.v1"
    /// 「你的名字」问过了(继续或跳过都算)。与昵称同一份 App Group 存储,删除账号时一并重置。
    public static let namePromptDone = "cc.namePromptDone.v1"

    public static func isNamePromptDone(defaults: UserDefaults?) -> Bool {
        defaults?.bool(forKey: namePromptDone) ?? false
    }

    /// 记下「你的名字」的回答:`name` 非空 → 存昵称;nil(跳过 / 空输入)→ 不动昵称。两种都标记为问过。
    public static func recordNamePromptAnswer(_ name: String?, defaults: UserDefaults?) {
        guard let defaults else { return }
        if let name, !name.isEmpty { defaults.set(name, forKey: self.name) }
        defaults.set(true, forKey: namePromptDone)
    }

    /// 现存的昵称(没设或 App Group 不可用 → 空串)。配对发出的邀请 / 回执带的就是它。
    public static func storedName(defaults: UserDefaults?) -> String {
        defaults?.string(forKey: name) ?? ""
    }

    /// 删除账号时清掉资料键(昵称、头像两键、问过名字标记);`defaults` 为空(App Group 不可用)则什么都不做。
    public static func clear(defaults: UserDefaults?) {
        guard let defaults else { return }
        for key in [name, avatarGlyph, avatarColor, namePromptDone] {
            defaults.removeObject(forKey: key)
        }
    }
}
