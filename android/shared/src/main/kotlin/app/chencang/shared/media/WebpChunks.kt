package app.chencang.shared.media

/**
 * 扫 WebP 的 RIFF 块，找出可能带位置等元数据的 `EXIF` / `XMP ` 块。
 * 校验器失败即拦：输出不是 RIFF/WEBP（含过短）时返回 [NOT_WEBP]，不放行。
 */
object WebpChunks {
    const val NOT_WEBP = "NOT_WEBP"
    private val PRIVACY = setOf("EXIF", "XMP ")

    fun privacyChunks(webp: ByteArray): List<String> {
        if (webp.size < 12) return listOf(NOT_WEBP)
        fun tag(at: Int) = String(webp, at, 4, Charsets.ISO_8859_1)
        if (tag(0) != "RIFF" || tag(8) != "WEBP") return listOf(NOT_WEBP)
        val found = mutableListOf<String>()
        var pos = 12L
        while (pos + 8 <= webp.size) {
            val i = pos.toInt()
            val size = (webp[i + 4].toLong() and 0xFF) or
                ((webp[i + 5].toLong() and 0xFF) shl 8) or
                ((webp[i + 6].toLong() and 0xFF) shl 16) or
                ((webp[i + 7].toLong() and 0xFF) shl 24)
            val t = tag(i)
            if (t in PRIVACY) found += t
            pos += 8 + size + (size and 1)
        }
        return found
    }
}
