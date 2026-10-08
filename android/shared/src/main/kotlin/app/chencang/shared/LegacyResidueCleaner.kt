package app.chencang.shared

import android.content.Context
import java.io.File

/**
 * One-shot cleanup of on-disk residue from the retired keyboard / neural-voice
 * form factor (2026-09-24). Idempotent via a marker in SharedPreferences.
 *
 * Deletes:
 * - `cc-voice.db` (retired voice-history Room database)
 * - the `chencang_voice_prefs` DataStore file
 * - `files/voices/` (locally recorded/received voice PCM)
 * - `files/models/` (keyboard-era TFLite models copied out of assets, ~90 MB)
 */
object LegacyResidueCleaner {
    private const val PREFS = "chencang_legacy_residue"
    private const val MARKER = "cleaned_v2"

    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MARKER, false)) return
        context.deleteDatabase("cc-voice.db")
        File(context.filesDir, "datastore/chencang_voice_prefs.preferences_pb").delete()
        File(context.filesDir, "voices").deleteRecursively()
        File(context.filesDir, "models").deleteRecursively()
        prefs.edit().putBoolean(MARKER, true).apply()
    }
}
