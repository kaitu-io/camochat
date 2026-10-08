package app.chencang.shared.chat

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 一条媒体消息里的一个媒体条目（裁决 R4）。多图 = 一条 [ChatMessage] + 2..9 个条目。
 * 文件在 `<filesDir>/media/<messageId>/<index>.cca|.bin`（见 MediaFiles）。
 */
@Entity(tableName = "media_item", primaryKeys = ["message_id", "idx"])
data class MediaItem(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "idx") val index: Int,
    /** 1 语音 / 2 图片 / 3 视频（MediaConstants.KIND_*）。 */
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "dur_ms") val durMs: Int,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    /** 加密 blob 总长（含 `.cca` 头与 tag），发送方加密前为 0。 */
    @ColumnInfo(name = "byte_len") val byteLen: Long,
    /** 32 字节；发送方加密前为空数组。 */
    @ColumnInfo(name = "blob_secret") val blobSecret: ByteArray,
    /** 22 字符 base64url；发送方加密前为空串。 */
    @ColumnInfo(name = "blob_id") val blobId: String,
    @ColumnInfo(name = "state") val state: String,
    /** 明文 `.bin` 的绝对路径：发送方一开始就有；接收方解密完成后才有。 */
    @ColumnInfo(name = "local_path") val localPath: String? = null,
    /** 收到的语音是否播放过（红点）。 */
    @ColumnInfo(name = "played", defaultValue = "0") val played: Boolean = false,
    /**
     * 发送方：这一项**永久**上传失败的原因（[app.chencang.shared.media.MediaFailure] 的名字：
     * `TOO_LARGE` / `REJECTED` / `FILE_MISSING`）。非空 = 终态：自愈与手动重试都不再理它（spec 2026-09-30 §1.3）。
     * 可重试失败（上传引擎放弃）不写，留给自愈。v4 新增。
     */
    @ColumnInfo(name = "upload_failure") val uploadFailure: String? = null,
    /**
     * 发送方：这一项从何时起「等着上传」（epoch ms）——分享文本落库时写入，手动重试时重置。
     * 上传引擎的 6 小时上限按它算（spec §1.1.1）。v4 新增；v4 之前的行为 null（回落到消息时间）。
     */
    @ColumnInfo(name = "upload_since") val uploadSince: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaItem) return false
        return messageId == other.messageId && index == other.index && kind == other.kind &&
            durMs == other.durMs && width == other.width && height == other.height &&
            byteLen == other.byteLen && blobSecret.contentEquals(other.blobSecret) &&
            blobId == other.blobId && state == other.state && localPath == other.localPath &&
            played == other.played && uploadFailure == other.uploadFailure && uploadSince == other.uploadSince
    }

    override fun hashCode(): Int {
        var h = messageId.hashCode()
        h = 31 * h + index
        h = 31 * h + state.hashCode()
        h = 31 * h + blobSecret.contentHashCode()
        return h
    }

    companion object {
        // 发送方
        const val STATE_ENCRYPTING = "encrypting"
        const val STATE_UPLOADING = "uploading"
        const val STATE_SEALED = "sealed"
        const val STATE_FAILED = "failed"

        // 接收方（failed 两边共用）
        const val STATE_PENDING = "pending"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_READY = "ready"
        const val STATE_EXPIRED = "expired"
        const val STATE_CORRUPT = "corrupt"

        /**
         * 接收方：中转答 403/404 且本地收到不满 24 h（`classifyGone`）——等待对方上传，非终态、可再取
         * （先分享、后上传 spec §2）。可见会话里由 `AwaitingPoller` 轮询。
         */
        const val STATE_AWAITING = "awaiting"
    }
}
