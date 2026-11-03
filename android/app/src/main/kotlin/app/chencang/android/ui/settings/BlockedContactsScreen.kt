package app.chencang.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.model.Contact
import app.chencang.shared.profile.contactAvatar

object BlockedContactsTestTags {
    const val EMPTY = "blocked-empty"
    fun row(fingerprintHex: String) = "blocked-row-$fingerprintHex"
    fun unblock(fingerprintHex: String) = "blocked-unblock-$fingerprintHex"
}

/**
 * Settings → Blocked contacts. Each row opens the contact page (which can also unblock) and has
 * its own "Unblock" button. Unblocking brings the contact and conversation back as they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContactsScreen(
    contacts: List<Contact>,
    onOpenContact: (fingerprintHex: String) -> Unit,
    onUnblock: (fingerprintHex: String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = moyuColors.surfaceBase,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_blocked_contacts), color = moyuColors.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = moyuColors.surfaceBase),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = moyuColors.accentPrimary,
                        )
                    }
                },
            )
        },
    ) { inner ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.blocked_empty),
                    color = moyuColors.textSecondary,
                    modifier = Modifier.testTag(BlockedContactsTestTags.EMPTY),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(inner),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + Moyu.Space.L,
                bottom = inner.calculateBottomPadding() + Moyu.Space.L,
            ),
        ) {
            items(contacts, key = { it.fingerprintHex }) { contact ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(moyuColors.surfaceRaised)
                        .clickable { onOpenContact(contact.fingerprintHex) }
                        .testTag(BlockedContactsTestTags.row(contact.fingerprintHex)),
                    leadingContent = {
                        MoyuAvatar(
                            spec = remember(contact.displayName, contact.fingerprintHex) {
                                contactAvatar(contact.displayName, contact.fingerprintHex)
                            },
                            size = Moyu.Size.AvatarList,
                        )
                    },
                    headlineContent = { Text(contact.displayName, color = moyuColors.textPrimary) },
                    trailingContent = {
                        TextButton(
                            onClick = { onUnblock(contact.fingerprintHex) },
                            modifier = Modifier.testTag(BlockedContactsTestTags.unblock(contact.fingerprintHex)),
                        ) { Text(stringResource(R.string.contacts_unblock_row)) }
                    },
                )
                HorizontalDivider(color = moyuColors.borderHairline)
            }
        }
    }
}
