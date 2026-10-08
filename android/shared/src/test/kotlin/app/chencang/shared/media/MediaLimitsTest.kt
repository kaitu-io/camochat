package app.chencang.shared.media

import app.chencang.shared.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaLimitsTest {

    @Test
    fun `videos up to 60 whole seconds pass, 61 s and longer are stopped at selection`() {
        assertThat(MediaLimits.videoDurationError(1_000)).isNull()
        assertThat(MediaLimits.videoDurationError(60_000)).isNull()
        assertThat(MediaLimits.videoDurationError(61_000)).isEqualTo(R.string.media_video_too_long)
        assertThat(MediaLimits.videoDurationError(120_000)).isEqualTo(R.string.media_video_too_long)
    }

    @Test
    fun `camera clip of 60_040 ms is accepted and clamped to 60000`() {
        // 系统相机按 EXTRA_DURATION_LIMIT=60 录出来常常是 60.0x 秒；core 对 dur_ms > 60000 报错。
        assertThat(MediaLimits.videoDurationError(60_040)).isNull()
        assertThat(MediaLimits.clampDurationMs(60_040)).isEqualTo(60_000)
        assertThat(MediaLimits.clampDurationMs(-5)).isEqualTo(0)
    }

    @Test
    fun `transcoded video over the plaintext budget is too big`() {
        assertThat(MediaLimits.videoSizeError(31_457_230)).isNull()
        assertThat(MediaLimits.videoSizeError(31_457_231)).isEqualTo(R.string.media_video_too_big)
    }

    @Test
    fun `voice duration is clamped against the voice limit, independently of the video helper`() {
        assertThat(MediaLimits.clampVoiceDurationMs(60_000)).isEqualTo(60_000)
        assertThat(MediaLimits.clampVoiceDurationMs(60_040)).isEqualTo(60_000)
        assertThat(MediaLimits.clampVoiceDurationMs(-5)).isEqualTo(0)
        assertThat(MediaLimits.clampVoiceDurationMs(1_500)).isEqualTo(1_500)
    }
}
