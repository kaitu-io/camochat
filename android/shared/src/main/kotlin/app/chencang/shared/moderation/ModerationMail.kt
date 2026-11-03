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

    /**
     * Report of one incoming message. Text messages carry their text; media messages carry the
     * blob ids so the developer can have the files removed from the relay.
     */
    fun report(
        displayName: String,
        fingerprintHex: String,
        message: app.chencang.shared.chat.ChatMessage,
        blobIds: List<String>,
        appVersion: String,
    ): Mail {
        val lines = mutableListOf(
            "Reported contact: $displayName",
            "Fingerprint: ${fingerprintHex.take(FINGERPRINT_CHARS)}",
            "Message time (UTC): ${Instant.ofEpochMilli(message.timestamp).truncatedTo(ChronoUnit.SECONDS)}",
            "Type: ${message.kind}",
        )
        if (message.kind == app.chencang.shared.chat.ChatMessage.KIND_TEXT) {
            lines += "Content:\n${message.body}"
        } else if (blobIds.isNotEmpty()) {
            lines += "Media IDs: ${blobIds.joinToString(",")}"
        }
        lines += "App: CamoChat $appVersion Android"
        return Mail(to = MODERATION_EMAIL, subject = "CamoChat report / 举报", body = lines.joinToString("\n", postfix = "\n"))
    }
}
