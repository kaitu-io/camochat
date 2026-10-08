package app.chencang.shared.media

/**
 * 富媒体常量（spec 2026-09-25 §1–§3，裁决 R7）。数值与 core
 * `payload::MEDIA_MAX_BLOB_LEN_*` 和 `infra/media/lambda` 的 `LIMITS` 同步。
 * 中转的 base URL **不在这里**——只在 [MediaTransport]。
 */
object MediaConstants {
    const val KIND_VOICE = 1
    const val KIND_IMAGE = 2
    const val KIND_VIDEO = 3

    /** 一帧 1..9 条引用。 */
    const val MAX_ITEMS = 9

    const val MAX_BLOB_VOICE = 2_097_152L
    const val MAX_BLOB_IMAGE = 2_097_152L
    const val MAX_BLOB_VIDEO = 31_457_280L

    /** `.cca` 头 34 字节 + AEAD tag 16 字节。 */
    const val CCA_OVERHEAD = 50L

    const val MAX_VOICE_MS = 60_000
    const val MIN_VOICE_MS = 1_000
    const val VOICE_COUNTDOWN_FROM_MS = 50_000
    const val MAX_VIDEO_MS = 60_000

    const val IMAGE_MAX_EDGE = 1920
    /** 视频输出短边上限（按显示方向；只缩不放，宽高取偶数）。spec 2026-09-30 §4.1。 */
    const val VIDEO_MAX_SHORT_EDGE = 720
    const val VIDEO_MAX_FPS = 30
    const val VIDEO_BITRATE_HEVC = 1_800_000
    /** 设备没有硬件 HEVC 编码器（或 HEVC 转码失败重试）时退 H.264 的码率。 */
    const val VIDEO_BITRATE_H264 = 2_500_000
    const val VIDEO_AUDIO_BITRATE_AAC = 64_000

    /** App 内「已过期」按发送时间精确 24 小时判定（与云端生命周期是两条独立时钟）。 */
    const val EXPIRY_MS = 86_400_000L

    const val MAX_CONCURRENT_DOWNLOADS = 2

    fun maxBlob(kind: Int): Long = when (kind) {
        KIND_VOICE -> MAX_BLOB_VOICE
        KIND_IMAGE -> MAX_BLOB_IMAGE
        KIND_VIDEO -> MAX_BLOB_VIDEO
        else -> throw IllegalArgumentException("unknown media kind $kind")
    }

    /** 明文预算 = `.cca` 上限 − 50。 */
    fun plainBudget(kind: Int): Long = maxBlob(kind) - CCA_OVERHEAD

    fun isExpired(sentAtMs: Long, nowMs: Long): Boolean = sentAtMs + EXPIRY_MS <= nowMs

    /** 进度 / 忙碌集合里一个媒体条目的 key，也是线程行的 LazyColumn key。 */
    fun progressKey(messageId: String, index: Int): String = "$messageId:$index"
}
