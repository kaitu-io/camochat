package app.chencang.shared.profile

import android.icu.text.BreakIterator
import java.text.Normalizer

/** 昵称与邀请备注的上限：24 个 UTF-8 字节（= 8 个汉字或 24 个字母；约束来自配对消息体积，spec §7.3）。 */
const val MY_NAME_MAX_UTF8_BYTES = 24

/** 头像字符的上限：8 个 UTF-8 字节（带肤色的表情与国旗刚好 8；拼接的组合表情超限，spec §7.6）。 */
const val AVATAR_GLYPH_MAX_UTF8_BYTES = 8

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** 按字素（用户眼里的「一个字符」）切开：一个带肤色的表情、一面国旗、`e` + 组合重音都各算一个。 */
private fun graphemes(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out += s.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}

/** 第一个字素；空串返回 null。 */
fun firstGrapheme(s: String): String? = graphemes(s).firstOrNull()

/** 最后一个字素；空串返回 null。 */
fun lastGrapheme(s: String): String? = graphemes(s).lastOrNull()

/**
 * 保存前的昵称校验（跨端用例表 N）。返回 null = 拒绝；返回空串 = 回到「未设置」。
 * 顺序：裁首尾空白与换行 → 内部含控制字符（换行、制表符等）则拒绝，不是替换 → NFC → 超 24 字节拒绝。
 */
fun normalizeMyName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.any { Character.isISOControl(it) }) return null
    val nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
    if (nfc.utf8Size() > MY_NAME_MAX_UTF8_BYTES) return null
    return nfc
}

/**
 * 输入时的截断（跨端用例表 C）：超过 24 字节就不再接受新字符，按完整字素截，不切坏一个汉字或表情。
 * 不裁空白、不归一化——那是保存时 [normalizeMyName] 的事。
 */
fun clampMyNameInput(raw: String): String {
    if (raw.utf8Size() <= MY_NAME_MAX_UTF8_BYTES) return raw
    val out = StringBuilder()
    var bytes = 0
    for (g in graphemes(raw)) {
        bytes += g.utf8Size()
        if (bytes > MY_NAME_MAX_UTF8_BYTES) break
        out.append(g)
    }
    return out.toString()
}

sealed interface AvatarGlyphResult {
    /** 合法；[glyph] 已归一化为 NFC。 */
    data class Ok(val glyph: String) : AvatarGlyphResult

    /** 没填：头像字跟随昵称首字。 */
    data object Empty : AvatarGlyphResult

    /** 是一个字素但超过 8 字节（拼接的组合表情）：提示「这个表情太复杂，换一个」。 */
    data object TooComplex : AvatarGlyphResult

    /** 不止一个字素，或是空白 / 控制字符。 */
    data object Invalid : AvatarGlyphResult
}

/** 头像字符校验（跨端用例表 G）：恰好一个字素、非空白非控制字符、NFC、≤ 8 个 UTF-8 字节。 */
fun normalizeAvatarGlyph(raw: String): AvatarGlyphResult {
    if (raw.isEmpty()) return AvatarGlyphResult.Empty
    val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
    if (graphemes(nfc).size != 1) return AvatarGlyphResult.Invalid
    if (nfc.any { it.isWhitespace() || Character.isISOControl(it) }) return AvatarGlyphResult.Invalid
    if (nfc.utf8Size() > AVATAR_GLYPH_MAX_UTF8_BYTES) return AvatarGlyphResult.TooComplex
    return AvatarGlyphResult.Ok(nfc)
}
