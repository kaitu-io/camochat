package app.chencang.shared.chat

import uniffi.chencang.decodeWire

/**
 * 裁决 R2：从一段粘贴/选中的文字里找出真正的 wire 行。
 *
 * 媒体消息的粘贴形态是两行（说明行也以 🔒 开头，见 R1），聊天软件还可能带上昵称、
 * 时间、引号行，或在同一行 🔒 前面加前缀。按行拆开，每行取**从第一个 🔒 起**的子串
 * （trim）为候选，没有 🔒 的行跳过；**从最后一行往前**逐个试 [decode]，第一个能解码的
 * 就是 wire。单行文字消息行为不变。
 */
object WireLocator {
    private const val LOCK = "🔒" // 🔒
    private val LINE_BREAK = Regex("\r?\n")

    /** Closing quotes / brackets a chat app or a user may leave after the wire (same set as iOS `IntakeClassifier`). */
    private const val TRAILING_JUNK = "\"'”’」』》)）"

    fun extract(text: String, decode: (String) -> ByteArray = ::decodeWire): String? {
        for (line in candidates(text).asReversed()) {
            if (runCatching { decode(line) }.isSuccess) return line
        }
        return null
    }

    /** Every line that has a 🔒, cut from its first 🔒, trailing whitespace and closing quotes / brackets
     *  removed; in text order (callers try them last first). */
    fun candidates(text: String): List<String> = text.split(LINE_BREAK).mapNotNull { line ->
        val at = line.indexOf(LOCK)
        if (at < 0) null else line.substring(at).trimEnd { it.isWhitespace() || it in TRAILING_JUNK }
    }
}
