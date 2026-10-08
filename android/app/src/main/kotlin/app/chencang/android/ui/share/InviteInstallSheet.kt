package app.chencang.android.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.chencang.android.ChannelFeatures
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

object InviteInstallTestTags {
    const val SEND_LINK = "invite-send-link"
    const val SEND_APK = "invite-send-apk"
    const val SEND_ZIP = "invite-send-zip"
}

/** 配对时对方还没装：发下载链接 / 直接发安装包 / 以压缩包发送（后两项仅直装渠道）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteInstallSheet(
    site: String,
    onShareLink: (String) -> Unit,
    onShareApk: (asZip: Boolean) -> Unit,
    onDismiss: () -> Unit,
    canShareApk: Boolean = ChannelFeatures.canShareApk,
) {
    val installText = stringResource(R.string.invite_install_text, site)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = moyuColors.surfaceRaised) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.M),
        ) {
            TextButton(
                onClick = { onShareLink(installText); onDismiss() },
                modifier = Modifier.fillMaxWidth().testTag(InviteInstallTestTags.SEND_LINK),
            ) { Text(stringResource(R.string.invite_send_link)) }
            if (canShareApk) {
                Text(
                    stringResource(R.string.share_app_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = moyuColors.textSecondary,
                    modifier = Modifier.padding(vertical = Moyu.Space.S),
                )
                TextButton(
                    onClick = { onShareApk(false); onDismiss() },
                    modifier = Modifier.fillMaxWidth().testTag(InviteInstallTestTags.SEND_APK),
                ) { Text(stringResource(R.string.invite_send_apk)) }
                TextButton(
                    onClick = { onShareApk(true); onDismiss() },
                    modifier = Modifier.fillMaxWidth().testTag(InviteInstallTestTags.SEND_ZIP),
                ) { Text(stringResource(R.string.invite_send_zip)) }
            }
        }
    }
}

/** 设置 / 关于页用：只选「安装包 / 压缩包」。 */
@Composable
fun ShareAppDialog(onShareApk: (asZip: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_app_row)) },
        text = { Text(stringResource(R.string.share_app_hint)) },
        confirmButton = {
            Column {
                TextButton(onClick = { onShareApk(false); onDismiss() }) { Text(stringResource(R.string.invite_send_apk)) }
                TextButton(onClick = { onShareApk(true); onDismiss() }) { Text(stringResource(R.string.invite_send_zip)) }
            }
        },
    )
}
