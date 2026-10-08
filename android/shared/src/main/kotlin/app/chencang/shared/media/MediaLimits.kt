package app.chencang.shared.media

import androidx.annotation.StringRes
import app.chencang.shared.R

/** 选择/录制时的闸门（spec §5.1「超出大小或时长 → 选择时拦下」）与文案（字符串资源 id）。 */
object MediaLimits {
    @StringRes val VIDEO_TOO_LONG: Int = R.string.media_video_too_long
    @StringRes val VIDEO_TOO_BIG: Int = R.string.media_video_too_big
    @StringRes val VIDEO_UNREADABLE: Int = R.string.media_video_unreadable
    @StringRes val IMAGE_UNREADABLE: Int = R.string.media_image_unreadable
    @StringRes val IMAGE_TOO_BIG: Int = R.string.media_image_too_big
    @StringRes val VOICE_TOO_SHORT: Int = R.string.media_voice_too_short
    @StringRes val VOICE_FAILED: Int = R.string.media_voice_failed

    /**
     * 按整秒判「超过 60 秒」：系统相机按 60 秒上限录出的片子常是 60.0x 秒，必须能发；
     * 帧里的 `dur_ms` 再由 [clampDurationMs] 夹到 60000（core 的上限）。
     */
    @StringRes
    fun videoDurationError(durMs: Long): Int? =
        if (durMs / 1000 > MediaConstants.MAX_VIDEO_MS / 1000) VIDEO_TOO_LONG else null

    @StringRes
    fun videoSizeError(bytes: Long): Int? =
        if (bytes > MediaConstants.plainBudget(MediaConstants.KIND_VIDEO)) VIDEO_TOO_BIG else null

    fun clampDurationMs(durMs: Long): Int = durMs.coerceIn(0L, MediaConstants.MAX_VIDEO_MS.toLong()).toInt()

    /** 语音同样按 60 s 上限夹，但走语音自己的常量——不借用视频的 [clampDurationMs]。 */
    fun clampVoiceDurationMs(durMs: Long): Int = durMs.coerceIn(0L, MediaConstants.MAX_VOICE_MS.toLong()).toInt()
}
