package app.chencang.shared.config

import android.content.SharedPreferences

/** Persisted last-accepted config envelope plus the last successful refresh time. */
class ConfigStore(private val prefs: SharedPreferences) {
    fun envelope(): String? = prefs.getString(KEY_ENVELOPE, null)

    fun saveEnvelope(e: String) {
        prefs.edit().putString(KEY_ENVELOPE, e).apply()
    }

    fun lastSuccessAt(): Long = prefs.getLong(KEY_LAST_SUCCESS, 0L)

    fun markSuccess(at: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESS, at).apply()
    }

    companion object {
        const val PREFS_NAME = "chencang_config"
        private const val KEY_ENVELOPE = "envelope"
        private const val KEY_LAST_SUCCESS = "last_success_at"
    }
}
