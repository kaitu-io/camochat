package app.chencang.android.moderation

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.moderation.ModerationMail
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailLauncherTest {
    private val mail = ModerationMail.Mail("hi@allnationconnect.com", "Subj", "line1\nline2\n")

    @Test
    fun `intent is a mailto draft with subject and body`() {
        val i = MailLauncher.intent(mail)
        assertThat(i.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(i.data.toString()).isEqualTo("mailto:hi@allnationconnect.com")
        assertThat(i.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("Subj")
        assertThat(i.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("line1\nline2\n")
        assertThat(i.getStringArrayExtra(Intent.EXTRA_EMAIL)).asList().containsExactly("hi@allnationconnect.com")
    }

    @Test
    fun `without a mail app the draft goes to the clipboard`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).checkActivities(true) // no activity resolves → ActivityNotFoundException

        MailLauncher.send(app, mail)

        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(cm.primaryClip!!.getItemAt(0).text.toString())
            .isEqualTo("To: hi@allnationconnect.com\nSubject: Subj\n\nline1\nline2\n")
        assertThat(ShadowToast.getTextOfLatestToast()).contains("hi@allnationconnect.com")
    }
}
