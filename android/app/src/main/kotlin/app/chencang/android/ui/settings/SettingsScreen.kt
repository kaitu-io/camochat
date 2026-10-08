package app.chencang.android.ui.settings

import app.chencang.android.ChannelFeatures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.chencang.android.ui.share.ShareAppDialog
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import kotlinx.coroutines.launch

object SettingsTestTags {
    const val SUMMARY_PRIVACY = "settings_summary_privacy"
    const val RECOVERY_EXPORT = "settings-recovery-export"
    const val RECOVERY_RESTORE = "settings-recovery-restore"
    const val DELETE_ACCOUNT = "settings-delete-account"
    const val DELETE_CONFIRM_BUTTON = "settings-delete-confirm"
    const val UAT_LOOPBACK = "settings-uat-loopback"
    const val UAT_SEED = "settings-uat-seed"
    const val VERSION = "settings-version"
    const val SERVER_SOURCE = "settings-server-source"
    const val ABOUT = "settings-about"
    const val SHARE_APP = "settings-share-app"
}

/**
 * 设置各分组（隐私 → 身份(仅 Debug) → 关于 → 危险区 → 开发者(仅 Debug)），嵌在「我」tab 里；
 * 自己没有 Scaffold，也没有返回键。
 */
@Composable
fun SettingsSections(
    summaryPrivacy: Boolean,
    onSummaryPrivacyChange: (Boolean) -> Unit,
    onRecoveryExport: (() -> Unit)? = null,
    onRecoveryRestore: (() -> Unit)? = null,
    onDeleteAccount: () -> Unit,
    versionName: String,
    onCheckForUpdate: (() -> Unit)? = null,
    onOpenSource: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onShareApp: (asZip: Boolean) -> Unit = {},
    canShareApk: Boolean = ChannelFeatures.canShareApk,
    onRunUatLoopback: (suspend () -> String)? = null,
    onSeedUat: (suspend () -> String)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var shareAppDialog by remember { mutableStateOf(false) }
    var uatStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(Moyu.Space.L)) {
        SettingsGroup(stringResource(R.string.settings_privacy)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.SUMMARY_PRIVACY),
                headlineContent = { Text(stringResource(R.string.settings_hide_summary)) },
                supportingContent = { Text(stringResource(R.string.settings_hide_summary_hint)) },
                trailingContent = {
                    Switch(checked = summaryPrivacy, onCheckedChange = onSummaryPrivacyChange)
                },
            )
        }

        if (onRecoveryExport != null && onRecoveryRestore != null) {
            SettingsGroup("Identity (Debug)") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRecoveryExport)
                        .testTag(SettingsTestTags.RECOVERY_EXPORT),
                    headlineContent = { Text("Export recovery phrase") },
                    supportingContent = { Text("Restore your identity on a new device") },
                )
                HorizontalDivider(color = moyuColors.borderHairline)
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRecoveryRestore)
                        .testTag(SettingsTestTags.RECOVERY_RESTORE),
                    headlineContent = { Text("Restore identity") },
                    supportingContent = { Text("From a recovery phrase") },
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_about)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onCheckForUpdate != null) Modifier.clickable(onClick = onCheckForUpdate) else Modifier)
                    .testTag(SettingsTestTags.VERSION),
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                trailingContent = { Text(versionName, color = moyuColors.textSecondary) },
            )
            HorizontalDivider(color = moyuColors.borderHairline)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .testTag(SettingsTestTags.ABOUT),
                headlineContent = { Text(stringResource(R.string.about_row)) },
            )
            if (canShareApk) {
                HorizontalDivider(color = moyuColors.borderHairline)
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { shareAppDialog = true })
                        .testTag(SettingsTestTags.SHARE_APP),
                    headlineContent = { Text(stringResource(R.string.share_app_row)) },
                )
            }
            HorizontalDivider(color = moyuColors.borderHairline)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSource)
                    .testTag(SettingsTestTags.SERVER_SOURCE),
                headlineContent = { Text(stringResource(R.string.settings_media_relay)) },
                supportingContent = { Text(stringResource(R.string.settings_media_relay_hint)) },
            )
        }

        if (shareAppDialog) {
            ShareAppDialog(onShareApk = onShareApp, onDismiss = { shareAppDialog = false })
        }

        SettingsGroup(stringResource(R.string.settings_danger)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { confirmDelete = true })
                    .testTag(SettingsTestTags.DELETE_ACCOUNT),
                headlineContent = {
                    Text(stringResource(R.string.me_erase_all), color = moyuColors.statusDanger)
                },
                supportingContent = { Text(stringResource(R.string.settings_erase_hint)) },
            )
        }

        if (onRunUatLoopback != null || onSeedUat != null) {
            SettingsGroup("Developer (Debug)") {
                var firstRow = true
                if (onRunUatLoopback != null) {
                    firstRow = false
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                uatStatus = "Running…"
                                scope.launch {
                                    uatStatus = runCatching { onRunUatLoopback() }
                                        .getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
                                }
                            }
                            .testTag(SettingsTestTags.UAT_LOOPBACK),
                        headlineContent = { Text("UAT loopback (debug)") },
                        supportingContent = {
                            Text(uatStatus ?: "In-band loopback: pairing → encrypt → decrypt")
                        },
                    )
                }

                if (onSeedUat != null) {
                    if (!firstRow) HorizontalDivider(color = moyuColors.borderHairline)
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = moyuColors.surfaceRaised),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                uatStatus = "Running…"
                                scope.launch {
                                    uatStatus = runCatching { onSeedUat() }
                                        .getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
                                }
                            }
                            .testTag(SettingsTestTags.UAT_SEED),
                        headlineContent = { Text("Seed UAT contact (debug)") },
                        supportingContent = {
                            Text("Add UAT self-test contact + DR sessions for local loopback testing")
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.settings_erase_confirm_title)) },
            text = { Text(stringResource(R.string.settings_erase_confirm_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(SettingsTestTags.DELETE_CONFIRM_BUTTON),
                    onClick = {
                        confirmDelete = false
                        onDeleteAccount()
                    },
                ) { Text(stringResource(R.string.settings_erase_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = moyuColors.textTertiary,
            modifier = Modifier.padding(start = Moyu.Space.Xs, bottom = Moyu.Space.Xs),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Moyu.Radius.Card))
                .background(moyuColors.surfaceRaised),
            content = content,
        )
    }
}
