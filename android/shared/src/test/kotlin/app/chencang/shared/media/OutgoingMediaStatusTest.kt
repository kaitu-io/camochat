package app.chencang.shared.media

import app.chencang.shared.R
import app.chencang.shared.chat.MediaItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** spec 2026-09-30 §1.3 + 与 iOS Task 7 同口径的优先级裁决。纯函数，不需要 Robolectric。 */
class OutgoingMediaStatusTest {
    private fun item(index: Int, state: String, uploadFailure: String? = null) = MediaItem(
        messageId = "m", index = index, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 1, height = 1,
        byteLen = 1L, blobSecret = ByteArray(32), blobId = "b$index", state = state, uploadFailure = uploadFailure,
    )

    private fun permanent(reason: OutgoingFailureReason) = OutgoingMediaStatus.PermanentlyFailed(reason)

    private val ccaAll: (Int) -> Boolean = { true }
    private val ccaNone: (Int) -> Boolean = { false }

    @Test
    fun anEncryptingItemIsEncrypting() {
        val items = listOf(item(0, MediaItem.STATE_ENCRYPTING), item(1, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = false, ccaExists = ccaAll)).isEqualTo(OutgoingMediaStatus.ENCRYPTING)
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll)).isEqualTo(OutgoingMediaStatus.ENCRYPTING)
    }

    @Test
    fun sharedWithAnUploadingItemIsUploading() {
        val items = listOf(item(0, MediaItem.STATE_SEALED), item(1, MediaItem.STATE_UPLOADING))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll)).isEqualTo(OutgoingMediaStatus.UPLOADING)
    }

    @Test
    fun allSealedIsUploaded() {
        val items = listOf(item(0, MediaItem.STATE_SEALED), item(1, MediaItem.STATE_SEALED))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaNone)).isEqualTo(OutgoingMediaStatus.UPLOADED)
    }

    @Test
    fun sharedFailedWithItsCcaIsNotVisibleToPeer() {
        val items = listOf(item(0, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll))
            .isEqualTo(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER)
    }

    @Test
    fun sharedFailedWithoutItsCcaIsPermanentlyFailedAsFileMissing() {
        val items = listOf(item(0, MediaItem.STATE_SEALED), item(1, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaNone))
            .isEqualTo(permanent(OutgoingFailureReason.FILE_MISSING))
    }

    // ---- 永久失败为终态（spec §1.3，终审 F2；与 iOS `.permanentlyFailed` 同口径）----

    @Test
    fun aPersistedPermanentReasonIsTerminalEvenThoughTheCcaIsStillThere() {
        for ((stored, reason) in listOf(
            "TOO_LARGE" to OutgoingFailureReason.TOO_LARGE,
            "REJECTED" to OutgoingFailureReason.REJECTED,
            "FILE_MISSING" to OutgoingFailureReason.FILE_MISSING,
        )) {
            val items = listOf(item(0, MediaItem.STATE_FAILED, uploadFailure = stored))
            assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll)).isEqualTo(permanent(reason))
        }
    }

    @Test
    fun anyRetryableFailedItemKeepsTheMessageNotVisibleToPeer() {
        val items = listOf(
            item(0, MediaItem.STATE_FAILED, uploadFailure = "TOO_LARGE"),
            item(1, MediaItem.STATE_FAILED),
        )
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll))
            .isEqualTo(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER)
    }

    @Test
    fun mixedPermanentReasonsShowTooLargeThenRejectedThenFileMissing() {
        // 与 iOS 同口径：原因不一时按 TOO_LARGE > REJECTED > FILE_MISSING 取，与下标无关。
        val tooLarge = listOf(
            item(0, MediaItem.STATE_FAILED, uploadFailure = "FILE_MISSING"),
            item(1, MediaItem.STATE_FAILED, uploadFailure = "REJECTED"),
            item(2, MediaItem.STATE_FAILED, uploadFailure = "TOO_LARGE"),
        )
        assertThat(outgoingMediaStatus(tooLarge, shared = true, ccaExists = ccaAll))
            .isEqualTo(permanent(OutgoingFailureReason.TOO_LARGE))
        val rejected = listOf(
            item(0, MediaItem.STATE_FAILED), // .cca 不在 → FILE_MISSING
            item(1, MediaItem.STATE_FAILED, uploadFailure = "REJECTED"),
        )
        assertThat(outgoingMediaStatus(rejected, shared = true, ccaExists = ccaNone))
            .isEqualTo(permanent(OutgoingFailureReason.REJECTED))
    }

    @Test
    fun anItemStillUploadingBeatsAPermanentFailure() {
        // 产品裁决（UAT R1）：永久失败用户做不了什么，相册里还有项在传就显示「上传中」；传完才亮原因。
        val items = listOf(item(0, MediaItem.STATE_FAILED, uploadFailure = "TOO_LARGE"), item(1, MediaItem.STATE_UPLOADING))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll))
            .isEqualTo(OutgoingMediaStatus.UPLOADING)
        val missing = listOf(item(0, MediaItem.STATE_FAILED), item(1, MediaItem.STATE_UPLOADING)) // .cca 不在
        assertThat(outgoingMediaStatus(missing, shared = true, ccaExists = ccaNone))
            .isEqualTo(OutgoingMediaStatus.UPLOADING)
        val done = listOf(item(0, MediaItem.STATE_FAILED, uploadFailure = "TOO_LARGE"), item(1, MediaItem.STATE_SEALED))
        assertThat(outgoingMediaStatus(done, shared = true, ccaExists = ccaAll))
            .isEqualTo(permanent(OutgoingFailureReason.TOO_LARGE))
    }

    @Test
    fun aRetryableFailureStillBeatsUploadingAndPermanentFailures() {
        val items = listOf(
            item(0, MediaItem.STATE_FAILED, uploadFailure = "TOO_LARGE"),
            item(1, MediaItem.STATE_UPLOADING),
            item(2, MediaItem.STATE_FAILED),
        )
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll))
            .isEqualTo(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER)
    }

    @Test
    fun uploadFailuresShareTheBubbleCopy() {
        // UAT N1：MediaFailure 的永久失败文案与气泡同源，不再各写一份（「服务拒收了这个文件，无法上传」已删）。
        assertThat(MediaFailure.REJECTED.messageRes).isEqualTo(OutgoingFailureReason.REJECTED.messageRes)
        assertThat(MediaFailure.TOO_LARGE.messageRes).isEqualTo(OutgoingFailureReason.TOO_LARGE.messageRes)
        assertThat(MediaFailure.FILE_MISSING.messageRes).isEqualTo(OutgoingFailureReason.FILE_MISSING.messageRes)
    }

    @Test
    fun anUnrecognisedStoredReasonDecodesAsRejected() {
        // 与 iOS 同口径（UAT N2）：降级 / 将来新增的原因 → 永久失败「文件无法发送」。
        assertThat(OutgoingFailureReason.fromStored("SOMETHING_NEW")).isEqualTo(OutgoingFailureReason.REJECTED)
        val items = listOf(item(0, MediaItem.STATE_FAILED, uploadFailure = "SOMETHING_NEW"))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll))
            .isEqualTo(permanent(OutgoingFailureReason.REJECTED))
        assertThat(permanent(OutgoingFailureReason.REJECTED).statusRes).isEqualTo(R.string.media_failure_rejected)
    }

    @Test
    fun theCcaIsNotCheckedForItemsWithAPersistedReason() {
        val asked = mutableListOf<Int>()
        val items = listOf(item(0, MediaItem.STATE_FAILED, uploadFailure = "REJECTED"), item(1, MediaItem.STATE_FAILED))
        outgoingMediaStatus(items, shared = true) { asked += it; false }
        assertThat(asked).containsExactly(1)
    }

    @Test
    fun permanentReasonsUseTheFixedCopy() {
        assertThat(permanent(OutgoingFailureReason.TOO_LARGE).statusRes).isEqualTo(R.string.media_failure_too_large)
        assertThat(permanent(OutgoingFailureReason.REJECTED).statusRes).isEqualTo(R.string.media_failure_rejected)
        assertThat(permanent(OutgoingFailureReason.FILE_MISSING).statusRes).isEqualTo(R.string.media_failure_file_missing)
        OutgoingFailureReason.entries.forEach {
            assertThat(permanent(it).showsFailureMark).isTrue()
            assertThat(permanent(it).listPreviewPrefixRes).isNull()
        }
    }

    @Test
    fun unsharedFailedIsEncryptFailed() {
        val items = listOf(item(0, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = false, ccaExists = ccaAll)).isEqualTo(OutgoingMediaStatus.ENCRYPT_FAILED)
    }

    // ---- 优先级（iOS 裁决，两端一致）----

    @Test
    fun failedBeatsUploading() {
        val items = listOf(item(0, MediaItem.STATE_UPLOADING), item(1, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = ccaAll))
            .isEqualTo(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER)
    }

    @Test
    fun permanentOnlyWhenEveryFailedItemLacksItsCca() {
        val items = listOf(item(0, MediaItem.STATE_FAILED), item(1, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = true, ccaExists = { it == 1 }))
            .isEqualTo(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER)
    }

    @Test
    fun ccaIsOnlyAskedForFailedItems() {
        val asked = mutableListOf<Int>()
        val items = listOf(item(0, MediaItem.STATE_SEALED), item(1, MediaItem.STATE_UPLOADING), item(2, MediaItem.STATE_FAILED))
        outgoingMediaStatus(items, shared = true) { asked += it; true }
        assertThat(asked).containsExactly(2)
    }

    @Test
    fun unsharedWithSomethingInFlightIsStillEncrypting() {
        val items = listOf(item(0, MediaItem.STATE_UPLOADING), item(1, MediaItem.STATE_FAILED))
        assertThat(outgoingMediaStatus(items, shared = false, ccaExists = ccaAll)).isEqualTo(OutgoingMediaStatus.ENCRYPTING)
    }

    @Test
    fun unsharedWithNothingInFlightIsEncryptFailed() {
        // 旧数据：项全 sealed 但封帧没成功（share_text 空）——也要能点红「!」重走 seal。
        val items = listOf(item(0, MediaItem.STATE_SEALED))
        assertThat(outgoingMediaStatus(items, shared = false, ccaExists = ccaAll)).isEqualTo(OutgoingMediaStatus.ENCRYPT_FAILED)
    }

    @Test
    fun onlyNotVisibleToPeerGetsTheListPrefix() {
        assertThat(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER.listPreviewPrefixRes).isEqualTo(R.string.media_unsent_prefix)
        allStatuses.filter { it != OutgoingMediaStatus.NOT_VISIBLE_TO_PEER }.forEach {
            assertThat(it.listPreviewPrefixRes).isNull()
        }
    }

    private val allStatuses: List<OutgoingMediaStatus> = listOf(
        OutgoingMediaStatus.ENCRYPTING, OutgoingMediaStatus.UPLOADING, OutgoingMediaStatus.UPLOADED,
        OutgoingMediaStatus.NOT_VISIBLE_TO_PEER, OutgoingMediaStatus.ENCRYPT_FAILED,
    ) + OutgoingFailureReason.entries.map { permanent(it) }

    @Test
    fun statusLinesUseTheFixedCopy() {
        assertThat(OutgoingMediaStatus.UPLOADING.statusRes).isEqualTo(R.string.media_status_uploading)
        assertThat(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER.statusRes).isEqualTo(R.string.media_status_not_visible)
        assertThat(OutgoingMediaStatus.ENCRYPTING.statusRes).isEqualTo(R.string.media_status_encrypting)
        assertThat(OutgoingMediaStatus.ENCRYPT_FAILED.statusRes).isEqualTo(R.string.media_status_encrypt_failed)
        assertThat(OutgoingMediaStatus.UPLOADED.statusRes).isNull() // the handoff status line takes over
    }

    @Test
    fun theRedMarkShowsForEveryFailure() {
        assertThat(allStatuses.filter { it.showsFailureMark }).containsExactly(
            OutgoingMediaStatus.NOT_VISIBLE_TO_PEER,
            OutgoingMediaStatus.ENCRYPT_FAILED,
            permanent(OutgoingFailureReason.TOO_LARGE),
            permanent(OutgoingFailureReason.REJECTED),
            permanent(OutgoingFailureReason.FILE_MISSING),
        )
    }
}
