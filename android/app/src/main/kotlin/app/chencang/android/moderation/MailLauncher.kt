package app.chencang.android.moderation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.chencang.android.clipboard.AppClipboard
import app.chencang.shared.R
import app.chencang.shared.moderation.ModerationMail

/**
 * Hands a [ModerationMail.Mail] draft to the user's mail app; the user decides whether to send it.
 * No mail app: the whole draft goes to the clipboard and a toast says where to send it.
 */
object MailLauncher {
    fun send(context: Context, mail: ModerationMail.Mail) {
        try {
            context.startActivity(intent(mail))
        } catch (_: ActivityNotFoundException) {
            AppClipboard.write(context, "CamoChat", fallbackText(mail))
            Toast.makeText(context, R.string.mail_copied_fallback, Toast.LENGTH_LONG).show()
        }
    }

    internal fun intent(mail: ModerationMail.Mail): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${mail.to}")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.to))
            putExtra(Intent.EXTRA_SUBJECT, mail.subject)
            putExtra(Intent.EXTRA_TEXT, mail.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    internal fun fallbackText(mail: ModerationMail.Mail): String =
        "To: ${mail.to}\nSubject: ${mail.subject}\n\n" + mail.body
}
