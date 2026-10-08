package app.chencang.shared.profile

/**
 * 头像色板格数。下标 0–7 ↔ token `avatar-1…avatar-8`，是跨端约定：只能在末尾追加，
 * 不能重排、不能删（spec §7.6）。
 */
const val AVATAR_PALETTE_SIZE = 8

/** 取不到本机指纹时「自动」底色用的固定种子。 */
private const val MY_AVATAR_FALLBACK_SEED = "me"

/** 联系人显示名为空时的头像字。 */
private const val EMPTY_NAME_GLYPH = "?"

/**
 * 确定性色板下标：FNV-1a 64 位（对 [seed] 的 UTF-8 字节）对 [paletteSize] 取模。
 * 与 iOS 同一算法（跨端用例表 P）——对方不发头像时，两台手机要对同一个指纹算出同一个颜色。
 */
fun avatarPaletteIndex(seed: String, paletteSize: Int): Int {
    var hash = 0xcbf29ce484222325uL
    for (b in seed.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toULong() and 0xffuL)
        hash *= 0x100000001b3uL
    }
    return (hash % paletteSize.toULong()).toInt()
}

/** 头像 = 圆 + 底色 + 一个字符。[paletteIndex] 是色板下标（0 起）。 */
data class AvatarSpec(val glyph: String, val paletteIndex: Int)

/** 联系人头像：显示名的第一个字素 + 按指纹（小写十六进制串）取色。 */
fun contactAvatar(displayName: String, fingerprintHex: String): AvatarSpec = AvatarSpec(
    glyph = firstGrapheme(displayName) ?: EMPTY_NAME_GLYPH,
    paletteIndex = avatarPaletteIndex(fingerprintHex.lowercase(), AVATAR_PALETTE_SIZE),
)

/**
 * 「我」的头像（跨端用例表 A）。字符：已存 → 昵称首字素 → [defaultGlyph]（调用方从 `me_avatar_default_glyph` 取）；
 * 下标：已存且在色板范围内 → 按本机指纹取色 → 取不到指纹用固定种子。
 */
fun myAvatar(
    storedGlyph: String?,
    storedColor: Int?,
    myName: String,
    myFingerprintHex: String?,
    defaultGlyph: String,
): AvatarSpec = AvatarSpec(
    glyph = storedGlyph?.takeIf { it.isNotEmpty() } ?: firstGrapheme(myName) ?: defaultGlyph,
    paletteIndex = storedColor?.takeIf { it in 0 until AVATAR_PALETTE_SIZE }
        ?: avatarPaletteIndex(
            myFingerprintHex?.takeIf { it.isNotEmpty() }?.lowercase() ?: MY_AVATAR_FALLBACK_SEED,
            AVATAR_PALETTE_SIZE,
        ),
)
