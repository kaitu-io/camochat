package app.chencang.shared.moderation

import android.content.Context

/**
 * Local bad-word filter for incoming text. Words containing CJK characters match as
 * substrings; purely Latin words match whole words only. Case-insensitive.
 */
class ContentFilter(words: List<String>) {
    private val cjkWords: List<String>
    private val latinRegex: Regex?

    init {
        val lowered = words.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
        val (cjk, latin) = lowered.partition { w -> w.any { isCjk(it) } }
        cjkWords = cjk
        latinRegex = if (latin.isEmpty()) null else Regex(
            "(?<![\\p{L}\\p{N}_])(?:" + latin.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}_])",
        )
    }

    fun isFlagged(text: String): Boolean {
        if (text.isEmpty()) return false
        val t = normalize(text)
        if (cjkWords.any { t.contains(it) }) return true
        return latinRegex?.containsMatchIn(t) == true
    }

    companion object {
        private const val ASSET = "moderation_wordlist.txt"

        private val WS = Regex("[\\s\\p{Z}]+")

        private fun normalize(s: String): String = s.trim().lowercase().replace(WS, " ")

        private fun isCjk(c: Char): Boolean =
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

        fun parse(raw: String): List<String> =
            raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

        fun fromAssets(context: Context): ContentFilter {
            val raw = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return ContentFilter(parse(raw))
        }
    }
}
