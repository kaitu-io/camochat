package app.chencang.android.ui.splash

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** 一段关键帧区间（毫秒，从开屏层第一帧算起）。 */
internal data class Span(val start: Float, val end: Float) {
    /** 区间内的进度，夹在 0…1。 */
    fun at(t: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)
}

/**
 * 开屏的两个版本，同一套动作，只是关键帧不同。
 * 完整版只在装好后第一次启动时播；之后每次冷启动播短版（没有字标）。
 */
internal enum class SplashVariant(
    val duck: Span,
    val peek: Span,
    val decoyIn: Span,
    val pawsIn: Span,
    val pawsMove: Span,
    /** null = 这一版不出字标。 */
    val wordmark: Span?,
    val camouflage: Span?,
    val blink: Span,
    val fade: Span,
) {
    FULL(
        duck = Span(200f, 550f),
        peek = Span(550f, 900f),
        decoyIn = Span(200f, 320f),
        pawsIn = Span(650f, 720f),
        pawsMove = Span(650f, 850f),
        wordmark = Span(550f, 850f),
        camouflage = Span(1050f, 1350f),
        blink = Span(1400f, 1550f),
        fade = Span(1900f, 2200f),
    ),
    SHORT(
        duck = Span(120f, 380f),
        peek = Span(380f, 620f),
        decoyIn = Span(120f, 200f),
        pawsIn = Span(450f, 500f),
        pawsMove = Span(450f, 600f),
        wordmark = null,
        camouflage = null,
        blink = Span(620f, 740f),
        fade = Span(750f, 1000f),
    ),
    ;

    /** 点屏幕跳过、或系统关了动画时，直接从淡出开始。 */
    val skipTo: Float get() = fade.start

    /** 到这一刻开屏层整个撤掉。 */
    val duration: Float get() = fade.end

    fun frameAt(t: Float): SplashFrame {
        val rise = ease(duck.at(t))
        val paws = back(pawsMove.at(t))
        val w = wordmark?.let { ease(it.at(t)) } ?: 0f
        val g = camouflage?.let { ease(it.at(t)) } ?: 0f
        val f = ease(fade.at(t))
        return SplashFrame(
            catY = rise * 16f - back(peek.at(t)) * 20f,
            clipBottom = 100f - 20f * rise,
            decoyY = (1f - rise) * 34f,
            decoyAlpha = decoyIn.at(t),
            pawsAlpha = pawsIn.at(t),
            pawsY = (1f - paws) * 8f,
            wordmarkAlpha = w,
            wordmarkY = (1f - w) * 6f,
            hFillAlpha = 1f - g,
            hGhostAlpha = g,
            lidScaleY = 0.9f * sin(PI.toFloat() * blink.at(t)),
            overlayAlpha = 1f - f,
            contentScale = 1f + 0.06f * f,
        )
    }

    companion object {
        fun ease(t: Float): Float = 1f - (1f - t).pow(3)

        fun back(t: Float): Float {
            val c = 1.7f
            return 1f + (c + 1f) * (t - 1f).pow(3) + c * (t - 1f).pow(2)
        }
    }
}

/** 某一时刻的画面状态；位移都是网格单位（猫占 100 单位）。 */
internal data class SplashFrame(
    val catY: Float,
    /** 猫只在这条线以上可见（躲在气泡后面）。 */
    val clipBottom: Float,
    val decoyY: Float,
    val decoyAlpha: Float,
    val pawsAlpha: Float,
    val pawsY: Float,
    val wordmarkAlpha: Float,
    val wordmarkY: Float,
    val hFillAlpha: Float,
    val hGhostAlpha: Float,
    /** 眼皮盖住眼睛的比例：0 睁眼，0.9 几乎闭上。 */
    val lidScaleY: Float,
    val overlayAlpha: Float,
    val contentScale: Float,
)
