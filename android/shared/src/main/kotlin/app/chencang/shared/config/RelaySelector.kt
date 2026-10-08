package app.chencang.shared.config

import android.content.SharedPreferences

/**
 * Orders the signed config's relay origins for [app.chencang.shared.media.MediaTransport]:
 * the relay that last worked goes first (if the config still lists it), the rest keep config order.
 */
class RelaySelector(
    private val relays: () -> List<String>,
    private val prefs: SharedPreferences,
) {
    fun ordered(): List<String> {
        val all = relays()
        val good = prefs.getString(KEY_GOOD, null)
        return if (good != null && good in all) listOf(good) + all.filter { it != good } else all
    }

    fun markGood(base: String) {
        if (prefs.getString(KEY_GOOD, null) == base) return
        prefs.edit().putString(KEY_GOOD, base).apply()
    }

    companion object {
        const val PREFS_NAME = "chencang_relay"
        private const val KEY_GOOD = "good"
    }
}
