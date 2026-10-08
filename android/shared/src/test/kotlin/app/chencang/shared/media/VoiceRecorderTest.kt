package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 假引擎：写一个字节、报一次振幅、等到被叫停；[holdAfterStop] 模拟线程迟迟收不了尾；
 * [fails] 模拟真引擎失败（麦克风被占用 / muxer 收尾失败等）——返回 null，而不是抛异常。
 */
private class FakeEngine(private val holdAfterStop: Boolean = false, private val fails: Boolean = false) : VoiceEngine {
    val release = CountDownLatch(1)
    override fun record(out: File, shouldStop: () -> Boolean, onAmplitude: (Float) -> Unit): PreparedMedia? {
        out.writeBytes(byteArrayOf(1))
        onAmplitude(0.5f)
        while (!shouldStop()) Thread.sleep(5)
        if (holdAfterStop) release.await()
        if (fails) return null
        return PreparedMedia(MediaConstants.KIND_VOICE, out, 1_500, 0, 0)
    }
}

class VoiceRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `finish hands the recording out once and null the second time`() = runBlocking {
        val r = VoiceRecorder(tmp.root, FakeEngine())
        assertThat(r.start()).isTrue()
        val first = r.finish()
        val second = r.finish()
        assertThat(first).isNotNull()
        assertThat(first!!.file.exists()).isTrue()
        assertThat(second).isNull()
    }

    @Test
    fun `start is refused while the previous recording thread is still finishing`() {
        val engine = FakeEngine(holdAfterStop = true)
        val r = VoiceRecorder(tmp.root, engine)
        assertThat(r.start()).isTrue()
        r.cancel()
        assertThat(r.start()).isFalse()
        engine.release.countDown()
    }

    @Test
    fun `a new recording can start once the previous one has finished`() = runBlocking {
        val r = VoiceRecorder(tmp.root, FakeEngine())
        assertThat(r.start()).isTrue()
        r.finish()
        assertThat(r.start()).isTrue()
        assertThat(r.finish()).isNotNull()
    }

    @Test
    fun `engine failure (mic busy, muxer stop failure, etc) yields null and deletes the partial file`() = runBlocking {
        val dir = File(tmp.root, "fail-voice")
        val r = VoiceRecorder(dir, FakeEngine(fails = true))
        assertThat(r.start()).isTrue()
        assertThat(r.finish()).isNull()
        assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `cancel discards the file and a later finish returns null`() = runBlocking {
        val r = VoiceRecorder(File(tmp.root, "voice"), FakeEngine())
        assertThat(r.start()).isTrue()
        r.cancel()
        assertThat(r.finish()).isNull()
        repeat(100) {
            if (File(tmp.root, "voice").listFiles().isNullOrEmpty()) return@runBlocking
            Thread.sleep(10)
        }
        assertThat(File(tmp.root, "voice").listFiles()!!.toList()).isEmpty()
    }
}
