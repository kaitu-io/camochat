package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `OpusOggEngine.record` itself needs a real `AudioRecord`/`MediaCodec`/`MediaMuxer` and can't run
 * on the JVM — but the pure "should this recording be handed out" decision is extracted into
 * [OpusOggEngine.finalizeResult] precisely so it can be unit tested here.
 */
class OpusOggEngineTest {

    @Test
    fun `zero samples is a failure even if the muxer stopped cleanly`() {
        assertThat(OpusOggEngine.finalizeResult(samples = 0, stopFailed = false)).isFalse()
    }

    @Test
    fun `a muxer stop failure is a failure even if samples were captured`() {
        assertThat(OpusOggEngine.finalizeResult(samples = 48_000, stopFailed = true)).isFalse()
    }

    @Test
    fun `samples captured and a clean stop succeeds`() {
        assertThat(OpusOggEngine.finalizeResult(samples = 48_000, stopFailed = false)).isTrue()
    }
}
