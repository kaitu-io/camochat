package app.chencang.shared.media

import androidx.annotation.StringRes
import app.chencang.shared.R

/**
 * spec §5.1 的用户可见错误。前五个是 [MediaTransport] 的错误枚举（裁决 R6）。
 * 上传永久失败的三句（太大 / 丢失 / 被拒）与气泡状态行同源：取自 [OutgoingFailureReason]。
 */
enum class MediaFailure(@get:StringRes val messageRes: Int) {
    RATE_LIMITED(R.string.media_failure_rate_limited),
    TOO_LARGE(OutgoingFailureReason.TOO_LARGE.messageRes),
    GONE(R.string.media_failure_expired),
    NETWORK(R.string.media_failure_network),
    SERVER(R.string.media_failure_server),
    STORAGE_FULL(R.string.media_failure_storage_full),
    CORRUPT(R.string.media_failure_corrupt),
    SESSION_LOST(R.string.media_failure_session_lost),
    NOT_READY(R.string.media_failure_not_ready),
    /** 发送途中用户删掉了这条消息：安静结束，不提示、不复活。 */
    DELETED(R.string.media_failure_deleted),

    /** 已分享的消息某项 `.cca` 不在了：密文已冻结，不能重新加密，只能提示（spec 2026-09-30 §1.3）。 */
    FILE_MISSING(OutgoingFailureReason.FILE_MISSING.messageRes),

    /** 发送端 `.cca` 还在但这次没读出来（偶发 I/O 错）：可重试，下次重传同一份密文。 */
    READ_FAILED(R.string.media_failure_read_failed),

    /**
     * 中转以 4xx（403/408/429/413 之外）拒收：请求本身不被接受，重试多少次都一样——上传即放弃
     * （与 iOS 同口径）。403/408/429、3xx、5xx 仍可重试。
     */
    REJECTED(OutgoingFailureReason.REJECTED.messageRes),
}

class MediaFailureException(
    val failure: MediaFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
