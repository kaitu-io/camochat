package app.chencang.shared.media

import androidx.annotation.StringRes
import app.chencang.shared.R
import app.chencang.shared.chat.MediaItem

/**
 * 发送端永久上传失败的原因（spec 2026-09-30 §1.3，终审 F2），与 iOS `.permanentlyFailed(.tooLarge/.rejected/.fileMissing)`
 * 同口径。[messageRes] 即状态行与点击提示的文案。
 */
enum class OutgoingFailureReason(@get:StringRes val messageRes: Int) {
    // 声明顺序即相册里各项原因不一时的显示优先级（TOO_LARGE > REJECTED > FILE_MISSING，与 iOS 同）。
    /** 中转 413。 */
    TOO_LARGE(R.string.media_failure_too_large),

    /** 中转以其它 4xx 拒收。 */
    REJECTED(R.string.media_failure_rejected),

    /** `.cca` 不在了：密文已冻结，不能重新加密。 */
    FILE_MISSING(R.string.media_failure_file_missing),
    ;

    companion object {
        /**
         * `media_item.upload_failure`（[MediaFailure] 的名字）→ 原因。只会写 TOO_LARGE / REJECTED / FILE_MISSING；
         * 认不出的非空值（降级 / 将来新增的原因）按 REJECTED「文件无法发送」，仍是永久失败——与 iOS 解码同口径（UAT N2）。
         */
        fun fromStored(stored: String): OutgoingFailureReason =
            entries.firstOrNull { it.name == stored } ?: REJECTED
    }
}

/**
 * 发送端一条媒体消息的整体状态（spec 2026-09-30 §1.3），与 iOS `OutgoingMediaStatus` 同口径。
 * 气泡状态行、红「!」、会话列表「[未上传] 」前缀都按它来。
 */
sealed class OutgoingMediaStatus(
    /** 气泡下方的状态行；`null` = 用户交付状态（[app.chencang.shared.chat.Handoff]）接管。 */
    @get:StringRes val statusRes: Int?,
) {
    /** 还在压缩/加密（或加密完、分享文本还没落库）。 */
    data object ENCRYPTING : OutgoingMediaStatus(R.string.media_status_encrypting)

    /** 已分享，有项在上传（含排队、退避中）。 */
    data object UPLOADING : OutgoingMediaStatus(R.string.media_status_uploading)

    /** 全部项已上传。 */
    data object UPLOADED : OutgoingMediaStatus(null)

    /** 已分享，有项上传失败且还能重传（`.cca` 在、没有永久原因）：点了立刻交给上传引擎，不重新加密、不重弹分享面板。 */
    data object NOT_VISIBLE_TO_PEER : OutgoingMediaStatus(R.string.media_status_not_visible)

    /**
     * 已分享，失败的项全是永久失败（太大 / 被拒 / `.cca` 丢失）：终态。状态行 = 原因文案，点击只提示同一句话，
     * 不重传、列表不加前缀、自愈跳过；用户只能删。
     */
    data class PermanentlyFailed(val reason: OutgoingFailureReason) : OutgoingMediaStatus(reason.messageRes)

    /** 还没分享（加密/封帧阶段失败）：点红「!」重走 seal（允许重新加密）。 */
    data object ENCRYPT_FAILED : OutgoingMediaStatus(R.string.media_status_encrypt_failed)

    /** 红「!」与错误色状态行。 */
    val showsFailureMark: Boolean
        get() = this == NOT_VISIBLE_TO_PEER || this is PermanentlyFailed || this == ENCRYPT_FAILED

    /** 会话列表预览前缀：只有「对方还看不到」催一下（永久失败重传不了，不催）。 */
    @get:StringRes
    val listPreviewPrefixRes: Int? get() = if (this == NOT_VISIBLE_TO_PEER) R.string.media_unsent_prefix else null
}

/**
 * 发送端媒体状态（纯函数，两端同口径）。优先级（UAT R1 裁决）：encrypting > 可重试的失败 > uploading >
 * 永久失败 > 全部已上传——永久失败用户做不了什么，相册里还有项在传就先显示「上传中」。
 * - 未分享（[shared] = false）：有 `encrypting`/`uploading` → [OutgoingMediaStatus.ENCRYPTING]，
 *   否则 → [OutgoingMediaStatus.ENCRYPT_FAILED]（旧数据里「项全 sealed 但封帧失败」也能重试）。
 * - 已分享：有 `encrypting` → ENCRYPTING；有**可重试**的 `failed` 项（没有 `upload_failure` 且 `.cca` 还在）→
 *   NOT_VISIBLE_TO_PEER；有 `uploading` → UPLOADING；还有 `failed`（全是永久失败）→ [OutgoingMediaStatus.PermanentlyFailed]
 *   （各项原因：记了原因用原因，`.cca` 不在则 FILE_MISSING；不一时按 TOO_LARGE > REJECTED > FILE_MISSING 取，与 iOS 同）；
 *   否则 UPLOADED。
 * [ccaExists]（按条目下标）只对没有永久原因的 `failed` 项调用——正常路径不碰文件系统。
 */
fun outgoingMediaStatus(items: List<MediaItem>, shared: Boolean, ccaExists: (Int) -> Boolean): OutgoingMediaStatus {
    if (items.any { it.state == MediaItem.STATE_ENCRYPTING }) return OutgoingMediaStatus.ENCRYPTING
    if (!shared) {
        return if (items.any { it.state == MediaItem.STATE_UPLOADING }) {
            OutgoingMediaStatus.ENCRYPTING
        } else {
            OutgoingMediaStatus.ENCRYPT_FAILED
        }
    }
    val failed = items.filter { it.state == MediaItem.STATE_FAILED }
    if (failed.any { it.uploadFailure == null && ccaExists(it.index) }) return OutgoingMediaStatus.NOT_VISIBLE_TO_PEER
    if (items.any { it.state == MediaItem.STATE_UPLOADING }) return OutgoingMediaStatus.UPLOADING
    if (failed.isNotEmpty()) {
        val reason = failed.minOf { it.uploadFailure?.let(OutgoingFailureReason::fromStored) ?: OutgoingFailureReason.FILE_MISSING }
        return OutgoingMediaStatus.PermanentlyFailed(reason)
    }
    return OutgoingMediaStatus.UPLOADED
}
