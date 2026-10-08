package app.chencang.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import app.chencang.design.Moyu
import app.chencang.design.MoyuLight
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.media.HoldToTalk

/** 按住说话时屏幕中部的浮层:实时振幅条 + 「松开 发送 / 上滑 取消」;进取消区变红;50 s 起倒计时。 */
@Composable
internal fun VoiceOverlay(phase: HoldToTalk.Phase.Recording, amplitude: Float, modifier: Modifier = Modifier) {
    // 有意复用 Size.ProgressStroke(3dp)作波形条宽度(spec §3.2 音量波形),不另加 token。
    val barWidth = Moyu.Size.ProgressStroke
    val gap = Moyu.Space.Xs
    val bars = ((Moyu.Size.VoiceOverlay - Moyu.Space.M * 2) / (barWidth + gap)).toInt()
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(amplitude) {
        history.add(amplitude)
        while (history.size > bars) history.removeAt(0)
    }
    // 有意复用 MoyuLight.accentOnPrimary：mediaScrim 两条轨都是黑色，前景需恒为浅色
    val barColor = MoyuLight.accentOnPrimary
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .size(Moyu.Size.VoiceOverlay)
                .clip(RoundedCornerShape(Moyu.Radius.Card))
                .background(if (phase.inCancelZone) moyuColors.statusDanger else moyuColors.mediaScrim)
                .padding(Moyu.Space.M),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            val countdown = phase.countdownSec
            if (countdown != null) {
                // 有意复用 MoyuLight.accentOnPrimary：mediaScrim 两条轨都是黑色，前景需恒为浅色
                Text("$countdown", style = MaterialTheme.typography.displaySmall, color = MoyuLight.accentOnPrimary)
            } else {
                // 有意复用 Size.PlayBadge(44dp)作波形区高度(spec §3.2),不另加 token。
                Canvas(modifier = Modifier.fillMaxWidth().height(Moyu.Size.PlayBadge)) {
                    val w = barWidth.toPx()
                    val g = gap.toPx()
                    val midY = size.height / 2
                    history.forEachIndexed { i, a ->
                        val h = (a.coerceIn(0f, 1f) * size.height).coerceAtLeast(w)
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(i * (w + g), midY - h / 2),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(w / 2),
                        )
                    }
                }
            }
            Text(
                stringResource(if (phase.inCancelZone) R.string.media_voice_release_cancel else R.string.media_voice_release_send_or_cancel),
                style = MaterialTheme.typography.labelMedium,
                // 有意复用 MoyuLight.accentOnPrimary：mediaScrim 两条轨都是黑色，前景需恒为浅色
                color = MoyuLight.accentOnPrimary,
            )
        }
    }
}
