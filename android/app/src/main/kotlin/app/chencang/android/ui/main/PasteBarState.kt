package app.chencang.android.ui.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.chencang.android.clipboard.AppClipboard
import app.chencang.shared.AppPrefs
import app.chencang.shared.intake.PasteBarGate

/** 粘贴条依赖的剪贴板探测；只看「有无文本 + 时间戳」，不读内容。测试里换假实现。 */
interface PasteBarProbe {
    /** 有待办的「分享面板后」标记就先记为已消费。 */
    fun consumePendingFocusMark()
    /** 进入 ON_STOP：撤掉「分享面板后」标记。 */
    fun clearPendingFocusMark()
    fun hasText(): Boolean
    fun stamp(): Long?
    fun consumedStamp(): Long?
    fun markConsumed()
}

class ClipboardPasteBarProbe(context: Context) : PasteBarProbe {
    private val appContext = context.applicationContext
    private val prefs = AppPrefs(appContext)
    override fun consumePendingFocusMark() {
        AppClipboard.consumePendingFocusMark(appContext, prefs)
    }
    override fun clearPendingFocusMark() = AppClipboard.clearPendingFocusMark()
    override fun hasText() = AppClipboard.hasText(appContext)
    override fun stamp() = AppClipboard.currentStamp(appContext)
    override fun consumedStamp() = prefs.pasteBarConsumedStamp
    override fun markConsumed() = AppClipboard.markConsumed(appContext, prefs)
}

/**
 * 粘贴条的可见性持有者（会话/联系人 tab 与对话页共享一个）。
 * 只在窗口有焦点时判定（Android 10+ 非焦点应用读不到剪贴板）：焦点变 true → 先处理
 * 「分享面板后」标记，再重算；ON_RESUME 只是兜底，同样要求有焦点；ON_STOP 撤掉该标记。
 * 点粘贴或 ✕ 先记已消费再收起；陈仓自己写剪贴板时立刻收起。
 */
@Stable
class PasteBarState(private val probe: PasteBarProbe) {
    var visible by mutableStateOf(false)
        private set

    fun onFocusGained() {
        probe.consumePendingFocusMark()
        recompute()
    }

    /** ON_STOP：离开去了别的 App，回来时剪贴板不再算作分享面板里复制的。 */
    fun onStopped() = probe.clearPendingFocusMark()

    /** 陈仓自己刚写了剪贴板（已记为已消费）：立刻收起。 */
    fun onAppWrote() {
        visible = false
    }

    private fun recompute() {
        visible = PasteBarGate.shouldShow(probe.hasText(), probe.stamp(), probe.consumedStamp())
    }

    /** 点「粘贴」或「✕」：同一个剪贴板内容之后不再提示。 */
    fun consume() {
        probe.markConsumed()
        visible = false
    }
}

/**
 * [focused] 默认取窗口焦点；测试可注入。焦点为 false 时绝不判定（保持上一次的结果）。
 */
@Composable
fun rememberPasteBarState(
    probe: PasteBarProbe = run {
        val context = LocalContext.current
        remember(context) { ClipboardPasteBarProbe(context) }
    },
    focused: Boolean = LocalWindowInfo.current.isWindowFocused,
): PasteBarState {
    val state = remember(probe) { PasteBarState(probe) }
    val focusedNow by rememberUpdatedState(focused)
    LaunchedEffect(focused) { if (focused) state.onFocusGained() }
    LaunchedEffect(state) { AppClipboard.writes.collect { state.onAppWrote() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (focusedNow) state.onFocusGained()
                Lifecycle.Event.ON_STOP -> state.onStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
