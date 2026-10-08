package app.chencang.shared.profile

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 跨端用例表 P / A（计划头部）。Robolectric：首字素切分用 `android.icu`。 */
@RunWith(RobolectricTestRunner::class)
class AvatarSpecTest {
    private val myFp = "3f9c1e7a5b2d4f608192a3b4c5d6e7f8" // 表 P：→ 1

    @Test
    fun `palette index table P`() {
        assertEquals(4, avatarPaletteIndex("a", 8))
        assertEquals(7, avatarPaletteIndex("me", 8))
        assertEquals(5, avatarPaletteIndex("", 8))
        assertEquals(5, avatarPaletteIndex("0123456789abcdef0123456789abcdef", 8))
        assertEquals(1, avatarPaletteIndex("3f9c1e7a5b2d4f608192a3b4c5d6e7f8", 8))
    }

    @Test
    fun `contact avatar takes first grapheme`() {
        assertEquals(AvatarSpec("👍🏽", 1), contactAvatar("👍🏽老周", myFp))
        assertEquals(AvatarSpec("老", 5), contactAvatar("老周", "0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `contact avatar seed is the lowercase fingerprint`() {
        assertEquals(contactAvatar("老周", myFp), contactAvatar("老周", myFp.uppercase()))
    }

    @Test
    fun `contact avatar with an empty name still has a glyph`() {
        assertEquals("?", contactAvatar("", myFp).glyph)
    }

    @Test
    fun `my avatar table A`() {
        // 全空且无指纹 → 传入的默认字 + 固定种子 "me"
        assertEquals(AvatarSpec("M", 7), myAvatar(null, null, "", null, defaultGlyph = "M"))
        assertEquals(AvatarSpec("M", 7), myAvatar("", null, "", null, defaultGlyph = "M"))
        // 昵称「阿青」→ 字「阿」
        assertEquals("阿", myAvatar(null, null, "阿青", null, "M").glyph)
        // 已存字「山」、昵称改「老周」→ 仍「山」
        assertEquals("山", myAvatar("山", null, "老周", null, "M").glyph)
        // 已存下标 3 → 3
        assertEquals(3, myAvatar(null, 3, "", myFp, "M").paletteIndex)
        // 已存下标 9（越界）→ 按指纹
        assertEquals(1, myAvatar(null, 9, "", myFp, "M").paletteIndex)
        assertEquals(1, myAvatar(null, -1, "", myFp, "M").paletteIndex)
        // 没存下标 → 按指纹
        assertEquals(1, myAvatar(null, null, "", myFp, "M").paletteIndex)
    }
}
