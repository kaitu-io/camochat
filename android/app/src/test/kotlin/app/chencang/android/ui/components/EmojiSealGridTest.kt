package app.chencang.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiSealGridTest {

    @Test
    fun `eight emojis split into two rows of four`() {
        val emojis = listOf("🌊", "🔥", "🌙", "⭐", "🍀", "🌸", "🐚", "🦋")

        val rows = sealGridRows(emojis)

        assertEquals(listOf(emojis.subList(0, 4), emojis.subList(4, 8)), rows)
    }

    @Test
    fun `fewer than eight falls back to a single row`() {
        val emojis = listOf("🌊", "🔥", "🌙")

        val rows = sealGridRows(emojis)

        assertEquals(listOf(emojis), rows)
    }

    @Test
    fun `empty list yields no rows`() {
        val rows = sealGridRows(emptyList())

        assertEquals(emptyList<List<String>>(), rows)
    }
}
