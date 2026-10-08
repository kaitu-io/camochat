package app.chencang.android.update

import android.content.SharedPreferences

class UpdatePrefs(private val prefs: SharedPreferences) {
    fun snooze(): Snooze? {
        if (!prefs.contains(KEY_CODE)) return null
        return Snooze(prefs.getInt(KEY_CODE, 0), prefs.getLong(KEY_AT, 0L))
    }

    fun setSnooze(s: Snooze) {
        prefs.edit().putInt(KEY_CODE, s.versionCode).putLong(KEY_AT, s.at).apply()
    }

    companion object {
        const val NAME = "chencang_update"
        private const val KEY_CODE = "snooze_version_code"
        private const val KEY_AT = "snooze_at"
    }
}
