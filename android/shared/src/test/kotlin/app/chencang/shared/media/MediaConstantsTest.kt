package app.chencang.shared.media

import app.chencang.shared.R
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaConstantsTest {

    @Test
    fun `plaintext budgets are blob limits minus 50`() {
        assertThat(MediaConstants.maxBlob(MediaConstants.KIND_VOICE)).isEqualTo(2_097_152L)
        assertThat(MediaConstants.maxBlob(MediaConstants.KIND_IMAGE)).isEqualTo(2_097_152L)
        assertThat(MediaConstants.maxBlob(MediaConstants.KIND_VIDEO)).isEqualTo(31_457_280L)
        assertThat(MediaConstants.plainBudget(MediaConstants.KIND_VOICE)).isEqualTo(2_097_102L)
        assertThat(MediaConstants.plainBudget(MediaConstants.KIND_IMAGE)).isEqualTo(2_097_102L)
        assertThat(MediaConstants.plainBudget(MediaConstants.KIND_VIDEO)).isEqualTo(31_457_230L)
    }

    @Test
    fun `unknown kind is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MediaConstants.maxBlob(4) }
        assertThrows(IllegalArgumentException::class.java) { MediaConstants.maxBlob(0) }
    }

    @Test
    fun `expiry is exactly 24h after the timestamp, inclusive`() {
        val sent = 1_000L
        assertThat(MediaConstants.isExpired(sent, sent + 86_399_999L)).isFalse()
        assertThat(MediaConstants.isExpired(sent, sent + 86_400_000L)).isTrue()
        assertThat(MediaConstants.isExpired(sent, sent + 86_400_001L)).isTrue()
    }

    @Test
    fun `progress key joins message id and index`() {
        assertThat(MediaConstants.progressKey("m-1", 3)).isEqualTo("m-1:3")
    }

    @Test
    fun `failure messages point at their string resources`() {
        assertThat(MediaFailure.RATE_LIMITED.messageRes).isEqualTo(R.string.media_failure_rate_limited)
        assertThat(MediaFailure.GONE.messageRes).isEqualTo(R.string.media_failure_expired)
        assertThat(MediaFailure.CORRUPT.messageRes).isEqualTo(R.string.media_failure_corrupt)
        assertThat(MediaFailure.STORAGE_FULL.messageRes).isEqualTo(R.string.media_failure_storage_full)
        assertThat(MediaFailure.SESSION_LOST.messageRes).isEqualTo(R.string.media_failure_session_lost)
    }
}
