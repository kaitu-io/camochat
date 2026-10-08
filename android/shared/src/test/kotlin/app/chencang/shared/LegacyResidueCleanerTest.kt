package app.chencang.shared

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LegacyResidueCleanerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `deletes voice db, prefs, voices dir and models dir once`() {
        ctx.openOrCreateDatabase("cc-voice.db", Context.MODE_PRIVATE, null).close()
        val prefs = File(ctx.filesDir, "datastore/chencang_voice_prefs.preferences_pb").apply {
            parentFile!!.mkdirs(); writeText("x")
        }
        val voices = File(ctx.filesDir, "voices").apply { mkdirs(); File(this, "a.opus").writeText("x") }
        val models = File(ctx.filesDir, "models").apply {
            mkdirs(); File(this, "MimiEncoder25_tanh_drq.tflite").writeText("x")
        }

        LegacyResidueCleaner.runOnce(ctx)

        assertFalse(ctx.getDatabasePath("cc-voice.db").exists())
        assertFalse(prefs.exists())
        assertFalse(voices.exists())
        assertFalse(models.exists())

        // Marker set → second run leaves a re-created file alone.
        voices.mkdirs()
        LegacyResidueCleaner.runOnce(ctx)
        assertTrue(voices.exists())
    }
}
