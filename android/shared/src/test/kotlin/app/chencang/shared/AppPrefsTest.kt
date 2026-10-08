package app.chencang.shared

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPrefsTest {
    @Test
    fun `summaryPrivacy defaults false and persists`() {
        val a = AppPrefs(ApplicationProvider.getApplicationContext())
        assertEquals(false, a.summaryPrivacy.value)
        a.setSummaryPrivacy(true)
        assertEquals(true, AppPrefs(ApplicationProvider.getApplicationContext()).summaryPrivacy.value)
    }

    @Test
    fun `sealAction defaults to share and persists the last choice`() {
        val a = AppPrefs(ApplicationProvider.getApplicationContext())
        assertEquals(AppPrefs.SealAction.SHARE, a.sealAction.value)
        a.setSealAction(AppPrefs.SealAction.COPY)
        assertEquals(AppPrefs.SealAction.COPY, AppPrefs(ApplicationProvider.getApplicationContext()).sealAction.value)
        a.setSealAction(AppPrefs.SealAction.SHARE)
        assertEquals(AppPrefs.SealAction.SHARE, AppPrefs(ApplicationProvider.getApplicationContext()).sealAction.value)
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun rawPrefs() = context.getSharedPreferences("chencang_app_prefs", Context.MODE_PRIVATE)

    @Test
    fun `profile is not loaded until loadProfile runs`() = runTest {
        AppPrefs(context).apply {
            setMyName("阿青")
            setMyAvatarGlyph("山")
            setMyAvatarColor(3)
        }
        val fresh = AppPrefs(context)
        assertFalse(fresh.profileLoaded.value)
        assertEquals("", fresh.myName.value)
        assertEquals("", fresh.myAvatarGlyph.value)
        assertNull(fresh.myAvatarColor.value)

        fresh.loadProfile()
        assertTrue(fresh.profileLoaded.value)
        assertEquals("阿青", fresh.myName.value)
    }

    @Test
    fun `setMyName persists across instances after loadProfile`() = runTest {
        val a = AppPrefs(context)
        a.setMyName("阿青")
        assertEquals("阿青", a.myName.value)
        assertEquals("阿青", rawPrefs().getString("my_name", null))

        val b = AppPrefs(context).also { it.loadProfile() }
        assertEquals("阿青", b.myName.value)

        b.setMyName("")
        assertEquals("", AppPrefs(context).also { it.loadProfile() }.myName.value)
    }

    @Test
    fun `avatar glyph and color persist`() = runTest {
        val a = AppPrefs(context)
        a.setMyAvatarGlyph("山")
        a.setMyAvatarColor(3)
        assertEquals("山", a.myAvatarGlyph.value)
        assertEquals(3, a.myAvatarColor.value)
        assertEquals("山", rawPrefs().getString("my_avatar_glyph", null))
        assertEquals(3, rawPrefs().getInt("my_avatar_color", -1))

        val b = AppPrefs(context).also { it.loadProfile() }
        assertEquals("山", b.myAvatarGlyph.value)
        assertEquals(3, b.myAvatarColor.value)
    }

    @Test
    fun `setMyAvatarColor(null) removes the key`() = runTest {
        val a = AppPrefs(context)
        a.setMyAvatarColor(0)
        assertTrue(rawPrefs().contains("my_avatar_color"))
        a.setMyAvatarColor(null)
        assertNull(a.myAvatarColor.value)
        assertFalse(rawPrefs().contains("my_avatar_color"))
        assertNull(AppPrefs(context).also { it.loadProfile() }.myAvatarColor.value)
    }

    @Test
    fun `clearMyProfile resets all three and keeps summaryPrivacy`() = runTest {
        val a = AppPrefs(context)
        a.setSummaryPrivacy(true)
        a.setMyName("阿青")
        a.setMyAvatarGlyph("山")
        a.setMyAvatarColor(3)

        a.clearMyProfile()

        assertEquals("", a.myName.value)
        assertEquals("", a.myAvatarGlyph.value)
        assertNull(a.myAvatarColor.value)
        listOf("my_name", "my_avatar_glyph", "my_avatar_color").forEach { assertFalse(it, rawPrefs().contains(it)) }
        val b = AppPrefs(context).also { it.loadProfile() }
        assertEquals("", b.myName.value)
        assertEquals("", b.myAvatarGlyph.value)
        assertNull(b.myAvatarColor.value)
        assertEquals(true, b.summaryPrivacy.value)
    }

    @Test
    fun `namePromptDone defaults false, persists, and is reset by clearMyProfile`() {
        val a = AppPrefs(context)
        assertFalse(a.namePromptDone)
        a.setNamePromptDone(true)
        assertTrue(AppPrefs(context).namePromptDone)
        a.clearMyProfile()
        assertFalse(AppPrefs(context).namePromptDone)
    }
}
