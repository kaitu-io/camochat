package app.chencang.shared.media

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 接收端「等待对方上传」的轮询（先分享、后上传 spec §2），与 iOS `AwaitingPoller` 同口径：
 * - 只在这条消息所在会话**可见**（线程屏 ON_RESUME 之后、ON_PAUSE 之前）时轮询；不可见一律停。
 * - [onVisible]：对给定项立即试一次并重开 30 分钟窗口（进会话 / 回前台 / 点「还没收到文件 · 点击重试」）。
 * - [track]：刚请求过、刚进入等待的项从现在起按间隔轮询，不立即再试。
 * - 间隔 10 s、20 s、30 s、60 s，之后恒 60 s；CloudFront 对错误响应缓存约 10 s，首轮不早于 10 s。
 * - 窗口过后停止自动轮询，[windowExpired] 为 true（气泡改「还没收到文件 · 点击重试」）。窗口由每项一个
 *   独立的截止计时（调度器时间，单调）兜底：在途的那次请求迟迟不回、[clock] 被往回拨，都不能把收口往后推（UAT O4）。
 *   在途的请求照常跑完（不打断下载），只是不再开下一轮。
 * - 每项独立（相册各张先到先显示）；项不再 [keepsPolling]（拿到了 / 已过期 / 失败）即停止这一项。
 * 24 h 过期判定不在这里：[tryFetch]（即 [MediaDownloader.download]）见到超过 24 h 直接判已过期、不发请求。
 *
 * 所有公开方法须在同一线程（主线程）上调用；[scope] 的调度器也须是它（生产 = `viewModelScope`）。
 */
class AwaitingPoller(
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val tryFetch: suspend (messageId: String, index: Int) -> Unit,
    /** 这一项当前是否仍该轮询；返回 false 就停止这一项。生产侧 = [keepsPolling] 当前状态。 */
    private val isAwaiting: suspend (messageId: String, index: Int) -> Boolean = { _, _ -> true },
) {
    data class Key(val messageId: String, val index: Int)

    private var visible = false

    /** 每项一个轮询循环 + 它的窗口截止计时；`gen` 防止被替换掉的旧循环收尾时误删新循环。 */
    private class Loop(val gen: Int, val job: Job, val deadline: Job) {
        fun cancel() {
            job.cancel()
            deadline.cancel()
        }
    }

    private val loops = mutableMapOf<Key, Loop>()
    private var generation = 0

    private val _expired = MutableStateFlow<Set<Key>>(emptySet())

    /** 窗口已过、停止自动轮询的项（界面据此选文案）。 */
    val expired: StateFlow<Set<Key>> = _expired.asStateFlow()

    /** 会话变为可见 / 用户点了重试：这些项立即试一次并重开窗口；其它正在轮询的项不受影响。 */
    fun onVisible(items: List<Pair<String, Int>>) {
        visible = true
        for ((messageId, index) in items) start(Key(messageId, index), fetchNow = true)
    }

    /** 刚进入等待的项：从现在起按间隔轮询（不立即再试）。已在轮询 / 窗口已过的项不动；不可见时忽略。 */
    fun track(items: List<Pair<String, Int>>) {
        if (!visible) return
        for ((messageId, index) in items) {
            val key = Key(messageId, index)
            if (loops.containsKey(key) || key in _expired.value) continue
            start(key, fetchNow = false)
        }
    }

    /** 会话不可见：全部停止。正在进行的那次请求会跑完（不取消下载），之后不再请求。 */
    fun onHidden() {
        visible = false
        loops.values.forEach { it.cancel() }
        loops.clear()
        _expired.value = emptySet()
    }

    /** 这一项的 30 分钟窗口已过（气泡显示「还没收到文件 · 点击重试」）。 */
    fun windowExpired(messageId: String, index: Int): Boolean = Key(messageId, index) in _expired.value

    private fun start(key: Key, fetchNow: Boolean) {
        loops.remove(key)?.cancel()
        _expired.update { it - key }
        val gen = ++generation
        val windowStart = clock()
        // 截止计时先于循环登记：同一时刻到期时它先收口，循环不会在 30 分钟整点再发一次请求。
        val deadline = scope.launch {
            delay(WINDOW_MS)
            expire(key, gen)
        }
        val job = scope.launch { run(key, gen, windowStart, fetchNow) }
        // 调度器是 immediate 时 run 可能已经结束并 finish 过：只在还活着时登记。
        if (job.isActive) loops[key] = Loop(gen, job, deadline) else deadline.cancel()
    }

    private suspend fun run(key: Key, gen: Int, windowStart: Long, fetchNow: Boolean) {
        if (fetchNow) {
            fetch(key)
            if (!isAwaiting(key.messageId, key.index)) return finish(key, gen)
        }
        var attempt = 0
        while (true) {
            val step = DELAYS_MS[minOf(attempt, DELAYS_MS.lastIndex)]
            attempt++
            val remaining = WINDOW_MS - (clock() - windowStart)
            delay(maxOf(0L, minOf(step, remaining)))
            if (clock() - windowStart >= WINDOW_MS) return expire(key, gen)
            if (!isAwaiting(key.messageId, key.index)) return finish(key, gen)
            fetch(key)
            if (!isAwaiting(key.messageId, key.index)) return finish(key, gen)
        }
    }

    /**
     * 请求放进不随循环取消的独立协程：会话此刻被隐藏也让这次下载正常跑完——取消会把下载打断，
     * 而用户只是离开了会话。循环自身在 `join` 处照常响应取消。
     */
    private suspend fun fetch(key: Key) {
        scope.launch {
            try {
                tryFetch(key.messageId, key.index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 一次再试的意外失败不能带崩宿主作用域；下一轮照常再试。
            }
        }.join()
    }

    /** 这一项不再等待（拿到了 / 已过期 / 失败）：收掉它的循环与截止计时。 */
    private fun finish(key: Key, gen: Int) {
        if (loops[key]?.gen == gen) loops.remove(key)?.deadline?.cancel()
    }

    /**
     * 窗口到了（截止计时或循环自己先看到）：停掉这一项的循环——在途的请求不受影响、跑完为止——
     * 并标「窗口已过」。只认当前这一轮，被替换 / 已收尾的旧一轮不动。
     */
    private fun expire(key: Key, gen: Int) {
        val loop = loops[key]?.takeIf { it.gen == gen } ?: return
        loops.remove(key)
        loop.cancel()
        _expired.update { it + key }
    }

    companion object {
        val DELAYS_MS = listOf(10_000L, 20_000L, 30_000L, 60_000L)
        const val WINDOW_MS = 30 * 60_000L

        /**
         * 生产侧 `isAwaiting` 的判定：`awaiting`，或轮询那一次已经开始收 blob 而切到的 `downloading`
         * （那次下载中途失败会回到 `awaiting`，循环不能在它下载期间被判停）。
         */
        fun keepsPolling(state: String?): Boolean =
            state == MediaItem.STATE_AWAITING || state == MediaItem.STATE_DOWNLOADING

        /**
         * 可见会话里应当轮询的项：收到的、处于 awaiting 或 downloading 的媒体项。
         * - 视频只有用户点了才会下载，因此处于 awaiting 的视频一定是点过的（spec §2）。
         * - 含 downloading：轮询那次已切到下载中时会话被隐藏又回来，这一项也要接回轮询——那次下载中途失败
         *   会退回 awaiting，没有循环就再也没人试。接回时的立即再试被下载器的在途去重吃掉，不会重复下载。
         */
        fun targets(messages: List<ChatMessage>, items: List<MediaItem>): List<Pair<String, Int>> {
            val incoming = messages.filter { it.direction == ChatMessage.DIRECTION_IN }.map { it.id }.toSet()
            return items
                .filter { it.messageId in incoming && keepsPolling(it.state) }
                .map { it.messageId to it.index }
        }
    }
}
