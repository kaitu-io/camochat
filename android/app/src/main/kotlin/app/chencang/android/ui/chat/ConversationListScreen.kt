package app.chencang.android.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.ui.asString
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.android.ui.main.MainTestTags
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.chat.MessagePreview
import app.chencang.shared.profile.contactAvatar

object ConversationListTestTags {
    const val LIST = "convlist"
    const val EMPTY = "convlist-empty"
    const val EMPTY_CONTACTS = "convlist-empty-contacts"
    const val EMPTY_ADD_CONTACT = "convlist-empty-add-contact"
    const val EMPTY_RECEIVED_HINT = "convlist-empty-received-hint"
    const val EMPTY_GO_TO_CONTACTS = "convlist-empty-go-to-contacts"
    const val ADD = "convlist-add"
    const val ROW_PREFIX = "convlist-row-" // + username
}

/**
 * 会话 tab 的内容（顶栏与底栏由 [app.chencang.android.ui.main.MainScreen] 提供，这里不再有 Scaffold）。
 * 一行都没有时才画空状态，按「有没有联系人或配对中条目」分 A（「添加联系人」＋一行粘贴提示）/ B（「去联系人」；
 * 只剩配对中的邀请、或只有回暗号还没发出去的联系人时）。
 * 整行一个点击区进会话；未核对只在名字旁加标记，预览照常显示。
 */
@Composable
fun ConversationListContent(
    viewModel: ConversationListViewModel,
    contentPadding: PaddingValues,
    onOpenThread: (username: String) -> Unit,
    onAddContact: () -> Unit,
    onGoToContacts: () -> Unit,
) {
    val awaitingCount by viewModel.awaitingCount.collectAsStateWithLifecycle()
    // 等待行在最上面；有它时，顶部内边距给外层，列表本体只留其余三边。
    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (awaitingCount > 0) {
            Text(
                pluralStringResource(R.plurals.chats_pending_invites, awaitingCount, awaitingCount),
                style = MaterialTheme.typography.bodyMedium,
                color = moyuColors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGoToContacts)
                    .heightIn(min = Moyu.Size.TouchMin)
                    .padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.M)
                    .testTag(MainTestTags.CHATS_PENDING_ROW),
            )
            HorizontalDivider(color = moyuColors.borderHairline, thickness = Moyu.Radius.BubbleTail / 4)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ConversationListBody(
                viewModel,
                PaddingValues(
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                onOpenThread, onAddContact, onGoToContacts,
            )
        }
    }
}

@Composable
private fun ConversationListBody(
    viewModel: ConversationListViewModel,
    contentPadding: PaddingValues,
    onOpenThread: (username: String) -> Unit,
    onAddContact: () -> Unit,
    onGoToContacts: () -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val summaryPrivacy by viewModel.summaryPrivacy.collectAsStateWithLifecycle()
    val hasContactsOrPending by viewModel.hasContactsOrPending.collectAsStateWithLifecycle()

    if (rows.isEmpty()) {
        // 联系人 / 配对中是否存在还没读出来：先不画空状态。
        val hasAny = hasContactsOrPending ?: return
        val tag = if (hasAny) ConversationListTestTags.EMPTY_CONTACTS else ConversationListTestTags.EMPTY
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(Moyu.Space.Xxl).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = moyuColors.textTertiary,
                modifier = Modifier.size(Moyu.Space.Xxl + Moyu.Space.Xl),
            )
            Text(
                stringResource(R.string.conversations_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = moyuColors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Moyu.Space.L),
            )
            Text(
                stringResource(
                    if (hasAny) R.string.conversations_empty_has_contacts else R.string.conversations_empty_no_contacts,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = moyuColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Moyu.Space.Xs),
            )
            if (hasAny) {
                Button(
                    onClick = onGoToContacts,
                    modifier = Modifier.padding(top = Moyu.Space.Xl).testTag(ConversationListTestTags.EMPTY_GO_TO_CONTACTS),
                ) {
                    Text(stringResource(R.string.conversations_go_to_contacts), textAlign = TextAlign.Center)
                }
            } else {
                Button(
                    onClick = onAddContact,
                    modifier = Modifier.padding(top = Moyu.Space.Xl).testTag(ConversationListTestTags.EMPTY_ADD_CONTACT),
                ) {
                    Text(stringResource(R.string.pairing_add_contact), textAlign = TextAlign.Center)
                }
                Text(
                    stringResource(R.string.conversations_empty_received_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = moyuColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Moyu.Space.L).testTag(ConversationListTestTags.EMPTY_RECEIVED_HINT),
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().testTag(ConversationListTestTags.LIST),
        ) {
            items(rows, key = { it.contact.fingerprintHex }) { row ->
                ConversationRow(row, summaryPrivacy, onOpenThread)
                HorizontalDivider(color = moyuColors.borderHairline, thickness = Moyu.Radius.BubbleTail / 4)
            }
        }
    }
}

@Composable
private fun ConversationRow(
    row: ConversationListViewModel.Row,
    summaryPrivacy: Boolean,
    onOpenThread: (String) -> Unit,
) {
    val c = row.contact
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenThread(c.username) }
            .padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.M)
            .testTag(ConversationListTestTags.ROW_PREFIX + c.username),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.M),
    ) {
        MoyuAvatar(
            spec = remember(c.displayName, c.fingerprintHex) { contactAvatar(c.displayName, c.fingerprintHex) },
            size = Moyu.Size.AvatarList,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Moyu.Space.Xs)) {
                Text(
                    c.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = moyuColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.verified) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = stringResource(R.string.verify_status_verified),
                        tint = moyuColors.accentPrimary,
                        modifier = Modifier.size(Moyu.Space.L),
                    )
                } else {
                    // A marker, not a button: the whole row opens the chat.
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = moyuColors.statusWarn,
                        modifier = Modifier.size(Moyu.Space.L),
                    )
                    Text(
                        stringResource(R.string.verify_status_unverified),
                        style = MaterialTheme.typography.labelSmall,
                        color = moyuColors.statusWarn,
                        maxLines = 1,
                    )
                }
            }
            val placeholder = row.placeholderRes
            if (placeholder != null) {
                // 还没消息：只有一句说明，不受摘要隐藏影响，也没有「[未上传] 」前缀。
                Text(
                    stringResource(placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = moyuColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                MessagePreviewLine(row, summaryPrivacy)
            }
        }
        row.timeMillis?.let { time ->
            Text(
                DateUtils.getRelativeTimeSpanString(time).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = moyuColors.textTertiary,
            )
        }
    }
}

@Composable
private fun MessagePreviewLine(row: ConversationListViewModel.Row, summaryPrivacy: Boolean) {
    val last = row.last ?: return
    val preview = if (summaryPrivacy) {
        stringResource(R.string.conversations_preview_hidden)
    } else {
        MessagePreview.of(last, row.itemCount).asString()
    }
    val danger = moyuColors.statusDanger
    val unsentPrefix = row.unsentPrefixRes?.let { stringResource(it) }
    Text(
        // 最后一条「对方还看不到」：前缀「[未上传] 」用错误色（spec 2026-09-30 §1.3）。
        text = buildAnnotatedString {
            unsentPrefix?.let { withStyle(SpanStyle(color = danger)) { append(it) } }
            append(preview)
        },
        style = MaterialTheme.typography.bodySmall,
        color = moyuColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
