import Foundation

/// 印章头像 = 一个字 + 色板下标(0–7 ↔ avatar-1…avatar-8)。
public struct AvatarSpec: Equatable {
    public let glyph: String
    public let paletteIndex: Int

    public init(glyph: String, paletteIndex: Int) {
        self.glyph = glyph
        self.paletteIndex = paletteIndex
    }
}

private let avatarPaletteSize = 8
/// 取不到本机指纹时的固定种子(跨端约定)。
private let myAvatarFallbackSeed = "me"

/// 联系人头像:显示名首字素 + 指纹取色。
public func contactAvatar(displayName: String, fingerprintHex: String) -> AvatarSpec {
    AvatarSpec(
        glyph: firstGrapheme(displayName) ?? "?",
        paletteIndex: avatarPaletteIndex(seed: fingerprintHex.lowercased(), paletteSize: avatarPaletteSize)
    )
}

/// 我的头像(表 A):字 = 已存 → 昵称首字素 → 「我」;
/// 底色 = 已存且在 0…7 → 按本机指纹 → 固定种子 "me"。
public func myAvatar(storedGlyph: String?, storedColor: Int?, myName: String, myFingerprintHex: String?) -> AvatarSpec {
    let glyph: String
    if let g = storedGlyph, !g.isEmpty {
        glyph = g
    } else {
        glyph = firstGrapheme(myName) ?? L10n.meAvatarDefaultGlyph
    }
    let index: Int
    if let c = storedColor, (0..<avatarPaletteSize).contains(c) {
        index = c
    } else {
        index = avatarPaletteIndex(seed: myFingerprintHex.flatMap { $0.isEmpty ? nil : $0.lowercased() } ?? myAvatarFallbackSeed, paletteSize: avatarPaletteSize)
    }
    return AvatarSpec(glyph: glyph, paletteIndex: index)
}
