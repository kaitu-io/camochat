package app.chencang.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import app.chencang.shared.profile.contactAvatar

/** 长按「转发」→ 选联系人(排除当前线程);选中后对该联系人重新加密、上传、分享(spec §3.7)。 */
@Composable
internal fun ForwardPicker(contacts: List<Contact>, onPick: (Contact) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_forward_to)) },
        text = {
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.media_forward_no_contacts), color = moyuColors.textSecondary)
            } else {
                LazyColumn {
                    items(contacts, key = { it.username }) { c ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                            leadingContent = {
                                MoyuAvatar(
                                    spec = remember(c.displayName, c.fingerprintHex) { contactAvatar(c.displayName, c.fingerprintHex) },
                                    size = Moyu.Size.AvatarInline,
                                )
                            },
                            headlineContent = { Text(c.displayName) },
                            modifier = Modifier.clickable { onPick(c) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
