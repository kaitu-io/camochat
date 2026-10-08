package app.chencang.android.ui.splash

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.chencang.android.ui.brand.BrandCatPaths
import app.chencang.android.ui.brand.drawBrandCatInGrid
import app.chencang.design.moyuColors

/**
 * 系统启动页把 windowSplashScreenAnimatedIcon（ic_launcher_foreground，108 视口）画成 288dp 见方、
 * 居中于整块屏幕（API 31+ 的系统启动页与 core-splashscreen 在 29–30 上的兼容实现都是这样）。
 * 图标把猫网格按 0.56 缩进 108 视口，所以一个网格单位 = 288 / 108 × 0.56 dp，网格点
 * ([SplashGeometry.ANCHOR_X], [SplashGeometry.ANCHOR_Y]) 落在屏幕正中。开屏层第一帧照此摆放，
 * 和系统启动页叠在一起看不出切换。
 */
private val GRID_UNIT = (288f / 108f * 0.56f).dp

/**
 * 冷启动开屏：猫躲到别人家的聊天气泡后面，再扒着边探出头，眨一下眼，整层淡出露出 App。
 * 纯装饰：读屏跳过；App 在下面照常加载；点一下跳到淡出；播完调 [onFinished]，由调用方把它移出界面树。
 */
@Composable
internal fun SplashOverlay(variant: SplashVariant, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    // 系统「动画时长缩放」为 0：只放淡出。
    val startAt = remember { if (ValueAnimator.areAnimatorsEnabled()) 0f else variant.skipTo }
    var t by remember { mutableFloatStateOf(startAt) }
    var skipRequested by remember { mutableStateOf(false) }

    LaunchedEffect(variant) {
        var origin = -1L
        var offset = startAt
        var skipped = false
        while (t < variant.duration) {
            withFrameNanos { now ->
                if (origin < 0) origin = now
                val elapsed = (now - origin) / 1_000_000f
                if (skipRequested && !skipped) {
                    skipped = true
                    if (offset + elapsed < variant.skipTo) offset = variant.skipTo - elapsed
                }
                t = offset + elapsed
            }
        }
        finished()
    }

    val paths = remember { SplashPaths() }
    val colors = moyuColors
    // t 只在绘制阶段读：每帧只重画，不重组。
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .graphicsLayer { alpha = variant.frameAt(t).overlayAlpha }
            .background(colors.splashBackground)
            .pointerInput(Unit) { detectTapGestures { skipRequested = true } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val frame = variant.frameAt(t)
            val unit = GRID_UNIT.toPx()
            withTransform({
                translate(
                    center.x - SplashGeometry.ANCHOR_X * unit,
                    center.y - SplashGeometry.ANCHOR_Y * unit,
                )
                scale(unit, unit, Offset.Zero)
                scale(
                    frame.contentScale,
                    frame.contentScale,
                    Offset(SplashGeometry.ZOOM_PIVOT_X, SplashGeometry.ZOOM_PIVOT_Y),
                )
            }) {
                drawScene(frame, paths, colors.splashMark, colors.splashDecoy)
            }
        }
    }
}

/** 画在 100 单位网格里。顺序：猫（被气泡挡住的部分裁掉）→ 气泡 → 字标 → 爪子。 */
private fun DrawScope.drawScene(frame: SplashFrame, paths: SplashPaths, mark: Color, decoy: Color) {
    clipRect(left = -20f, top = -40f, right = 120f, bottom = frame.clipBottom) {
        translate(top = frame.catY) {
            drawBrandCatInGrid(paths.cat, mark)
            if (frame.lidScaleY > 0f) {
                scale(1f, frame.lidScaleY, Offset(0f, SplashGeometry.LID_PIVOT_Y)) {
                    drawPath(paths.lids, mark)
                }
            }
        }
    }

    if (frame.decoyAlpha > 0f) {
        translate(top = frame.decoyY) {
            // 整个气泡作为一层淡入，里面两行字的 35% 是相对气泡的。
            withLayerAlpha(frame.decoyAlpha, Rect(6f, 68f, 94f, 98f)) {
                drawRoundRect(decoy, Offset(6f, 68f), Size(88f, 30f), CornerRadius(15f))
                drawRoundRect(mark.copy(alpha = 0.35f), Offset(18f, 77f), Size(46f, 5f), CornerRadius(2.5f))
                drawRoundRect(mark.copy(alpha = 0.35f), Offset(18f, 86f), Size(30f, 5f), CornerRadius(2.5f))
            }
        }
    }

    if (frame.wordmarkAlpha > 0f) {
        withTransform({
            translate(SplashGeometry.WORDMARK_X, SplashGeometry.WORDMARK_Y + frame.wordmarkY)
            scale(SplashGeometry.WORDMARK_SCALE, SplashGeometry.WORDMARK_SCALE, Offset.Zero)
        }) {
            val a = frame.wordmarkAlpha
            drawPath(paths.wordmarkRest, mark, alpha = a)
            if (frame.hFillAlpha > 0f) drawPath(paths.wordmarkH, mark, alpha = a * frame.hFillAlpha)
            if (frame.hGhostAlpha > 0f) {
                drawPath(
                    paths.wordmarkH,
                    mark,
                    alpha = a * frame.hGhostAlpha,
                    style = Stroke(
                        width = 5f,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(11f, 8f)),
                    ),
                )
            }
        }
    }

    if (frame.pawsAlpha > 0f) {
        translate(top = frame.pawsY) {
            val paw = mark.copy(alpha = frame.pawsAlpha)
            drawRoundRect(paw, Offset(29f, 63f), Size(13f, 10f), CornerRadius(5f))
            drawRoundRect(paw, Offset(58f, 63f), Size(13f, 10f), CornerRadius(5f))
        }
    }
}

private inline fun DrawScope.withLayerAlpha(alpha: Float, bounds: Rect, block: DrawScope.() -> Unit) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
        block()
        canvas.restore()
    }
}

private class SplashPaths {
    val cat = BrandCatPaths()
    val lids = parse(SplashGeometry.LIDS)
    val wordmarkRest = parse(SplashGeometry.WORDMARK_REST)
    val wordmarkH = parse(SplashGeometry.WORDMARK_H)

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
}
