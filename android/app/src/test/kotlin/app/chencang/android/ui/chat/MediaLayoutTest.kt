package app.chencang.android.ui.chat

import app.chencang.shared.R
import app.chencang.shared.chat.Handoff
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.media.OutgoingFailureReason
import app.chencang.shared.media.OutgoingMediaStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaLayoutTest {

    @Test
    fun `landscape image fills the max width and keeps its ratio`() {
        val s = MediaLayout.thumbSize(1920, 1080)
        assertThat(s.width.value).isWithin(0.01f).of(200f)
        assertThat(s.height.value).isWithin(0.01f).of(112.5f)
    }

    @Test
    fun `portrait image fills the max height`() {
        val s = MediaLayout.thumbSize(1080, 1920)
        assertThat(s.width.value).isWithin(0.01f).of(112.5f)
        assertThat(s.height.value).isWithin(0.01f).of(200f)
    }

    @Test
    fun `very thin image is clamped to the min thumb size`() {
        val s = MediaLayout.thumbSize(100, 2000)
        assertThat(s.width.value).isWithin(0.01f).of(72f)
        assertThat(s.height.value).isWithin(0.01f).of(200f)
    }

    @Test
    fun `unknown size falls back to a square placeholder`() {
        val s = MediaLayout.thumbSize(0, 0)
        assertThat(s.width.value).isWithin(0.01f).of(200f)
        assertThat(s.height.value).isWithin(0.01f).of(200f)
    }

    @Test
    fun `voice bubble grows linearly from 1 s to 60 s`() {
        assertThat(MediaLayout.voiceWidth(500).value).isWithin(0.01f).of(72f)
        assertThat(MediaLayout.voiceWidth(1_000).value).isWithin(0.01f).of(72f)
        assertThat(MediaLayout.voiceWidth(30_500).value).isWithin(0.01f).of(156f)
        assertThat(MediaLayout.voiceWidth(60_000).value).isWithin(0.01f).of(240f)
    }

    @Test
    fun `duration labels`() {
        assertThat(MediaLayout.voiceLabel(12_345)).isEqualTo("12″")
        assertThat(MediaLayout.voiceLabel(999)).isEqualTo("1″")
        assertThat(MediaLayout.videoLabel(9_000)).isEqualTo("0:09")
        assertThat(MediaLayout.videoLabel(59_600)).isEqualTo("1:00")
    }

    // ---- 先分享、后上传 spec §2 接收端文案 ----

    @Test
    fun `awaiting shows the waiting copy with a spinner, then the stalled copy without one`() {
        assertThat(MediaLayout.incomingStateRes(MediaItem.STATE_AWAITING, windowExpired = false)).isEqualTo(R.string.media_awaiting)
        assertThat(MediaLayout.showsAwaitingSpinner(MediaItem.STATE_AWAITING, windowExpired = false)).isTrue()
        assertThat(MediaLayout.incomingStateRes(MediaItem.STATE_AWAITING, windowExpired = true))
            .isEqualTo(R.string.media_awaiting_stalled)
        assertThat(MediaLayout.showsAwaitingSpinner(MediaItem.STATE_AWAITING, windowExpired = true)).isFalse()
    }

    @Test
    fun `expired and corrupt keep their copy, other states have none`() {
        assertThat(MediaLayout.incomingStateRes(MediaItem.STATE_EXPIRED, false)).isEqualTo(R.string.media_failure_expired)
        assertThat(MediaLayout.incomingStateRes(MediaItem.STATE_CORRUPT, false)).isEqualTo(R.string.media_failure_corrupt)
        assertThat(MediaLayout.incomingStateRes(MediaItem.STATE_READY, windowExpired = true)).isNull()
        assertThat(MediaLayout.incomingStateRes(MediaItem.STATE_DOWNLOADING, windowExpired = false)).isNull()
        assertThat(MediaLayout.showsAwaitingSpinner(MediaItem.STATE_DOWNLOADING, windowExpired = false)).isFalse()
    }

    // ---- 发出媒体状态行（终审 F2：永久失败是终态，busy 不再改写「对方还看不到」，与 iOS 同）----

    @Test
    fun `not visible to peer reads the same while busy as when idle, like iOS`() {
        val line = MediaLayout.outStatusLine(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER, Handoff.NOT_SENT, busy = true)
        assertThat(line).isEqualTo(MediaLayout.OutLine(R.string.media_status_not_visible, failed = true, reopenable = false))
    }

    @Test
    fun `a permanent failure shows its reason as a failure line`() {
        val cases = mapOf(
            OutgoingFailureReason.TOO_LARGE to R.string.media_failure_too_large,
            OutgoingFailureReason.REJECTED to R.string.media_failure_rejected,
            OutgoingFailureReason.FILE_MISSING to R.string.media_failure_file_missing,
        )
        for ((reason, res) in cases) {
            for (busy in listOf(false, true)) {
                val line = MediaLayout.outStatusLine(OutgoingMediaStatus.PermanentlyFailed(reason), Handoff.SHARED, busy = busy)
                assertThat(line).isEqualTo(MediaLayout.OutLine(res, failed = true, reopenable = false))
            }
        }
    }

    @Test
    fun `not visible to peer when idle is the tappable failure`() {
        val line = MediaLayout.outStatusLine(OutgoingMediaStatus.NOT_VISIBLE_TO_PEER, Handoff.NOT_SENT, busy = false)
        assertThat(line.textRes).isEqualTo(R.string.media_status_not_visible)
        assertThat(line.failed).isTrue()
    }

    @Test
    fun `encrypt failed while busy reads encrypting`() {
        assertThat(MediaLayout.outStatusLine(OutgoingMediaStatus.ENCRYPT_FAILED, Handoff.NOT_SENT, busy = true))
            .isEqualTo(MediaLayout.OutLine(R.string.media_status_encrypting, failed = false, reopenable = false))
        assertThat(MediaLayout.outStatusLine(OutgoingMediaStatus.ENCRYPT_FAILED, Handoff.NOT_SENT, busy = false))
            .isEqualTo(MediaLayout.OutLine(R.string.media_status_encrypt_failed, failed = true, reopenable = false))
    }

    @Test
    fun `uploaded shows what the user did with it`() {
        assertThat(MediaLayout.outStatusLine(OutgoingMediaStatus.UPLOADED, Handoff.NOT_SENT, busy = false))
            .isEqualTo(MediaLayout.OutLine(R.string.status_encrypted_not_sent, failed = false, reopenable = true))
        assertThat(MediaLayout.outStatusLine(OutgoingMediaStatus.UPLOADED, Handoff.COPIED, busy = false))
            .isEqualTo(MediaLayout.OutLine(R.string.status_copied, failed = false, reopenable = true))
        val shared = MediaLayout.outStatusLine(OutgoingMediaStatus.UPLOADED, Handoff.SHARED, busy = false)
        assertThat(shared.textRes).isEqualTo(R.string.status_shared)
        assertThat(shared.reopenable).isFalse()
    }
}
