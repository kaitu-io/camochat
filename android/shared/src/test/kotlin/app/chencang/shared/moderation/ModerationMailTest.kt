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
}
