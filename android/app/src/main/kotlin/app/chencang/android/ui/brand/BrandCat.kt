package app.chencang.android.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.chencang.android.ui.splash.SplashGeometry

/**
 * 「气泡猫」品牌标（brand/camochat-glyph.svg），轮廓与开屏动画同一份 [SplashGeometry]。
 * 眼睛是奇偶填充的洞，透出底色；整只猫只用一种颜色。
 */
internal class BrandCatPaths {
    val ears = parse(SplashGeometry.EAR_LEFT + " " + SplashGeometry.EAR_RIGHT)
    val tail = parse(SplashGeometry.TAIL)
    val body = parse(SplashGeometry.BODY).apply { fillType = PathFillType.EvenOdd }
    val pupils = parse(SplashGeometry.PUPILS)

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
}

internal object BrandCatMetrics {
    const val EAR_STROKE = 7f
    const val TAIL_STROKE = 2f

    /** 猫在 100 单位网格里实际占的范围（含耳朵、尾巴的描边）。 */
    const val LEFT = 6f
    const val TOP = 9.5f
    const val RIGHT = 88f
    const val BOTTOM = 96.5f
    const val EXTENT = BOTTOM - TOP
}

/** 在 100 单位网格坐标里画整只猫（不含眨眼的眼皮）。 */
internal fun DrawScope.drawBrandCatInGrid(paths: BrandCatPaths, color: Color) {
    drawPath(paths.ears, color)
    drawPath(paths.ears, color, style = Stroke(width = BrandCatMetrics.EAR_STROKE, join = StrokeJoin.Round))
    drawPath(paths.tail, color)
    drawPath(paths.tail, color, style = Stroke(width = BrandCatMetrics.TAIL_STROKE, join = StrokeJoin.Round))
    drawPath(paths.body, color)
    drawPath(paths.pupils, color)
}

/** 把猫的实际轮廓等比放进 [topLeft] 起、边长 [side] 的方框，居中。 */
internal fun DrawScope.drawBrandCat(paths: BrandCatPaths, color: Color, topLeft: Offset, side: Float) {
    val k = side / BrandCatMetrics.EXTENT
    val w = (BrandCatMetrics.RIGHT - BrandCatMetrics.LEFT) * k
    translate(
        left = topLeft.x + (side - w) / 2 - BrandCatMetrics.LEFT * k,
        top = topLeft.y - BrandCatMetrics.TOP * k,
    ) {
        scale(k, k, Offset.Zero) { drawBrandCatInGrid(paths, color) }
    }
}

/** 画进一张位图（分享卡片等非 Compose 场景）。 */
internal fun drawBrandCat(canvas: android.graphics.Canvas, paths: BrandCatPaths, color: Color, left: Float, top: Float, side: Float) {
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(canvas),
        Size(canvas.width.toFloat(), canvas.height.toFloat()),
    ) {
        drawBrandCat(paths, color, Offset(left, top), side)
    }
}

/** 纯装饰的品牌猫：读屏跳过，大小由 [modifier] 决定（取宽高中较小的一边）。 */
@Composable
internal fun BrandCat(color: Color, modifier: Modifier = Modifier) {
    val paths = remember { BrandCatPaths() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val side = minOf(size.width, size.height)
        drawBrandCat(paths, color, Offset((size.width - side) / 2, (size.height - side) / 2), side)
    }
}
