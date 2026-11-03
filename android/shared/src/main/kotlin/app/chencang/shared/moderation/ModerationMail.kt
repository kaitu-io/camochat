package app.chencang.shared.moderation

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Emails the user can choose to send to the developer about abuse. The app never sends
 * anything itself: these are drafts handed to the user's own mail app.
 */
object ModerationMail {
    const val MODERATION_EMAIL = "hi@allnationconnect.com"

    data class Mail(val to: String, val subject: String, val body: String)

    /** Fingerprint characters included in a mail: enough to identify, not the whole key. */
    private const val FINGERPRINT_CHARS = 16

    /** "I blocked this contact" notice. [nowMillis] is written as ISO-8601 UTC, to the second. */
    fun blockNotice(displayName: String, fingerprintHex: String, nowMillis: Long, appVersion: String): Mail =
        Mail(
            to = MODERATION_EMAIL,
            subject = "CamoChat block notice / 拉黑通知",
            body = listOf(
                "Blocked contact: $displayName",
                "Fingerprint: ${fingerprintHex.take(FINGERPRINT_CHARS)}",
                "Time (UTC): ${Instant.ofEpochMilli(nowMillis).truncatedTo(ChronoUnit.SECONDS)}",
                "App: CamoChat $appVersion Android",
            ).joinToString("\n", postfix = "\n"),
        )
}
