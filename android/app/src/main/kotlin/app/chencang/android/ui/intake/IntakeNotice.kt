package app.chencang.android.ui.intake

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure

/**
 * In-app notice for a failed intake ("Paste & decrypt" from the + menu). Inside the app only
 * [IntakeFailure.NO_CONTACTS] gets an action ("Add contact"); every other case — including
 * CANNOT_DECRYPT, whose "Open app" action only makes sense from outside — has just "OK".
 */
@Composable
fun IntakeNoticeDialog(
    failure: IntakeFailure,
    onAddContact: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(failure.messageRes)) },
        confirmButton = {
            if (failure == IntakeFailure.NO_CONTACTS) {
                TextButton(onClick = onAddContact) { Text(stringResource(R.string.pairing_add_contact)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            }
        },
        dismissButton = if (failure == IntakeFailure.NO_CONTACTS) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } }
        } else {
            null
        },
    )
}
