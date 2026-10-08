package app.chencang.android.ui.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.ui.components.EmojiSealGrid
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import app.chencang.shared.profile.contactAvatar

object ContactTestTags {
    const val AVATAR = "contact-avatar"
    const val RENAME = "contact-rename"
    const val GRID = "contact-grid"
    const val CONFIRM = "contact-confirm"
    const val CLEAR = "contact-clear"
    const val DELETE = "contact-delete"
    const val DELETE_CONFIRM = "contact-delete-confirm"
    const val MESSAGE = "contact-message"
    const val RESEND_RESPONSE = "contact-resend-response"
}

/**
 * Contact page (spec section 5.6): avatar + name (editable) + the safety-code card + "Clear
 * messages" and "Delete contact". An unverified contact gets a primary "I checked, they match"
 * button; a verified one shows only the status. Route `contact/{fingerprintHex}`; [ContactTestTags]
 * took over the semantics of the old `VerifyTestTags`.
 *
 * [ContactViewModel.deleted] is collected in CcNavGraph (not here), the same "navigation events are
 * collected at the nav host" split as `PairingWizardViewModel.openThread`. This composable only
 * renders and forwards user actions back to the view model.
 */
@Composable
fun ContactScreen(
    viewModel: ContactViewModel,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onResendResponse: (wire: String) -> Unit,
) {
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val resendableResponse by viewModel.resendableResponse.collectAsStateWithLifecycle()
    ContactContent(
        contact = contact,
        resendableResponse = resendableResponse,
        onBack = onBack,
        onMessage = onMessage,
        onResendResponse = onResendResponse,
        onConfirm = viewModel::confirm,
        onRename = viewModel::rename,
        onClearMessages = viewModel::clearMessages,
        onDeleteContact = viewModel::deleteContact,
    )
}

/** The contact page's pure rendering layer (no ViewModel): state in, actions out, easy to test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactContent(
    contact: Contact?,
    resendableResponse: String?,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onResendResponse: (wire: String) -> Unit,
    onConfirm: () -> Unit,
    onRename: (String) -> Unit,
    onClearMessages: () -> Unit,
    onDeleteContact: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = moyuColors.surfaceBase,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_detail_title), color = moyuColors.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = moyuColors.surfaceBase),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = moyuColors.accentPrimary)
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .verticalScroll(rememberScrollState())
                .padding(Moyu.Space.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val displayName = contact?.displayName ?: "…"
            val fingerprint = contact?.fingerprintHex ?: ""

            Box(modifier = Modifier.testTag(ContactTestTags.AVATAR)) {
                MoyuAvatar(
                    spec = remember(displayName, fingerprint) { contactAvatar(displayName, fingerprint) },
                    size = Moyu.Size.AvatarProfile,
                )
            }
            Spacer(Modifier.height(Moyu.Space.M))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Moyu.Space.Xs),
            ) {
                Text(displayName, fontSize = Moyu.FontSize.Title, color = moyuColors.textPrimary)
                IconButton(
                    onClick = { renaming = true },
                    modifier = Modifier.testTag(ContactTestTags.RENAME),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.contacts_rename_label), tint = moyuColors.textSecondary)
                }
            }

            Spacer(Modifier.height(Moyu.Space.L))
            Button(onClick = onMessage, modifier = Modifier.fillMaxWidth().testTag(ContactTestTags.MESSAGE)) {
                Text(stringResource(R.string.contacts_message))
            }
            resendableResponse?.let { wire ->
                TextButton(
                    onClick = { onResendResponse(wire) },
                    modifier = Modifier.testTag(ContactTestTags.RESEND_RESPONSE),
                ) { Text(stringResource(R.string.pairing_resend)) }
                Text(
                    stringResource(R.string.contacts_resend_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = moyuColors.textSecondary,
                )
            }

            Spacer(Modifier.height(Moyu.Space.Xl))
            TrustSealCard(contact = contact, onConfirm = onConfirm)

            Spacer(Modifier.height(Moyu.Space.Xl))
            ActionsGroup(
                onClear = { confirmClear = true },
                onDelete = { confirmDelete = true },
            )
        }
    }

    if (renaming) {
        RenameDialog(
            current = contact?.displayName.orEmpty(),
            onDismiss = { renaming = false },
            onSave = { name -> onRename(name); renaming = false },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.contacts_clear_title)) },
            text = { Text(stringResource(R.string.contacts_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearMessages() }) {
                    Text(stringResource(R.string.contacts_clear), color = moyuColors.statusDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.contacts_delete_title)) },
            text = { Text(stringResource(R.string.contacts_delete_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(ContactTestTags.DELETE_CONFIRM),
                    onClick = { confirmDelete = false; onDeleteContact() },
                ) { Text(stringResource(R.string.common_delete), color = moyuColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Safety-code card: [EmojiSealGrid] + status row; the confirm button only while unverified. */
@Composable
private fun TrustSealCard(contact: Contact?, onConfirm: () -> Unit) {
    val verified = contact?.verified == true
    val statusText = stringResource(if (verified) R.string.verify_status_verified else R.string.verify_status_unverified)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Moyu.Radius.Card))
            .background(moyuColors.surfaceRaised)
            .padding(Moyu.Space.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.testTag(ContactTestTags.GRID)) {
            EmojiSealGrid(contact?.safetyEmoji.orEmpty())
        }
        Spacer(Modifier.height(Moyu.Space.L))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Moyu.Space.Xs),
        ) {
            Icon(
                if (verified) Icons.Default.Verified else Icons.Outlined.Shield,
                contentDescription = statusText,
                tint = if (verified) moyuColors.accentPrimary else moyuColors.statusWarn,
                modifier = Modifier.size(Moyu.Space.L),
            )
            Text(
                statusText,
                color = if (verified) moyuColors.accentPrimary else moyuColors.statusWarn,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (!verified) {
            Spacer(Modifier.height(Moyu.Space.L))
            Button(onClick = onConfirm, modifier = Modifier.testTag(ContactTestTags.CONFIRM)) {
                Text(stringResource(R.string.verify_confirm_done), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Action group: clear messages / delete contact (danger). */
@Composable
private fun ActionsGroup(onClear: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Moyu.Radius.Card))
            .background(moyuColors.surfaceRaised),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClear)
                .testTag(ContactTestTags.CLEAR),
            headlineContent = { Text(stringResource(R.string.contacts_clear_row)) },
            supportingContent = { Text(stringResource(R.string.contacts_clear_row_hint)) },
        )
        HorizontalDivider(color = moyuColors.borderHairline)
        ListItem(
            colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDelete)
                .testTag(ContactTestTags.DELETE),
            headlineContent = { Text(stringResource(R.string.contacts_delete_row), color = moyuColors.statusDanger) },
            supportingContent = { Text(stringResource(R.string.contacts_delete_row_hint)) },
        )
    }
}

/** Edit the contact's local name (receiver-side label, never leaves the device). */
@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_rename_label)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.contacts_name_field)) },
                shape = RoundedCornerShape(Moyu.Radius.Input),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
