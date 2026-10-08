package app.chencang.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

/** 2×4 网格切分;非 8 个(异常/旧数据)退化为单行,由调用方配合空态文案。 */
fun sealGridRows(emojis: List<String>): List<List<String>> = when {
    emojis.isEmpty() -> emptyList()
    emojis.size == 8 -> listOf(emojis.subList(0, 4), emojis.subList(4, 8))
    else -> listOf(emojis)
}

/**
 * 安全码表情网格:8 个表情按 [sealGridRows] 渲染 2×4;非 8 个(legacy/异常数据)
 * 退化单行,空表时不渲染格子——展示与联系人页(ContactScreen)一致的重新配对
 * 提示文案。
 */
@Composable
fun EmojiSealGrid(emojis: List<String>, cellSize: Dp = 40.dp) {
    val rows = sealGridRows(emojis)
    if (rows.isEmpty()) {
        Text(
            stringResource(R.string.verify_no_code),
            color = moyuColors.statusWarn,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(Moyu.Space.M),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S)) {
                row.forEachIndexed { colIdx, emoji ->
                    val cellIdx = rowIdx * 4 + colIdx
                    Box(
                        modifier = Modifier
                            .size(cellSize)
                            .clip(RoundedCornerShape(Moyu.Radius.Card))
                            .background(moyuColors.surfaceRaised)
                            .border(Moyu.Radius.BubbleTail / 4, moyuColors.borderHairline, RoundedCornerShape(Moyu.Radius.Card))
                            .testTag("seal-emoji-$cellIdx"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = Moyu.FontSize.Display)
                    }
                }
            }
        }
    }
}
