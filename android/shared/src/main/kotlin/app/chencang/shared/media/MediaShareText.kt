package app.chencang.shared.media

import android.content.Context
import app.chencang.shared.R

/**
 * 裁决 R1：分享面板与「复制」给出的两行文本。第一行给人看（[ShareHeaders] 产出，跟随系统语言），
 * 媒体首行的链接只含首条 blob_id（单拿链接解不开）；第二行是真正的 wire。
 * 文字消息首行不含任何密文或明文（spec 2026-09-30 §5.4）。
 */
object MediaShareText {
    /** 首行 + 换行 + wire。`WireLocator` 从后往前试，首行解不开自然跳过。 */
    fun compose(headerLine: String, wire: String): String = "$headerLine\n$wire"
}

/** The human-readable first line of a share text, in the current system language. */
interface ShareHeaders {
    /** First line for a text message: no ciphertext, no plaintext, just how to decrypt it. */
    fun text(): String

    /** First line for a media message of [count] items of [kind]; links to the first blob's landing page. */
    fun media(kind: Int, count: Int, firstBlobId: String): String
}

/** Reads the resources on every call, so the line follows the system language at the moment of sharing. */
class ResourceShareHeaders(private val context: Context, private val site: () -> String) : ShareHeaders {
    override fun text(): String = context.getString(R.string.card_share_header_text, site() + "m/")

    override fun media(kind: Int, count: Int, firstBlobId: String): String {
        val res = context.resources
        val label = when (kind) {
            MediaConstants.KIND_VOICE -> res.getString(R.string.media_kind_voice)
            MediaConstants.KIND_IMAGE ->
                if (count > 1) res.getQuantityString(R.plurals.media_photo_count, count, count) else res.getString(R.string.media_kind_image)
            MediaConstants.KIND_VIDEO -> res.getString(R.string.media_kind_video)
            else -> throw IllegalArgumentException("unknown media kind $kind")
        }
        return res.getString(R.string.card_share_header_media, label, site() + "m/" + firstBlobId)
    }
}
