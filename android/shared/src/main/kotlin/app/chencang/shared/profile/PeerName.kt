package app.chencang.shared.profile

/**
 * 对方昵称是不可信的展示文本（来自邀请 / 回执）：去控制字符与双向控制符，裁空白，
 * 按本机昵称同一上限（[clampMyNameInput]）截断；清洗后为空视为「没有名字」。
 */
object PeerName {
    private const val ZWJ = 0x200D // 表情序列（👨‍👩‍👧）靠它连接，保留

    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val sb = StringBuilder()
        raw.codePoints().forEach { cp ->
            if (!Character.isISOControl(cp) && !isInvisibleFormat(cp)) sb.appendCodePoint(cp)
        }
        val stripped = sb.toString()
        val clamped = clampMyNameInput(stripped.trim()).trim()
        return clamped.ifEmpty { null }
    }

    /** 格式控制符（Cf，含增补平面的 U+E0001 / 标签字符，含双向控制符、零宽字符、BOM、U+061C）与行/段分隔符；ZWJ 例外。 */
    private fun isInvisibleFormat(cp: Int): Boolean {
        if (cp == ZWJ) return false
        return when (Character.getType(cp).toByte()) {
            Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }
}
