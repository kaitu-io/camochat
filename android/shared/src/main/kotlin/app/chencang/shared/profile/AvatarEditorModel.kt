package app.chencang.shared.profile

import androidx.annotation.StringRes
import app.chencang.shared.R

/**
 * 头像编辑的纯值模型（规 E / 表 E）；界面只做绑定，选择即存。
 * [storedGlyph] 空串或 null = 跟随昵称首字；[storedColor] null = 自动（按本机指纹取色）。
 */
data class AvatarEditorModel(
    val storedGlyph: String?,
    val storedColor: Int?,
    val myName: String,
    val myFingerprintHex: String?,
    val defaultGlyph: String,
    @StringRes val errorRes: Int? = null,
) {
    val preview: AvatarSpec get() = myAvatar(storedGlyph, storedColor, myName, myFingerprintHex, defaultGlyph)

    /** 输入框的占位：没存头像字时预览会画的那个字（昵称首字，否则 [defaultGlyph]）。 */
    val placeholderGlyph: String get() = myAvatar(null, storedColor, myName, myFingerprintHex, defaultGlyph).glyph

    /**
     * 输入一段文字。[isComposing]（输入法组合中，如拼「shan」的中间字母）时什么都不做：
     * 组合中间态不是最终选择，不规整、不报错、不落盘；组合结束后再以最终文本调一次。
     * 先去掉尾随空白/换行（粘贴「山\n」），再只留最后一个字素：空 = 清除，跟随昵称；
     * 太复杂 = 字不变并给出错误；其余无效输入忽略。合法输入清掉旧错误。
     */
    fun inputGlyph(raw: String, isComposing: Boolean = false): AvatarEditorModel {
        if (isComposing) return this
        val last = lastGrapheme(raw.trimEnd()) ?: return copy(storedGlyph = "", errorRes = null)
        return when (val r = normalizeAvatarGlyph(last)) {
            is AvatarGlyphResult.Ok -> copy(storedGlyph = r.glyph, errorRes = null)
            AvatarGlyphResult.Empty -> copy(storedGlyph = "", errorRes = null)
            AvatarGlyphResult.TooComplex -> copy(errorRes = R.string.me_avatar_too_complex)
            AvatarGlyphResult.Invalid -> this
        }
    }

    /** 选色板下标 0…7；null = 自动；越界忽略。 */
    fun pickColor(index: Int?): AvatarEditorModel =
        if (index == null || index in 0 until AVATAR_PALETTE_SIZE) copy(storedColor = index) else this
}

/**
 * 昵称输入框的「输入时截断」：组合中返回 null（不改）；组合结束后按 [clampMyNameInput] 截断，
 * 返回 null 也表示无需改写。
 */
fun rewrittenNameInput(raw: String, isComposing: Boolean): String? {
    if (isComposing) return null
    val clamped = clampMyNameInput(raw)
    return if (clamped == raw) null else clamped
}
