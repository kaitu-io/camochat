package app.chencang.android.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

/** 「你刚复制了内容 [粘贴] ✕」。点「粘贴」才会读剪贴板（由调用方做）。 */
@Composable
fun PasteBar(
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(moyuColors.accentContainer)
            .heightIn(min = Moyu.Size.TouchMin)
            .padding(start = Moyu.Space.L)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(MainTestTags.PASTE_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.paste_bar_text),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPaste, modifier = Modifier.testTag(MainTestTags.PASTE_BAR_PASTE)) {
            Text(stringResource(R.string.pairing_paste), color = moyuColors.accentPrimary)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(MainTestTags.PASTE_BAR_DISMISS)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.paste_bar_dismiss_cd), tint = moyuColors.textSecondary)
        }
    }
}

/** 状态持有者驱动的粘贴条：可见时才画；点粘贴/✕ 先记已消费。 */
@Composable
fun PasteBarHost(state: PasteBarState, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    if (state.visible) {
        PasteBar(
            onPaste = { state.consume(); onPaste() },
            onDismiss = state::consume,
            modifier = modifier,
        )
    }
}
