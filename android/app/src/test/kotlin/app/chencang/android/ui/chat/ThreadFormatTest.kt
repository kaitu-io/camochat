package app.chencang.android.ui.chat

import app.chencang.shared.chat.ChatMessage
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadFormatTest {
    private fun msg(ts: Long) = ChatMessage("i$ts", "p", ChatMessage.DIRECTION_IN, "x", ts)

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 8, 11, 21, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val yesterday = now - 24 * 60 * 60 * 1000L

    private fun time(ms: Long, locale: Locale) =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun dateTime(ms: Long, locale: Locale) =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))

    @Test
    fun `first message and gaps over 10 minutes get a time chip`() {
        assertTrue(ThreadFormat.showTimeChip(null, msg(0)))
        assertTrue(ThreadFormat.showTimeChip(msg(0), msg(10 * 60 * 1000L + 1)))
        assertFalse(ThreadFormat.showTimeChip(msg(0), msg(10 * 60 * 1000L)))
    }

    @Test
    fun `same day shows the localized short time, another day the localized short date and time - US`() {
        assertEquals(time(now, Locale.US), ThreadFormat.chipText(now, now, Locale.US, zone))
        assertEquals(dateTime(yesterday, Locale.US), ThreadFormat.chipText(yesterday, now, Locale.US, zone))
    }

    @Test
    fun `same day shows the localized short time, another day the localized short date and time - zh`() {
        val zh = Locale.SIMPLIFIED_CHINESE
        assertEquals(time(now, zh), ThreadFormat.chipText(now, now, zh, zone))
        assertEquals(dateTime(yesterday, zh), ThreadFormat.chipText(yesterday, now, zh, zone))
    }

    @Test
    fun `the day boundary follows the given zone`() {
        // 00:10 in Shanghai is still the previous day in UTC: same day only in Shanghai.
        val justAfterMidnight = ZonedDateTime.of(2026, 8, 12, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        val earlier = justAfterMidnight - 20 * 60 * 1000L
        assertEquals(dateTime(earlier, Locale.US), ThreadFormat.chipText(earlier, justAfterMidnight, Locale.US, zone))
    }
}
