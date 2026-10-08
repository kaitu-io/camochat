package app.chencang.android.ui.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import app.chencang.android.R
import app.chencang.design.Moyu
import app.chencang.shared.R as SharedR

/**
 * 配对卡片图 v2：360x480 dp 的版面按固定 3 px/dp 画成 1080x1440 位图，不依赖设备密度。
 * 自上而下：顶栏（品牌 + 步骤）、标题、邀请专有的一句话卖点、二维码（白底圆角，自己画 quiet zone）、
 * 扫码提示、小字说明。顶栏与标题单行/两行内缩小字号而不换行、不截断；说明文字永不截断。
 * 颜色全部取不随暗色变化的 share-card / qr token。
 */
object PairingCard {
    enum class Kind { INVITE, RESPONSE }

    /** 每 dp 的像素数，导出尺寸固定为 1080x1440。 */
    const val SCALE = 3

    /** quiet zone 至少 4 个模块（QR 规范）。 */
    private const val QUIET_MODULES = 4

    /** 标题：邀请/回执 x 有无昵称。昵称为空白按匿名版。 */
    fun title(context: Context, kind: Kind, name: String): String {
        val n = name.trim()
        return when (kind) {
            Kind.INVITE ->
                if (n.isEmpty()) context.getString(SharedR.string.share_card_invite_title_anonymous)
                else context.getString(SharedR.string.share_card_invite_title, n)
            Kind.RESPONSE ->
                if (n.isEmpty()) context.getString(SharedR.string.share_card_response_title_anonymous)
                else context.getString(SharedR.string.share_card_response_title, n)
        }
    }

    /** 单行文字缩小的下限（占原字号的比例）。 */
    private const val MIN_SHRINK = 0.7f

    /** 说明文字的行数上限，只是防御；正常版面远用不到，且不加省略号。 */
    private const val NOTE_MAX_LINES = 8

    /** 渲染结果：位图，以及说明文字底边的 y（px），供测试断言没有溢出 480。 */
    class Rendered(val bitmap: Bitmap, val contentBottom: Float)

    fun render(context: Context, kind: Kind, name: String, link: String): Bitmap =
        renderMeasured(context, kind, name, link).bitmap

    fun renderMeasured(context: Context, kind: Kind, name: String, link: String): Rendered {
        val s = SCALE.toFloat()
        val w = Moyu.Size.ShareCardWidth.value * s
        val h = Moyu.Size.ShareCardHeight.value * s
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(R.color.moyu_share_card_background))

        val pad = Moyu.Space.Xl.value * s
        val contentW = (w - 2 * pad).toInt()

        fun paint(sp: Float, colorRes: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(colorRes)
            textSize = sp * s
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        // 顶栏：整宽色带，左品牌右步骤，一行放不下就两段一起按比例缩小（下限 MIN_SHRINK）。
        val headerH = Moyu.Size.ShareCardHeader.value * s
        canvas.drawRect(0f, 0f, w, headerH, Paint().apply { color = context.getColor(R.color.moyu_share_card_header_background) })
        val brand = context.getString(SharedR.string.share_card_brand)
        val step = context.getString(
            if (kind == Kind.INVITE) SharedR.string.share_card_step_invite else SharedR.string.share_card_step_response,
        )
        val brandPaint = paint(Moyu.FontSize.Callout.value, R.color.moyu_share_card_header_text, true)
        val stepPaint = paint(Moyu.FontSize.Callout.value, R.color.moyu_share_card_header_text, true)
        val headerGap = Moyu.Space.M.value * s
        val need = brandPaint.measureText(brand) + stepPaint.measureText(step) + headerGap
        val ratio = (contentW / need).coerceIn(MIN_SHRINK, 1f)
        brandPaint.textSize *= ratio
        stepPaint.textSize *= ratio
        val baseline = headerH / 2 - (brandPaint.ascent() + brandPaint.descent()) / 2
        canvas.drawText(brand, pad, baseline, brandPaint)
        canvas.drawText(step, w - pad - stepPaint.measureText(step), baseline, stepPaint)

        var y = headerH

        fun layout(str: String, tp: TextPaint, maxLines: Int, ellipsize: Boolean): StaticLayout =
            StaticLayout.Builder.obtain(str, 0, str.length, tp, contentW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines)
                .apply { if (ellipsize) setEllipsize(TextUtils.TruncateAt.END) }
                .build()

        fun draw(layout: StaticLayout, gap: Float) {
            canvas.save()
            canvas.translate(pad, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }

        val gapXs = Moyu.Space.Xs.value * s
        val gapS = Moyu.Space.S.value * s
        val gapM = Moyu.Space.M.value * s

        // 标题：最多两行，放不下先缩小字号（下限 MIN_SHRINK），再不行才省略号。
        val titleText = title(context, kind, name)
        val titlePaint = paint(Moyu.FontSize.Title.value, R.color.moyu_share_card_text, true)
        val minTitleSize = titlePaint.textSize * MIN_SHRINK
        while (titlePaint.textSize > minTitleSize &&
            layout(titleText, titlePaint, Int.MAX_VALUE, false).lineCount > 2
        ) {
            titlePaint.textSize -= s / 2
        }
        val titleLayout = layout(titleText, titlePaint, 2, true)
        val pitchLayout = if (kind == Kind.INVITE) {
            layout(
                context.getString(SharedR.string.share_card_invite_pitch),
                paint(Moyu.FontSize.Callout.value, R.color.moyu_share_card_text_secondary, false),
                2,
                true,
            )
        } else {
            null
        }
        val hintLayout = layout(context.getString(SharedR.string.share_card_scan_hint), paint(Moyu.FontSize.Callout.value, R.color.moyu_share_card_text, true), 2, true)
        val noteRes = if (kind == Kind.INVITE) SharedR.string.share_card_invite_note else SharedR.string.share_card_response_note
        val noteLayout = layout(context.getString(noteRes), paint(Moyu.FontSize.Caption.value, R.color.moyu_share_card_text_secondary, false), NOTE_MAX_LINES, false)

        // 内容在顶栏下方的区域里垂直居中（回执卡内容少，不留一大片底部空白）；放不下时贴顶，由测试抓溢出。
        val box = Moyu.Size.ShareCardQr.value * s
        val contentH = titleLayout.height + gapS + (pitchLayout?.let { it.height + gapS } ?: 0f) +
            box + gapS + hintLayout.height + gapXs + noteLayout.height
        y += maxOf(gapM, (h - headerH - contentH) / 2)

        draw(titleLayout, gapS)
        pitchLayout?.let { draw(it, gapS) }

        // 二维码：整块 ShareCardQr 见方的白底圆角，模块像素宽取整后居中。
        val boxLeft = (w - box) / 2
        val radius = Moyu.Radius.Card.value * s
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.moyu_qr_quiet_zone) }
        canvas.drawRoundRect(RectF(boxLeft, y, boxLeft + box, y + box), radius, radius, bg)
        val matrix = PairingQr.matrix(link)
        val n = matrix.width
        val module = ((box / (n + 2 * QUIET_MODULES)).toInt()).coerceAtLeast(1)
        val qrSide = module * n
        val originX = boxLeft + (box - qrSide) / 2
        val originY = y + (box - qrSide) / 2
        val dark = Paint().apply { color = context.getColor(R.color.moyu_qr_module) }
        for (my in 0 until n) {
            for (mx in 0 until n) {
                if (matrix[mx, my]) {
                    val left = originX + mx * module
                    val top = originY + my * module
                    canvas.drawRect(left, top, left + module, top + module, dark)
                }
            }
        }
        y += box + gapS

        draw(hintLayout, gapXs)
        draw(noteLayout, 0f)
        return Rendered(bitmap, y)
    }
}
