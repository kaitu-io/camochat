package app.chencang.shared.media

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 先分享、后上传 spec §2 接收端轮询（虚拟时间）。与 iOS `AwaitingPollerTests` 一一对应。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitingPollerTest {
    /** 每项被请求的时刻（秒，虚拟时间）。 */
    private val log = mutableMapOf<String, MutableList<Long>>()
    private fun times(id: String): List<Long> = log[id].orEmpty()

    private fun TestScope.poller(
        states: MutableMap<String, String> = mutableMapOf(),
        onFetch: suspend (String) -> Unit = {},
    ) = AwaitingPoller(
        clock = { testScheduler.currentTime },
        scope = backgroundScope,
        tryFetch = { id, _ ->
            log.getOrPut(id) { mutableListOf() } += testScheduler.currentTime / 1_000
            onFetch(id)
        },
        isAwaiting = { id, _ -> AwaitingPoller.keepsPolling(states[id] ?: MediaItem.STATE_AWAITING) },
    )

    /** 推进到绝对时刻 [sec] 秒并跑完到期的任务。 */
    private fun TestScope.at(sec: Long) {
        advanceTimeBy(sec * 1_000 - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun `constants match the spec`() {
        assertThat(AwaitingPoller.DELAYS_MS).containsExactly(10_000L, 20_000L, 30_000L, 60_000L).inOrder()
        assertThat(AwaitingPoller.WINDOW_MS).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `classifyGone is awaiting until exactly 24h after receipt`() {
        val received = 5_000L
        assertThat(classifyGone(received, received + 23 * 3_600_000L + 59 * 60_000L)).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(classifyGone(received, received + 86_399_999L)).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(classifyGone(received, received + 86_400_000L)).isEqualTo(MediaItem.STATE_EXPIRED)
    }

    @Test
    fun `onVisible fetches now, then at 10, 30, 60, 120, 180 s`() = runTest {
        val p = poller()
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        at(200)
        assertThat(times("m1")).containsExactly(0L, 10L, 30L, 60L, 120L, 180L).inOrder()
    }

    @Test
    fun `after the 30-minute window it stops and reports windowExpired`() = runTest {
        val p = poller()
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        at(1_799)
        assertThat(p.windowExpired("m1", 0)).isFalse()
        val before = times("m1").size
        at(1_800)
        assertThat(p.windowExpired("m1", 0)).isTrue()
        assertThat(p.expired.value).contains(AwaitingPoller.Key("m1", 0))
        at(4_000)
        assertThat(times("m1").size).isEqualTo(before)
        assertThat(times("m1").last()).isLessThan(1_800L)
    }

    @Test
    fun `onHidden stops all polling`() = runTest {
        val p = poller()
        p.onVisible(listOf("m1" to 0, "m2" to 0))
        runCurrent()
        at(15)
        p.onHidden()
        at(600)
        assertThat(times("m1")).containsExactly(0L, 10L).inOrder()
        assertThat(times("m2")).containsExactly(0L, 10L).inOrder()
    }

    @Test
    fun `onVisible again fetches immediately and resets the window`() = runTest {
        val p = poller()
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        at(1_800)
        assertThat(p.windowExpired("m1", 0)).isTrue()
        at(2_000)
        p.onVisible(listOf("m1" to 0)) // 点「还没收到文件 · 点击重试」/ 回前台
        runCurrent()
        assertThat(p.windowExpired("m1", 0)).isFalse()
        assertThat(times("m1").last()).isEqualTo(2_000L)
        at(2_010)
        assertThat(times("m1").last()).isEqualTo(2_010L)
        at(2_000 + 1_799)
        assertThat(p.windowExpired("m1", 0)).isFalse()
        at(2_000 + 1_800)
        assertThat(p.windowExpired("m1", 0)).isTrue()
    }

    @Test
    fun `retrying one item leaves the others' schedules alone`() = runTest {
        val p = poller()
        p.onVisible(listOf("m1" to 0, "m2" to 0))
        runCurrent()
        at(5)
        p.onVisible(listOf("m2" to 0))
        runCurrent()
        at(31)
        assertThat(times("m1")).containsExactly(0L, 10L, 30L).inOrder()
        assertThat(times("m2")).containsExactly(0L, 5L, 15L).inOrder()
    }

    @Test
    fun `an item that is no longer awaiting stops polling`() = runTest {
        val states = mutableMapOf("m1" to MediaItem.STATE_AWAITING)
        val p = poller(states) { states["m1"] = MediaItem.STATE_READY }
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        at(600)
        assertThat(times("m1")).containsExactly(0L)
        assertThat(p.windowExpired("m1", 0)).isFalse()
    }

    @Test
    fun `track starts the schedule at 10 s without an immediate fetch and ignores running items`() = runTest {
        val p = poller()
        p.onVisible(emptyList())
        at(3)
        p.track(listOf("m1" to 0))
        runCurrent()
        assertThat(times("m1")).isEmpty()
        at(20)
        p.track(listOf("m1" to 0)) // 已在轮询：不重开
        at(40)
        assertThat(times("m1")).containsExactly(13L, 33L).inOrder()
    }

    @Test
    fun `track is ignored while the thread is not visible`() = runTest {
        val p = poller()
        p.track(listOf("m1" to 0))
        at(600)
        assertThat(times("m1")).isEmpty()
    }

    @Test
    fun `a video only polls once tapped, and is retried on re-entry`() = runTest {
        fun msg(id: String, dir: String) = ChatMessage(id, "alice", dir, "", 0L, kind = ChatMessage.KIND_IMAGE)
        fun item(id: String, kind: Int, state: String) = MediaItem(
            messageId = id, index = 0, kind = kind, durMs = 1_000, width = 1, height = 1, byteLen = 10,
            blobSecret = ByteArray(0), blobId = "b", state = state,
        )
        val messages = listOf(
            msg("m1", ChatMessage.DIRECTION_IN),
            msg("vid", ChatMessage.DIRECTION_IN),
            msg("m2", ChatMessage.DIRECTION_IN),
            msg("out", ChatMessage.DIRECTION_OUT),
        )
        val items = mutableListOf(
            item("m1", MediaConstants.KIND_VOICE, MediaItem.STATE_AWAITING),
            item("vid", MediaConstants.KIND_VIDEO, MediaItem.STATE_PENDING),
            item("m2", MediaConstants.KIND_IMAGE, MediaItem.STATE_READY),
            item("out", MediaConstants.KIND_IMAGE, MediaItem.STATE_UPLOADING),
        )
        assertThat(AwaitingPoller.targets(messages, items)).containsExactly("m1" to 0)

        val p = poller()
        p.onVisible(AwaitingPoller.targets(messages, items))
        runCurrent()
        at(60)
        assertThat(times("vid")).isEmpty()

        // 用户点了视频：下载得到 403 → awaiting，随后进入轮询。
        items[1] = item("vid", MediaConstants.KIND_VIDEO, MediaItem.STATE_AWAITING)
        p.track(AwaitingPoller.targets(messages, items))
        at(90)
        assertThat(times("vid")).containsExactly(70L, 90L).inOrder()
        assertThat(times("m1")).containsExactly(0L, 10L, 30L, 60L).inOrder()

        // 离开后再进会话：等待中的视频（一定是点过的）同样立即再试。
        p.onHidden()
        p.onVisible(AwaitingPoller.targets(messages, items))
        runCurrent()
        assertThat(times("vid")).containsExactly(70L, 90L, 90L).inOrder()
    }

    @Test
    fun `targets include incoming downloading items so a hidden-then-shown download rejoins polling`() {
        val m = ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "", 0L, kind = ChatMessage.KIND_IMAGE)
        val i = MediaItem(
            messageId = "m1", index = 0, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 1, height = 1,
            byteLen = 10, blobSecret = ByteArray(0), blobId = "b", state = MediaItem.STATE_DOWNLOADING,
        )
        assertThat(AwaitingPoller.targets(listOf(m), listOf(i))).containsExactly("m1" to 0)
        assertThat(AwaitingPoller.keepsPolling(MediaItem.STATE_AWAITING)).isTrue()
        assertThat(AwaitingPoller.keepsPolling(MediaItem.STATE_DOWNLOADING)).isTrue()
        assertThat(AwaitingPoller.keepsPolling(MediaItem.STATE_FAILED)).isFalse()
        assertThat(AwaitingPoller.keepsPolling(null)).isFalse()
    }

    @Test
    fun `a loop waking while the item is downloading keeps polling`() = runTest {
        val states = mutableMapOf("m1" to MediaItem.STATE_AWAITING)
        val p = poller(states)
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        states["m1"] = MediaItem.STATE_DOWNLOADING // 点重试那次刚切到下载中
        at(10)
        states["m1"] = MediaItem.STATE_AWAITING // 那次中途失败退回等待
        at(30)
        assertThat(times("m1")).containsExactly(0L, 10L, 30L).inOrder()
    }

    @Test
    fun `hiding the thread does not cancel a fetch already in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val p = poller { gate.await(); finished = true }
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        p.onHidden()
        gate.complete(Unit)
        runCurrent()
        assertThat(finished).isTrue()
        at(600)
        assertThat(times("m1")).containsExactly(0L)
    }

    // ---- UAT O4：30 分钟窗口必须按时收口 ----

    @Test
    fun `the window closes at 30 minutes even while a retry is still in flight`() = runTest {
        // 第二次再试起请求一直不回（弱网下连不上 / 排在下载并发闸后面）。
        val gate = CompletableDeferred<Unit>()
        val p = poller { if (times(it).size > 1) gate.await() }
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        at(1_799)
        assertThat(p.windowExpired("m1", 0)).isFalse()
        at(1_800)
        assertThat(p.windowExpired("m1", 0)).isTrue() // 在途的那次还没回，窗口照样收口
        assertThat(times("m1")).containsExactly(0L, 10L).inOrder()

        // 那次请求很晚才回（仍是 403）：不再开新的一轮，气泡保持「还没收到文件 · 点击重试」。
        gate.complete(Unit)
        runCurrent()
        at(4_000)
        assertThat(times("m1")).containsExactly(0L, 10L).inOrder()
        assertThat(p.windowExpired("m1", 0)).isTrue()
    }

    @Test
    fun `a clock that jumps backwards does not stretch the window`() = runTest {
        var offset = 0L
        val p = AwaitingPoller(
            clock = { testScheduler.currentTime - offset },
            scope = backgroundScope,
            tryFetch = { id, _ -> log.getOrPut(id) { mutableListOf() } += testScheduler.currentTime / 1_000 },
        )
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        at(100)
        offset = 10 * 60_000L // 系统时间被往回拨了 10 分钟（自动校时 / 用户改时间）
        at(1_800)
        assertThat(p.windowExpired("m1", 0)).isTrue()
        val before = times("m1").size
        at(3_000)
        assertThat(times("m1").size).isEqualTo(before)
    }

    @Test
    fun `repeated track calls and state flapping while visible never reopen the window`() = runTest {
        val states = mutableMapOf("m1" to MediaItem.STATE_AWAITING)
        val p = poller(states)
        p.onVisible(listOf("m1" to 0))
        runCurrent()
        // 线程一直可见：库里状态来回变（等待 ↔ 下载中）、收集器反复重发同一批等待项……
        var t = 5L
        while (t < 1_800) {
            states["m1"] = if ((t / 5) % 2 == 0L) MediaItem.STATE_DOWNLOADING else MediaItem.STATE_AWAITING
            p.track(listOf("m1" to 0))
            at(t)
            t += 5
        }
        states["m1"] = MediaItem.STATE_AWAITING
        at(1_800)
        assertThat(p.windowExpired("m1", 0)).isTrue()
        val before = times("m1").size
        repeat(3) { p.track(listOf("m1" to 0)) } // 窗口过后的重发也不重开
        at(3_600)
        assertThat(times("m1").size).isEqualTo(before)
        assertThat(p.windowExpired("m1", 0)).isTrue()
    }
}
