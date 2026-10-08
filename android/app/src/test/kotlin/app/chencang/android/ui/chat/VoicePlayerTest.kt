package app.chencang.android.ui.chat

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * `ShadowMediaPlayer` lets these drive the *real* [VoicePlayer] end to end (register a fake
 * `MediaInfo`/exception for a data-source path, then call `toggle` for real) — no fake/interface
 * needed for [VoicePlayer] itself; see [ConversationViewModelTest] for the same technique used
 * to test [ConversationViewModel.delete] stopping playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VoicePlayerTest {

    @After
    fun tearDown() {
        ShadowMediaPlayer.resetStaticState()
    }

    @Test
    fun `toggle starts playback and reports the key as playing`() {
        val path = "/tmp/voice-a.bin"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(1_000, 0))
        val player = VoicePlayer()

        player.toggle("v1:0", path)

        assertThat(player.playing.value).isEqualTo("v1:0")
        player.release()
    }

    @Test
    fun `toggling the same key again stops it`() {
        val path = "/tmp/voice-b.bin"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(1_000, 0))
        val player = VoicePlayer()
        player.toggle("v1:0", path)

        player.toggle("v1:0", path)

        assertThat(player.playing.value).isNull()
    }

    @Test
    fun `a setDataSource failure other than IOException or IllegalStateException does not crash`() {
        val path = "/tmp/voice-c.bin"
        val dataSource = DataSource.toDataSource(path)
        ShadowMediaPlayer.addMediaInfo(dataSource, ShadowMediaPlayer.MediaInfo(1_000, 0))
        ShadowMediaPlayer.addException(dataSource, IllegalArgumentException("bad source"))
        val player = VoicePlayer()

        player.toggle("v1:0", path) // must not throw

        assertThat(player.playing.value).isNull()
    }
}
