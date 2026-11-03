package app.chencang.shared.moderation

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentFilterTest {
    private val filter = ContentFilter(listOf("ass", "傻逼"))

    @Test
    fun `parse skips comments and blanks and trims`() {
        assertEquals(listOf("foo", "傻逼"), ContentFilter.parse("# c\nfoo\n\n 傻逼 \n"))
    }

    @Test
    fun `latin words match whole words case-insensitively`() {
        assertTrue(filter.isFlagged("you ASS!"))
        assertFalse(filter.isFlagged("my class"))
        assertFalse(filter.isFlagged("hello"))
    }

    @Test
    fun `cjk words match as substrings`() {
        assertTrue(filter.isFlagged("你个傻逼啊"))
    }

    @Test
    fun `empty text is not flagged`() {
        assertFalse(filter.isFlagged(""))
    }

    @Test
    fun `bundled asset list loads and flags its first word`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val raw = ctx.assets.open("moderation_wordlist.txt").bufferedReader().readText()
        val first = ContentFilter.parse(raw).first()
        assertTrue(ContentFilter.fromAssets(ctx).isFlagged(first))
    }

    @Test
    fun `runs of whitespace are normalized`() {
        assertTrue(ContentFilter(listOf("kill yourself")).isFlagged("kill   yourself"))
    }

    @Test
    fun `shipped list does not flag ordinary words`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(ContentFilter.fromAssets(ctx).isFlagged("个性交流"))
    }

    @Test
    fun `ideographic space is normalized`() {
        assertTrue(ContentFilter(listOf("kill yourself")).isFlagged("kill　yourself"))
    }
}
