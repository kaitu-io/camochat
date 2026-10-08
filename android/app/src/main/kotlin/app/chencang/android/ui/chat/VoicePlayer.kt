package app.chencang.android.ui.chat

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 语音播放：`MediaPlayer` 直接放 `.bin`（Ogg Opus），走系统默认音频路由。同一时间只放一条。 */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)

    /** 正在播放的线程行 key；null = 没在放。 */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    fun toggle(key: String, path: String) {
        if (_playing.value == key) {
            stop()
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
            player = mp
            _playing.value = key
        } catch (e: Exception) {
            // setDataSource/prepare/start can throw IOException, IllegalStateException,
            // IllegalArgumentException or SecurityException depending on the file/device —
            // any of them must not crash a tap. `path` is a local cache filename, not a
            // secret, so it's fine in the log; the audio bytes never are.
            Log.w(TAG, "voice playback failed for $path", e)
            mp.release()
        }
    }

    fun stop() {
        val p = player ?: run {
            _playing.value = null
            return
        }
        player = null
        runCatching { p.stop() }
        p.release()
        _playing.value = null
    }

    fun release() = stop()

    private companion object {
        const val TAG = "VoicePlayer"
    }
}
