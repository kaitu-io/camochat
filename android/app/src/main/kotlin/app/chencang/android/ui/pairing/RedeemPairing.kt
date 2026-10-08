package app.chencang.android.ui.pairing

import android.content.ClipboardManager
import android.content.Context

/**
 * Matches a 12-char base32 invite code, with OR without the grouping dashes
 * (`MCRE-NDQ4-7BPR` or `MCRENDQ47BPR`), embedded anywhere in a larger
 * invite-copy string. The zero-server pairing flow itself uses 🔒 wires /
 * pairing links, not codes.
 */
val CODE_RE = Regex("\\b[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}\\b")

/**
 * The clipboard's whole primary text, or null when it is empty. Read only when the user taps
 * "Paste" — never on screen entry. Locating the wire inside it is the classifier's job.
 */
fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()
        ?.takeIf { it.isNotEmpty() }
}
