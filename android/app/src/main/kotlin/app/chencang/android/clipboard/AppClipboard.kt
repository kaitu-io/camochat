package app.chencang.android.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import app.chencang.shared.AppPrefs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 应用内所有剪贴板写入的唯一入口，外加粘贴条用的只读探测。
 *
 * 除 [write] 外都只看 `primaryClipDescription`（MIME + 时间戳），不读剪贴板内容（隐私边界）。
 * [write] 写完立刻把新时间戳记为已消费，所以陈仓自己复制的东西回来不会弹粘贴条。
 * 系统分享面板里用户可能选「复制」（陈仓管不到）：拉起面板前 [markConsumedOnNextFocus]，
 * 回到前台、窗口重新有焦点时由粘贴条的焦点监听调 [consumePendingFocusMark] 把当时的剪贴板记为已消费；
 * 期间若经过 ON_STOP（离开去了别的 App）则由 [clearPendingFocusMark] 撤掉这个标记。
 * 每次 [write] 都发一次 [writes]，粘贴条据此立刻收起。
 */
object AppClipboard {
    @Volatile
    private var markOnNextFocus = false

    private val _writes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 陈仓自己写了剪贴板（写入成功后发出）。 */
    val writes: SharedFlow<Unit> = _writes.asSharedFlow()

    private fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** 写剪贴板；失败（系统服务异常）返回 false，调用方提示而不是装作复制成功。 */
    fun write(context: Context, label: String, text: String): Boolean = try {
        val cm = manager(context) ?: throw IllegalStateException("no clipboard service")
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        markConsumed(context, AppPrefs(context.applicationContext))
        _writes.tryEmit(Unit)
        true
    } catch (e: RuntimeException) {
        false
    }

    fun hasText(context: Context): Boolean = try {
        manager(context)?.primaryClipDescription?.hasMimeType("text/*") == true
    } catch (e: RuntimeException) {
        false
    }

    /** 非正时间戳 = 该设备不给可用时间戳：当作未知（粘贴条降级隐藏），免得永远误判成「新内容」。 */
    fun currentStamp(context: Context): Long? = try {
        manager(context)?.primaryClipDescription?.timestamp.let(::normalizeStamp)
    } catch (e: RuntimeException) {
        null
    }

    internal fun normalizeStamp(raw: Long?): Long? = raw?.takeIf { it > 0 }

    fun markConsumed(context: Context, prefs: AppPrefs) = markConsumed(currentStamp(context), prefs)

    /** 时间戳未知时不落盘（绝不存 0）。 */
    internal fun markConsumed(stamp: Long?, prefs: AppPrefs) {
        stamp?.let { prefs.setPasteBarConsumedStamp(it) }
    }

    /** 拉起系统分享面板之前调用。 */
    fun markConsumedOnNextFocus() {
        markOnNextFocus = true
    }

    /**
     * 界面进入 ON_STOP 时调用：用户离开去了聊天软件（分享面板里选「复制」只会让我们 pause，不会 stop），
     * 回来时剪贴板里多半是对方发回的东西，不能再把它记成已消费。
     */
    fun clearPendingFocusMark() {
        markOnNextFocus = false
    }

    /** 窗口重新获得焦点时调用；有待办标记才记已消费并清标记。返回是否执行了标记。 */
    fun consumePendingFocusMark(context: Context, prefs: AppPrefs): Boolean {
        if (!markOnNextFocus) return false
        markOnNextFocus = false
        markConsumed(context, prefs)
        return true
    }
}
