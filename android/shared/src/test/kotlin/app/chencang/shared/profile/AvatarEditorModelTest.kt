package app.chencang.shared.profile

import android.app.Application
import app.chencang.shared.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 规 E / 表 E（字素切分用 android.icu，故走 Robolectric）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AvatarEditorModelTest {
    private fun model(glyph: String? = null, color: Int? = null, name: String = "", fp: String? = null) =
        AvatarEditorModel(storedGlyph = glyph, storedColor = color, myName = name, myFingerprintHex = fp, defaultGlyph = "M")

    @Test
    fun `E1 typing two characters keeps only the last grapheme`() {
        assertEquals("山", model().inputGlyph("青山").storedGlyph)
    }

    @Test
    fun `E2 clearing the input follows the first grapheme of the name`() {
        val m = model(glyph = "山", name = "阿青").inputGlyph("")
        assertEquals("", m.storedGlyph)
        assertEquals("阿", m.preview.glyph)
    }

    @Test
    fun `E3 too complex emoji keeps the stored glyph and sets an error that a valid input clears`() {
        val m = model(glyph = "山").inputGlyph("👨‍👩‍👧‍👦")
        assertEquals("山", m.storedGlyph)
        assertEquals(R.string.me_avatar_too_complex, m.errorRes)
        val ok = m.inputGlyph("青")
        assertEquals("青", ok.storedGlyph)
        assertNull(ok.errorRes)
    }

    @Test
    fun `E4 picking a color and picking auto`() {
        val fp = "3f9c1e7a5b2d4f608192a3b4c5d6e7f8"
        val picked = model(fp = fp).pickColor(3)
        assertEquals(3, picked.storedColor)
        assertEquals(3, picked.preview.paletteIndex)
        val auto = picked.pickColor(null)
        assertNull(auto.storedColor)
        assertEquals(avatarPaletteIndex(fp, AVATAR_PALETTE_SIZE), auto.preview.paletteIndex)
    }

    @Test
    fun `E5 a stored glyph survives a name change`() {
        assertEquals("山", model(glyph = "山", name = "老周").preview.glyph)
    }

    @Test
    fun `placeholder follows the name then falls back to the default glyph`() {
        assertEquals("阿", model(name = "阿青").placeholderGlyph)
        assertEquals("M", model().placeholderGlyph)
    }

    @Test
    fun `composing input is neither normalized nor stored, the final text is`() {
        val start = model(glyph = "山")
        val mid = start.inputGlyph("sh", isComposing = true)
        assertEquals("山", mid.storedGlyph)
        assertEquals(start, mid)
        assertEquals("善", mid.inputGlyph("善", isComposing = false).storedGlyph)
    }

    @Test
    fun `composing a complex emoji does not raise the error`() {
        val m = model(glyph = "山").inputGlyph("👨‍👩‍👧‍👦", isComposing = true)
        assertNull(m.errorRes)
        assertEquals("山", m.storedGlyph)
    }

    @Test
    fun `trailing whitespace is trimmed before taking the last grapheme`() {
        assertEquals("山", model().inputGlyph("山\n").storedGlyph)
        assertEquals("山", model().inputGlyph("青山 \n").storedGlyph)
    }

    @Test
    fun `whitespace only input clears the glyph`() {
        val m = model(glyph = "山").inputGlyph("\n")
        assertEquals("", m.storedGlyph)
        assertNull(m.errorRes)
    }

    @Test
    fun `pickColor ignores out of range indexes`() {
        val m = model(color = 2)
        assertEquals(2, m.pickColor(8).storedColor)
        assertEquals(2, m.pickColor(-1).storedColor)
        assertEquals(7, m.pickColor(7).storedColor)
        assertEquals(0, m.pickColor(0).storedColor)
    }

    @Test
    fun `rewrittenNameInput leaves composing text alone and clamps final text`() {
        assertNull(rewrittenNameInput("一二三四五六七八九", isComposing = true))
        assertEquals("一二三四五六七八", rewrittenNameInput("一二三四五六七八九", isComposing = false))
        assertNull(rewrittenNameInput("阿青", isComposing = false))
    }
}
