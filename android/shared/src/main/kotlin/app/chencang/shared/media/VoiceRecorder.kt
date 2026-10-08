package app.chencang.shared.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

/** 真正的「录 + 编码」过程；抽成接口是为了让 [VoiceRecorder] 的开始/结束/取消簿记能在 JVM 上测。 */
fun interface VoiceEngine {
    fun record(out: File, shouldStop: () -> Boolean, onAmplitude: (Float) -> Unit): PreparedMedia?
}

/**
 * 按住说话的录音（spec §3.2，裁决 R8）。录音跑在独立线程；[amplitude] 给浮层画波形。
 * 簿记规则：同一时间只有一段录音；[finish] 对同一段只交出一次结果；上一段的线程
 * 没收尾时 [start] 返回 false（按钮这次不进入录音状态），绝不让两段录音交叉。
 */
class VoiceRecorder(
    private val outDir: File,
    private val engine: VoiceEngine = OpusOggEngine,
) {

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var discard = false
    private var worker: Thread? = null
    private var result: CompletableDeferred<PreparedMedia?>? = null

    /** 调用方必须已确认 RECORD_AUDIO 已授权。返回 false 时不要进入录音状态。 */
    fun start(): Boolean {
        if (result != null) return false // 已在录
        // 不阻塞调用方线程（通常是 UI 线程）等上一段收尾——上一段的线程自己会跑完；
        // 这次只是拒绝进入录音状态，用户可以立刻再按一次。
        if (worker?.isAlive == true) return false
        stopRequested = false
        discard = false
        val done = CompletableDeferred<PreparedMedia?>()
        result = done
        outDir.mkdirs()
        val out = File(outDir, "${UUID.randomUUID()}.ogg")
        worker = thread(name = "cc-voice-recorder") {
            val prepared = try {
                engine.record(out, { stopRequested }, { _amplitude.value = it })
            } catch (e: Exception) {
                null
            }
            _amplitude.value = 0f
            if (discard || prepared == null) {
                out.delete()
                done.complete(null)
            } else {
                done.complete(prepared)
            }
        }
        return true
    }

    /** 停止并拿到 Ogg 文件；录音失败（麦克风被占用等）或已交出过/已取消，返回 null。 */
    suspend fun finish(): PreparedMedia? {
        val done = result ?: return null
        result = null // 先摘下：第二次 finish（或与 cancel 交错）拿不到同一段
        stopRequested = true
        return done.await()
    }

    /** 放弃这段录音（太短 / 上滑取消 / 离开页面），文件由录音线程删除。 */
    fun cancel() {
        discard = true
        stopRequested = true
        result = null
    }
}

/**
 * `AudioRecord` 16-bit PCM 48 kHz 单声道 → `MediaCodec`（`audio/opus`，24 kbps）→
 * `MediaMuxer`（OGG）。三者都要 API 29+（minSdk 已是 29）。满 60 s 在这里停。
 */
object OpusOggEngine : VoiceEngine {
    private const val SAMPLE_RATE = 48_000
    private const val BIT_RATE = 24_000
    private const val FRAME_BYTES = 1_920 // 20 ms @ 48 kHz mono 16-bit
    private const val MAX_SAMPLES = 48_000L * MediaConstants.MAX_VOICE_MS / 1000
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_SPINS = 100

    @SuppressLint("MissingPermission")
    override fun record(out: File, shouldStop: () -> Boolean, onAmplitude: (Float) -> Unit): PreparedMedia? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 8),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        // codec/muxer 的创建搬进 try：configure()/MediaMuxer(...) 任何一步炸了，
        // finally 都要能把已经建好的那些释放掉，不漏 AudioRecord 也不漏 codec。
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val mux = MuxState()
        val pcm = ByteArray(FRAME_BYTES)
        var samples = 0L
        var pipelineFailed = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = mx
            enc.start()
            rec.startRecording()
            while (!shouldStop() && samples < MAX_SAMPLES) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) break
                if (n == 0) continue
                onAmplitude(peak(pcm, n))
                queue(enc, pcm, n, ptsUs(samples))
                samples += n / 2
                drain(enc, mx, mux, eos = false)
            }
            if (queueEndOfStream(enc, mx, mux, ptsUs(samples))) {
                drain(enc, mx, mux, eos = true)
            } else {
                pipelineFailed = true // 编码器迟迟腾不出输入 buffer 收 EOS：放弃这段录音
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            if (mux.started) {
                muxer?.let { m -> if (runCatching { m.stop() }.isFailure) pipelineFailed = true }
            }
            muxer?.let { m -> runCatching { m.release() } }
        }
        if (!mux.started || !finalizeResult(samples, pipelineFailed)) {
            out.delete()
            return null
        }
        val durMs = samples * 1000 / SAMPLE_RATE
        return PreparedMedia(MediaConstants.KIND_VOICE, out, MediaLimits.clampVoiceDurationMs(durMs), 0, 0)
    }

    /**
     * 录完了要不要交出这段录音：麦克风一响就被抢（[samples] 一帧都没进）或者
     * 编码收尾（EOS 排队 / `muxer.stop()`）失败了，都算失败，不能把空/断尾的文件发出去。
     * 抽成纯函数是因为真引擎的 `record` 用不了 JVM 单测——这条判断可以。
     */
    internal fun finalizeResult(samples: Long, stopFailed: Boolean): Boolean = samples > 0 && !stopFailed

    private fun queue(codec: MediaCodec, pcm: ByteArray, n: Int, ptsUs: Long) {
        val idx = codec.dequeueInputBuffer(TIMEOUT_US)
        if (idx < 0) return // 编码器一时没空位：丢这 20 ms，不阻塞录音
        val buf = requireNotNull(codec.getInputBuffer(idx))
        buf.clear()
        buf.put(pcm, 0, n)
        codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
    }

    /** EOS 一定要真正排进去（丢了 muxer 就永远 stop 不了），但绝不无限等——排不进就多腾几次输出队列，
     * 还不行就放弃整段录音，而不是让 `dequeueInputBuffer(-1)` 卡死录音线程。 */
    private fun queueEndOfStream(codec: MediaCodec, muxer: MediaMuxer, mux: MuxState, ptsUs: Long): Boolean {
        repeat(MAX_IDLE_SPINS) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) {
                val buf = requireNotNull(codec.getInputBuffer(idx))
                buf.clear()
                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return true
            }
            drain(codec, muxer, mux, eos = false) // 腾输出队列，给编码器让出输入 buffer 的机会
        }
        return false
    }

    private class MuxState {
        var track = -1
        var started = false
    }

    private fun drain(codec: MediaCodec, muxer: MediaMuxer, mux: MuxState, eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idleSpins = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) TIMEOUT_US else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos || ++idleSpins > MAX_IDLE_SPINS) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    mux.track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    mux.started = true
                }
                idx >= 0 -> {
                    val buf = requireNotNull(codec.getOutputBuffer(idx))
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0 && mux.started) muxer.writeSampleData(mux.track, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun peak(pcm: ByteArray, n: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            max = maxOf(max, abs(s.toShort().toInt()))
            i += 2
        }
        return max / 32768f
    }

    private fun ptsUs(samples: Long): Long = samples * 1_000_000L / SAMPLE_RATE
}
