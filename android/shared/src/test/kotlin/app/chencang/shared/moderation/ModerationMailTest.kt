package app.chencang.shared.moderation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModerationMailTest {

    @Test
    fun `blockNotice has the fixed address, subject and body lines`() {
        val mail = ModerationMail.blockNotice(
            displayName = "老周",
            fingerprintHex = "0123456789abcdef0123456789abcdef",
            nowMillis = 1_793_700_000_123L,
            appVersion = "1.0.3",
        )

        assertThat(ModerationMail.MODERATION_EMAIL).isEqualTo("hi@allnationconnect.com")
        assertThat(mail.to).isEqualTo("hi@allnationconnect.com")
        assertThat(mail.subject).isEqualTo("CamoChat block notice / 拉黑通知")
        assertThat(mail.body.lines()).containsAtLeast(
            "Blocked contact: 老周",
            "Fingerprint: 0123456789abcdef",
            "Time (UTC): 2026-11-03T10:00:00Z",
            "App: CamoChat 1.0.3 Android",
        ).inOrder()
    }

    @Test
    fun `blockNotice keeps a short fingerprint whole`() {
        val mail = ModerationMail.blockNotice("A", "abcd", 0L, "1.0")
        assertThat(mail.body).contains("Fingerprint: abcd\n")
    }

    private fun msg(kind: String, body: String = "") =
        app.chencang.shared.chat.ChatMessage("m1", "peer", app.chencang.shared.chat.ChatMessage.DIRECTION_IN, body, 1_793_700_000_999L, kind = kind)

    @Test
    fun `report of a text message carries the content and no media ids`() {
        val mail = ModerationMail.report(
            "老周", "0123456789abcdef0123456789abcdef",
            msg(app.chencang.shared.chat.ChatMessage.KIND_TEXT, "bad\nstuff"), emptyList(), "1.0.3",
        )
        assertThat(mail.to).isEqualTo("hi@allnationconnect.com")
        assertThat(mail.subject).isEqualTo("CamoChat report / 举报")
        assertThat(mail.body).isEqualTo(
            "Reported contact: 老周\nFingerprint: 0123456789abcdef\nMessage time (UTC): 2026-11-03T10:00:00Z\n" +
                "Type: text\nContent:\nbad\nstuff\nApp: CamoChat 1.0.3 Android\n",
        )
        assertThat(mail.body).doesNotContain("Media IDs")
    }

    @Test
    fun `report of an image message lists blob ids and no content`() {
        val mail = ModerationMail.report(
            "A", "abcd", msg(app.chencang.shared.chat.ChatMessage.KIND_IMAGE), listOf("a", "b"), "1.0.3",
        )
        assertThat(mail.body).contains("Type: image\n")
        assertThat(mail.body).contains("Media IDs: a,b\n")
        assertThat(mail.body).doesNotContain("Content:")
        assertThat(mail.body.trimEnd().lines().last()).isEqualTo("App: CamoChat 1.0.3 Android")
    }
}
