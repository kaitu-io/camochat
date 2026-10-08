package app.chencang.shared.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「按住 说话」的纯状态机（spec §3.2）：< 1 s 太短；上滑超过阈值进取消区；
 * 50 s 起倒计时；60 s 自动发送，之后的抬手被忽略。时间全部由调用方传入，便于测试。
 */
class HoldToTalk(private val cancelThresholdPx: Float) {

    sealed interface Phase {
        data object Idle : Phase
        data class Recording(val elapsedMs: Long, val inCancelZone: Boolean, val countdownSec: Int?) : Phase
    }

    sealed interface Outcome {
        data class Send(val durMs: Long) : Outcome
        data object TooShort : Outcome
        data object Cancelled : Outcome
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var startMs = 0L
    private var cancelZone = false

    fun press(nowMs: Long) {
        startMs = nowMs
        cancelZone = false
        _phase.value = Phase.Recording(0, false, null)
    }

    /** [dyPx]：手指相对按下点的纵向位移，向上为负。 */
    fun move(dyPx: Float, nowMs: Long) {
        if (_phase.value !is Phase.Recording) return
        cancelZone = dyPx <= -cancelThresholdPx
        publish(nowMs)
    }

    fun tick(nowMs: Long): Outcome? {
        if (_phase.value !is Phase.Recording) return null
        val elapsed = nowMs - startMs
        if (elapsed >= MediaConstants.MAX_VOICE_MS) {
            _phase.value = Phase.Idle
            return Outcome.Send(MediaConstants.MAX_VOICE_MS.toLong())
        }
        publish(nowMs)
        return null
    }

    fun release(nowMs: Long): Outcome? {
        if (_phase.value !is Phase.Recording) return null
        val elapsed = nowMs - startMs
        _phase.value = Phase.Idle
        return when {
            cancelZone -> Outcome.Cancelled
            elapsed < MediaConstants.MIN_VOICE_MS -> Outcome.TooShort
            else -> Outcome.Send(elapsed.coerceAtMost(MediaConstants.MAX_VOICE_MS.toLong()))
        }
    }

    private fun publish(nowMs: Long) {
        val elapsed = nowMs - startMs
        val countdown = if (elapsed >= MediaConstants.VOICE_COUNTDOWN_FROM_MS) {
            ((MediaConstants.MAX_VOICE_MS - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
        } else {
            null
        }
        _phase.value = Phase.Recording(elapsed, cancelZone, countdown)
    }
}
