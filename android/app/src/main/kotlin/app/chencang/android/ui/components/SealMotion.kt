package app.chencang.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import app.chencang.design.Moyu
import app.chencang.design.moyuColors

/** 落章缩放起点(未出现);1.0 = 稳定态(已出现)。差值反推淡入透明度。 */
private const val StampInitialScale = 1.4f
private const val StampFinalScale = 1.0f

/**
 * 呼吸印:等待态循环缩放 0.96↔1.04 + 透明度 0.7↔1.0,提示「正在核验」。
 * 图形沿用 M2 会话列表已验证联系人用的同一图标(`Icons.Default.Verified`,
 * 见 ConversationListScreen.kt),保持「印」的视觉语义跨屏一致。
 */
@Composable
fun SealBreathIcon(size: Dp) {
    val infinite = rememberInfiniteTransition(label = "seal-breath")
    val breathScale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(Moyu.Motion.Gentle * 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "seal-breath-scale",
    )
    val breathAlpha by infinite.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(Moyu.Motion.Gentle * 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "seal-breath-alpha",
    )
    Icon(
        Icons.Default.Verified,
        contentDescription = null,
        tint = moyuColors.accentPrimary,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = breathScale
                scaleY = breathScale
                alpha = breathAlpha
            },
    )
}

/**
 * 落章动效:从 1.4× 缩小到 1.0× 并同步淡入,模拟玉印按下的力度感——
 * `visible` 翻转驱动同一条 [animateFloatAsState] 曲线,scale 越接近
 * [StampFinalScale] 越不透明,越接近 [StampInitialScale] 越透明。
 */
fun Modifier.sealStampIn(visible: Boolean): Modifier = composed {
    val stampScale by animateFloatAsState(
        targetValue = if (visible) StampFinalScale else StampInitialScale,
        animationSpec = tween(Moyu.Motion.Standard),
        label = "seal-stamp-in",
    )
    graphicsLayer {
        scaleX = stampScale
        scaleY = stampScale
        alpha = ((StampInitialScale - stampScale) / (StampInitialScale - StampFinalScale)).coerceIn(0f, 1f)
    }
}
