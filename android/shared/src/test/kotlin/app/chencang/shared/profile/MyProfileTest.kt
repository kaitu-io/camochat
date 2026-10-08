package app.chencang.shared.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 跨端用例表 N / C / G（计划头部），期望值与 iOS 逐条对应。Robolectric：字素切分用 `android.icu`。 */
@RunWith(RobolectricTestRunner::class)
class MyProfileTest {
    private val family = "👨‍👩‍👧‍👦"
    private val thumbsMedium = "👍🏽"
    private val flagCn = "🇨🇳"

    @Test
    fun `normalizeMyName table N`() {
        assertEquals("阿青", normalizeMyName("  阿青\n"))
        assertEquals("", normalizeMyName(""))
        assertEquals("", normalizeMyName("   "))
        assertNull(normalizeMyName("阿\n青"))
        assertNull(normalizeMyName("阿\t青"))
        assertNull(normalizeMyName("a\u0000b"))
        assertEquals("é", normalizeMyName("é"))
        assertEquals(2, normalizeMyName("é")!!.toByteArray(Charsets.UTF_8).size)
        assertEquals("一二三四五六七八", normalizeMyName("一二三四五六七八"))
        assertNull(normalizeMyName("一二三四五六七八九"))
        assertEquals("a".repeat(24), normalizeMyName("a".repeat(24)))
        assertNull(normalizeMyName("a".repeat(25)))
        assertNull(normalizeMyName("一二三四五六七😀"))
    }

    @Test
    fun `clampMyNameInput table C`() {
        assertEquals("一二三四五六七八", clampMyNameInput("一二三四五六七八九"))
        assertEquals("一二三四五六七", clampMyNameInput("一二三四五六七😀"))
        assertEquals("a".repeat(22), clampMyNameInput("a".repeat(22) + thumbsMedium))
        assertEquals("字".repeat(8), clampMyNameInput("字".repeat(100)))
    }

    @Test
    fun `clampMyNameInput leaves short input and its whitespace untouched`() {
        assertEquals(" 阿青 ", clampMyNameInput(" 阿青 "))
        assertEquals("", clampMyNameInput(""))
    }

    @Test
    fun `normalizeAvatarGlyph table G`() {
        assertEquals(8, thumbsMedium.toByteArray(Charsets.UTF_8).size)
        assertEquals(8, flagCn.toByteArray(Charsets.UTF_8).size)
        listOf("青", "A", "7", "😀", thumbsMedium, flagCn).forEach {
            assertEquals(AvatarGlyphResult.Ok(it), normalizeAvatarGlyph(it))
        }
        assertEquals(AvatarGlyphResult.Ok("é"), normalizeAvatarGlyph("é"))
        assertEquals(AvatarGlyphResult.TooComplex, normalizeAvatarGlyph(family))
        assertEquals(AvatarGlyphResult.Invalid, normalizeAvatarGlyph("青山"))
        assertEquals(AvatarGlyphResult.Invalid, normalizeAvatarGlyph(" "))
        assertEquals(AvatarGlyphResult.Invalid, normalizeAvatarGlyph("\n"))
        assertEquals(AvatarGlyphResult.Empty, normalizeAvatarGlyph(""))
    }

    @Test
    fun `first and last grapheme`() {
        assertEquals("山", lastGrapheme("青山"))
        assertEquals(thumbsMedium, firstGrapheme("${thumbsMedium}老周"))
        assertNull(firstGrapheme(""))
        assertNull(lastGrapheme(""))
        assertEquals(family, firstGrapheme("${family}一家"))
        assertEquals(family, lastGrapheme("一家$family"))
    }
}
