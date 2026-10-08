package app.chencang.android.ui.contact

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateUtils
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.android.ui.pairing.DeleteInviteDialog
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PendingItem
import app.chencang.shared.pairing.inband.PendingKind
import app.chencang.shared.profile.contactAvatar

object ContactListTestTags {
    const val LIST = "contactlist"
    const val ADD = "contactlist-add"
    const val ROW_PREFIX = "contactlist-row-" // + fingerprintHex
    const val EMPTY = "contactlist-empty"
    const val PENDING = "contactlist-pending"
    const val PENDING_ROW_PREFIX = "contactlist-pending-row-" // + pairingId
    const val PENDING_RESEND_PREFIX = "contactlist-pending-resend-" // + pairingId
    const val PENDING_RESPONSE_PREFIX = "contactlist-pending-response-" // + fingerprintHex
    const val PENDING_SENDBACK_PREFIX = "contactlist-pending-sendback-" // + fingerprintHex
}

/**
 * The Contacts tab: a fixed "Add contact" row, then the "Pairing" section (hidden when empty), then
 * the sorted contacts (spec three-tab-shell section 7, rule U).
 *
 * @param onAddContact the "Add contact" row: start the wizard as the initiator.
 * @param onOpenPending tap on a whole row: open the wizard to resume.
 * @param onResendPending the row's trailing "Send code back / Send again": share the same pairing code and mark it shared.
 * @param onDeleteInvite after long-press "Delete" and confirmation (invite rows only).
 */
@Composable
fun ContactListContent(
    viewModel: ContactListViewModel,
    contentPadding: PaddingValues,
    onOpenContact: (fingerprintHex: String) -> Unit,
    onAddContact: () -> Unit,
    onOpenPending: (PendingItem) -> Unit,
    onResendPending: (PendingItem) -> Unit,
    onDeleteInvite: suspend (pairingId: String) -> Boolean,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val contacts by viewModel.rows.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val byFingerprint by viewModel.contactsByFingerprint.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().testTag(ContactListTestTags.LIST),
    ) {
        item(key = "add") { AddRow(onAddContact) }
        if (pending.isNotEmpty()) {
            item(key = "pending-header") {
                Text(
                    stringResource(R.string.contacts_pending),
                    style = MaterialTheme.typography.labelLarge,
                    color = moyuColors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.S)
                        .testTag(ContactListTestTags.PENDING),
                )
            }
            items(pending, key = { "pending-" + it.id }) { item ->
                PendingRow(
                    item = item,
                    contact = byFingerprint[item.id],
                    onOpen = onOpenPending,
                    onResend = onResendPending,
                    onDelete = { deleting = it },
                )
                HorizontalDivider(color = moyuColors.borderHairline, thickness = Moyu.Radius.BubbleTail / 4)
            }
        }
        if (contacts.isEmpty() && pending.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.contacts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = moyuColors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Moyu.Space.Xl)
                        .testTag(ContactListTestTags.EMPTY),
                )
            }
        }
        items(contacts, key = { it.fingerprintHex }) { contact ->
            ContactRow(contact, onOpenContact)
            HorizontalDivider(color = moyuColors.borderHairline, thickness = Moyu.Radius.BubbleTail / 4)
        }
    }

    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    val deleteFailed = stringResource(R.string.common_delete_failed)
    deleting?.let { id ->
        DeleteInviteDialog(
            onConfirm = {
                deleting = null
                scope.launch {
                    if (!onDeleteInvite(id)) snackbarHostState.showSnackbar(deleteFailed)
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * One "Pairing" row. The left block (avatar + two lines) is a single accessible element reading
 * "title, status · time"; tap = resume, long press = delete menu (invites only; screen readers use
 * a custom action). The trailing text button is a separate control.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingRow(
    item: PendingItem,
    contact: Contact?,
    onOpen: (PendingItem) -> Unit,
    onResend: (PendingItem) -> Unit,
    onDelete: (pairingId: String) -> Unit,
) {
    val isResponse = item.kind == PendingKind.ResponseUnsent
    val title = if (isResponse) contact?.displayName.orEmpty() else item.note?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.contacts_invite_no_note)
    val relative = remember(item.createdAtMillis) {
        DateUtils.getRelativeTimeSpanString(item.createdAtMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
    }
    val status = when (item.kind) {
        PendingKind.ResponseUnsent -> stringResource(R.string.contacts_pending_response_unsent)
        PendingKind.InviteAwaiting -> stringResource(R.string.contacts_pending_awaiting)
    }
    val subtitle = if (item.isStale) {
        stringResource(R.string.contacts_pending_stale, relative)
    } else {
        stringResource(R.string.contacts_pending_subtitle, status, relative)
    }
    val subtitleColor = when {
        item.isStale -> moyuColors.textTertiary
        item.kind == PendingKind.InviteAwaiting -> moyuColors.textSecondary
        else -> moyuColors.statusWarn
    }
    var menu by remember { mutableStateOf(false) }
    val rowTag = if (isResponse) {
        ContactListTestTags.PENDING_RESPONSE_PREFIX + item.id
    } else {
        ContactListTestTags.PENDING_ROW_PREFIX + item.id
    }
    val deleteLabel = stringResource(R.string.common_delete)
    val rowDescription = stringResource(R.string.contacts_pending_row_cd, title, subtitle)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Moyu.Size.AvatarList + Moyu.Space.M * 2)
            .padding(start = Moyu.Space.L, end = Moyu.Space.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpen(item) },
                        onLongClick = if (isResponse) null else ({ menu = true }),
                    )
                    .padding(vertical = Moyu.Space.M)
                    // clearAndSetSemantics clears testTags later in the chain, so the tag and custom actions are set here.
                    .clearAndSetSemantics {
                        contentDescription = rowDescription
                        testTag = rowTag
                        if (!isResponse) {
                            customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(item.id); true })
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Moyu.Space.M),
            ) {
                if (isResponse && contact != null) {
                    MoyuAvatar(
                        spec = remember(contact.displayName, contact.fingerprintHex) {
                            contactAvatar(contact.displayName, contact.fingerprintHex)
                        },
                        size = Moyu.Size.AvatarList,
                    )
                } else {
                    // Hollow placeholder: outlined circle + line-art shield icon.
                    Box(
                        modifier = Modifier
                            .size(Moyu.Size.AvatarList)
                            .border(Moyu.Radius.BubbleTail / 4, moyuColors.borderHairline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Shield, contentDescription = null, tint = moyuColors.textTertiary)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (!isResponse && item.note.isNullOrEmpty()) moyuColors.textSecondary else moyuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(deleteLabel, color = moyuColors.statusDanger) },
                    onClick = { menu = false; onDelete(item.id) },
                )
            }
        }
        if (item.canResend) {
            TextButton(
                onClick = { onResend(item) },
                modifier = Modifier.testTag(
                    if (isResponse) {
                        ContactListTestTags.PENDING_SENDBACK_PREFIX + item.id
                    } else {
                        ContactListTestTags.PENDING_RESEND_PREFIX + item.id
                    },
                ),
            ) { Text(stringResource(if (isResponse) R.string.contacts_send_back else R.string.contacts_resend)) }
        }
    }
}

@Composable
private fun AddRow(onAddContact: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAddContact)
            .padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.M)
            .testTag(ContactListTestTags.ADD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.M),
    ) {
        Box(
            modifier = Modifier
                .size(Moyu.Size.AvatarList)
                .clip(RoundedCornerShape(Moyu.Radius.Input))
                .background(moyuColors.accentPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = moyuColors.accentOnPrimary)
        }
        Text(stringResource(R.string.pairing_add_contact), style = MaterialTheme.typography.titleSmall, color = moyuColors.textPrimary)
    }
}

@Composable
private fun ContactRow(contact: Contact, onOpenContact: (String) -> Unit) {
    val state = stringResource(if (contact.verified) R.string.verify_status_verified else R.string.verify_status_unverified)
    val rowDescription = stringResource(R.string.contacts_row_cd, contact.displayName, state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Moyu.Size.AvatarList + Moyu.Space.M * 2)
            .clickable { onOpenContact(contact.fingerprintHex) }
            .padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.M)
            // One click target for the whole row; screen readers read a single "name, state" sentence.
            .clearAndSetSemantics {
                contentDescription = rowDescription
                // clearAndSetSemantics clears testTags later in the chain, so the tag is set here too.
                testTag = ContactListTestTags.ROW_PREFIX + contact.fingerprintHex
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.M),
    ) {
        MoyuAvatar(
            spec = remember(contact.displayName, contact.fingerprintHex) {
                contactAvatar(contact.displayName, contact.fingerprintHex)
            },
            size = Moyu.Size.AvatarList,
        )
        Text(
            contact.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = moyuColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (contact.verified) Icons.Default.Verified else Icons.Outlined.Shield,
            contentDescription = null,
            tint = if (contact.verified) moyuColors.accentPrimary else moyuColors.statusWarn,
            modifier = Modifier.size(Moyu.Space.L),
        )
    }
}
