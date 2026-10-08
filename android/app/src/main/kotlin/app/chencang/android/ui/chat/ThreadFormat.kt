package app.chencang.android.ui.chat

import app.chencang.shared.chat.ChatMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Thread time chips (pure, testable): a chip after a gap over 10 min; same day = short time, otherwise short date + time. */
object ThreadFormat {
    private const val GAP_MS = 10 * 60 * 1000L

    fun showTimeChip(prev: ChatMessage?, cur: ChatMessage): Boolean =
        prev == null || cur.timestamp - prev.timestamp > GAP_MS

    /** Formatted in [locale]'s own short style; "same day" is judged in [zone]. */
    fun chipText(timestampMs: Long, nowMs: Long, locale: Locale, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val sameDay = t.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val formatter = if (sameDay) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }
        return formatter.withLocale(locale).format(t)
    }
}
