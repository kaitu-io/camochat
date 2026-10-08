package app.chencang.android.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chencang.android.navigation.MainTab
import app.chencang.android.ui.chat.ConversationListTestTags
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

object MainTestTags {
    const val TABBAR = "main-tabbar"
    const val TAB_CHATS = "main-tab-chats"
    const val TAB_CONTACTS = "main-tab-contacts"
    const val TAB_ME = "main-tab-me"
    const val CONTACTS_BADGE = "main-tab-contacts-badge"
    const val PASTE_BAR = "paste-bar"
    const val PASTE_BAR_PASTE = "paste-bar-paste"
    const val PASTE_BAR_DISMISS = "paste-bar-dismiss"
    const val CHATS_PENDING_ROW = "chats-pending-row"
}

/** Top-bar title: the chats tab shows the product name. */
@StringRes
private fun MainTab.titleRes() = when (this) {
    MainTab.Chats -> R.string.app_name
    MainTab.Contacts -> R.string.contacts_tab
    MainTab.Me -> R.string.me_tab
}

@StringRes
private fun MainTab.labelRes() = when (this) {
    MainTab.Chats -> R.string.conversations_tab
    MainTab.Contacts -> R.string.contacts_tab
    MainTab.Me -> R.string.me_tab
}

private fun MainTab.tag() = when (this) {
    MainTab.Chats -> MainTestTags.TAB_CHATS
    MainTab.Contacts -> MainTestTags.TAB_CONTACTS
    MainTab.Me -> MainTestTags.TAB_ME
}

private fun MainTab.icon(selected: Boolean): ImageVector = when (this) {
    MainTab.Chats -> if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
    MainTab.Contacts -> if (selected) Icons.Filled.People else Icons.Outlined.People
    MainTab.Me -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}

/**
 * 全 App 顶层唯一带底栏的 Scaffold（spec three-tab-shell §5）：三个 tab 的内容由调用方以槽位给入，
 * 当前 tab 由调用方持有（`main` 条目的 savedStateHandle）。只在会话 tab 的顶栏有「＋」，
 * 点它直接进添加联系人向导（收对方的邀请走粘贴条，或向导出示幕上的「粘贴」「扫一扫」）。
 * 不拦截返回键：任一 tab 根上返回即回桌面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    contactsBadge: Int,
    pasteBar: PasteBarState,
    onPasteBarPaste: () -> Unit,
    onAddContact: () -> Unit,
    chats: @Composable (PaddingValues) -> Unit,
    contacts: @Composable (PaddingValues) -> Unit,
    me: @Composable (PaddingValues) -> Unit,
) {
    val holder = rememberSaveableStateHolder()

    Scaffold(
        containerColor = moyuColors.surfaceBase,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(tab.titleRes()), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = moyuColors.surfaceBase),
                actions = {
                    if (tab == MainTab.Chats) {
                        IconButton(onClick = onAddContact, modifier = Modifier.testTag(ConversationListTestTags.ADD)) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.conversations_add_cd), tint = moyuColors.accentPrimary)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // 粘贴条只在会话、联系人 tab；它在底栏之上，Scaffold 的内边距自然把列表底部让出来。
                if (tab != MainTab.Me) PasteBarHost(pasteBar, onPasteBarPaste)
                // 有意复用：与列表分隔线同一根发丝线（Moyu.Radius.BubbleTail / 4 = 1dp）。
                HorizontalDivider(color = moyuColors.borderHairline, thickness = Moyu.Radius.BubbleTail / 4)
                NavigationBar(
                    modifier = Modifier.testTag(MainTestTags.TABBAR),
                    containerColor = moyuColors.surfaceRaised,
                    tonalElevation = 0.dp,
                ) {
                    MainTab.entries.forEach { t ->
                        TabItem(t, selected = t == tab, badge = if (t == MainTab.Contacts) contactsBadge else 0) {
                            onTabSelected(t)
                        }
                    }
                }
            }
        },
    ) { inner ->
        holder.SaveableStateProvider(tab.key) {
            when (tab) {
                MainTab.Chats -> chats(inner)
                MainTab.Contacts -> contacts(inner)
                MainTab.Me -> me(inner)
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    tab: MainTab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val c = moyuColors
    val badgeDescription = if (badge > 0) pluralStringResource(R.plurals.contacts_badge_cd, badge, badge) else null
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag(tab.tag()),
        icon = {
            BadgedBox(
                badge = {
                    if (badge > 0) {
                        Badge(containerColor = c.accentPrimary, contentColor = c.accentOnPrimary) {
                            Text("$badge", modifier = Modifier.testTag(MainTestTags.CONTACTS_BADGE))
                        }
                    }
                },
            ) {
                Icon(
                    tab.icon(selected),
                    contentDescription = null,
                    modifier = Modifier
                        .then(if (badgeDescription != null) Modifier.semantics { contentDescription = badgeDescription } else Modifier)
                        .size(Moyu.Size.TabIcon),
                )
            }
        },
        label = { Text(stringResource(tab.labelRes()), fontSize = Moyu.FontSize.Caption) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = c.accentPrimary,
            selectedTextColor = c.accentPrimary,
            indicatorColor = c.accentContainer,
            unselectedIconColor = c.textSecondary,
            unselectedTextColor = c.textSecondary,
        ),
    )
}
