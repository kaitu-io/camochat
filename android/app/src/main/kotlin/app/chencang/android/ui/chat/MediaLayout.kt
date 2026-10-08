package app.chencang.android.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.annotation.StringRes
import app.chencang.design.Moyu
import app.chencang.shared.R
import app.chencang.shared.chat.Handoff
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.OutgoingMediaStatus

/** 媒体气泡的尺寸与文案(纯函数,尺寸全部来自 R11 token)。 */
object MediaLayout {
    data class BoxSize(val width: Dp, val height: Dp)

    /** 全屏看图的缩放范围与双击倍数(手势行为参数,不是视觉 token)。 */
    const val MAX_ZOOM = 5f
    const val DOUBLE_TAP_ZOOM = 2f

    /** 按帧里的 w/h 等比占位:长边 = MediaThumbMax,短边不小于 MediaThumbMin。 */
    fun thumbSize(
        w: Int,
        h: Int,
        max: Dp = Moyu.Size.MediaThumbMax,
        min: Dp = Moyu.Size.MediaThumbMin,
    ): BoxSize {
        if (w <= 0 || h <= 0) return BoxSize(max, max)
        return if (w >= h) {
            BoxSize(max, (max * (h.toFloat() / w)).coerceAtLeast(min))
        } else {
            BoxSize((max * (w.toFloat() / h)).coerceAtLeast(min), max)
        }
    }

    /** 语音气泡宽度随时长线性增长:1 s → VoiceBubbleMin,60 s → VoiceBubbleMax。 */
    fun voiceWidth(
        durMs: Int,
        min: Dp = Moyu.Size.VoiceBubbleMin,
        max: Dp = Moyu.Size.VoiceBubbleMax,
    ): Dp {
        val span = MediaConstants.MAX_VOICE_MS - MediaConstants.MIN_VOICE_MS
        val clamped = durMs.coerceIn(MediaConstants.MIN_VOICE_MS, MediaConstants.MAX_VOICE_MS)
        val f = (clamped - MediaConstants.MIN_VOICE_MS).toFloat() / span
        return min + (max - min) * f
    }

    fun voiceLabel(durMs: Int): String = "${(durMs + 500) / 1000}″"

    fun videoLabel(durMs: Int): String {
        val s = (durMs + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * 收到的媒体占位上的状态文案（spec §5.1；先分享、后上传 spec §2）。`awaiting` 按轮询窗口是否已过分两种：
     * 轮询中「等待对方上传」，窗口已过「还没收到文件 · 点击重试」（不断言是没传还是过期）。与 iOS 同口径。
     */
    @StringRes
    fun incomingStateRes(state: String, windowExpired: Boolean): Int? = when (state) {
        MediaItem.STATE_EXPIRED -> R.string.media_failure_expired
        MediaItem.STATE_CORRUPT -> R.string.media_failure_corrupt
        MediaItem.STATE_AWAITING -> if (windowExpired) R.string.media_awaiting_stalled else R.string.media_awaiting
        else -> null
    }

    /** 「等待对方上传」期间占位上转圈；窗口过后不转（等用户点）。 */
    fun showsAwaitingSpinner(state: String, windowExpired: Boolean): Boolean =
        state == MediaItem.STATE_AWAITING && !windowExpired

    /**
     * 发出媒体消息的状态行：[textRes] 文案，[failed] = 错误色、可点（重试）；
     * [reopenable] = 已上传但还没被分享出去（还没发 / 只复制过），点了可以再把卡片叫回来。
     */
    data class OutLine(@StringRes val textRes: Int, val failed: Boolean, val reopenable: Boolean)

    /**
     * 发出媒体消息的状态行（spec 2026-09-30 §1.3）。[busy] = 这条消息正在加密 / 上传：只有「发送失败」在加密重跑时
     * 改显「加密中」、不可点。「对方还看不到」不再因 busy 改写（终审 F2：永久失败已是终态，busy 时的
     * 「对方还看不到」只是认领与改「上传中」之间的一瞬，与 iOS 同样显示、可点）。
     * 全部上传完后，状态行只说用户做过的事（[handoff]）。
     */
    fun outStatusLine(status: OutgoingMediaStatus, handoff: Handoff, busy: Boolean): OutLine = when {
        status == OutgoingMediaStatus.UPLOADED ->
            OutLine(handoff.statusRes, failed = false, reopenable = handoff != Handoff.SHARED)
        busy && status == OutgoingMediaStatus.ENCRYPT_FAILED ->
            OutLine(R.string.media_status_encrypting, failed = false, reopenable = false)
        else -> OutLine(requireNotNull(status.statusRes), failed = status.showsFailureMark, reopenable = false)
    }
}
