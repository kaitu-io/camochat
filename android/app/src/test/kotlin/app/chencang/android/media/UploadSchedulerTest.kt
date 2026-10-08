package app.chencang.android.media

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.chencang.android.ui.chat.FakeShareHeaders
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.inTransactionRunner
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.MediaFailure
import app.chencang.shared.media.UploadOutcome
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.media.MediaSender
import app.chencang.shared.media.MediaTransport
import app.chencang.shared.media.UniffiMediaCrypto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChatDatabase

    /** 测试里 Worker 的行为：由各测试设定（默认一直 Retryable）。 */
    @Volatile private var outcome: suspend () -> UploadOutcome = { UploadOutcome.Retryable(MediaFailure.NETWORK) }

    private val target = object : UploadTarget {
        override suspend fun upload(messageId: String) = outcome()
        override suspend fun markUploadFailed(messageId: String) = Unit
        override suspend fun pendingSince(messageId: String): Long = System.currentTimeMillis()
    }

    private fun initWorkManager(executor: Executor = SynchronousExecutor()) {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(executor)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                            UploadWorker(appContext, workerParameters, { target }, System::currentTimeMillis)
                    },
                )
                .build(),
        )
    }

    @Before
    fun setUp() {
        initWorkManager()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
    }

    /** 轮询到 [predicate] 成立（CoroutineWorker 在 Dispatchers.Default 上跑，不随 SynchronousExecutor 同步）。 */
    private fun awaitInfo(messageId: String, predicate: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val info = infos(messageId).singleOrNull()
            if (info != null && predicate(info)) return info
            check(System.currentTimeMillis() < deadline) { "timed out; last = $info" }
            Thread.sleep(20)
        }
    }

    private fun startRunning(messageId: String) {
        val id = infos(messageId).single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun infos(messageId: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("upload-$messageId").get()

    @Test
    fun enqueueTwiceKeepsOneWork() {
        UploadScheduler.enqueue(context, "m1")
        UploadScheduler.enqueue(context, "m1")

        val infos = infos("m1")
        assertThat(infos).hasSize(1)
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED) // 等网络约束，还没跑
        assertThat(infos.single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun theWorkCarriesOnlyTheMessageIdAndNoIdentifyingTags() {
        UploadScheduler.enqueue(context, "m1")

        val info = infos("m1").single()
        assertThat(info.tags).contains(UploadScheduler.TAG)
        // tag 里不带 messageId（唯一名里已有），更不带 blob 信息。
        assertThat(info.tags.none { "m1" in it }).isTrue()
    }

    @Test
    fun enqueueKeepsAWorkWaitingInBackoff() {
        UploadScheduler.enqueue(context, "m1")
        startRunning("m1")
        val backingOff = awaitInfo("m1") { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }

        UploadScheduler.enqueue(context, "m1")

        assertThat(infos("m1").single().id).isEqualTo(backingOff.id)
    }

    @Test
    fun enqueueNowReplacesAWorkWaitingInBackoff() {
        UploadScheduler.enqueue(context, "m1")
        startRunning("m1")
        val backingOff = awaitInfo("m1") { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }

        runBlocking { UploadScheduler.enqueueNow(context, "m1") }

        val fresh = infos("m1").single()
        assertThat(fresh.id).isNotEqualTo(backingOff.id)
        assertThat(fresh.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(fresh.runAttemptCount).isEqualTo(0) // 不再等退避：一有网就跑
    }

    @Test
    fun enqueueNowNeverReplacesARunningWork() {
        initWorkManager(Executors.newSingleThreadExecutor())
        val release = CountDownLatch(1)
        outcome = {
            withContext(Dispatchers.IO) { release.await(10, TimeUnit.SECONDS) }
            UploadOutcome.Done
        }
        UploadScheduler.enqueue(context, "m1")
        startRunning("m1")
        val running = awaitInfo("m1") { it.state == WorkInfo.State.RUNNING }

        runBlocking { UploadScheduler.enqueueNow(context, "m1") }

        assertThat(infos("m1").single().id).isEqualTo(running.id)
        assertThat(infos("m1").single().state).isEqualTo(WorkInfo.State.RUNNING)
        release.countDown()
        awaitInfo("m1") { it.state == WorkInfo.State.SUCCEEDED }
    }

    @Test
    fun enqueueNowStartsAFreshWorkAfterAFinishedOne() {
        outcome = { UploadOutcome.Done }
        UploadScheduler.enqueue(context, "m1")
        startRunning("m1")
        val done = awaitInfo("m1") { it.state == WorkInfo.State.SUCCEEDED }

        runBlocking { UploadScheduler.enqueueNow(context, "m1") }

        val fresh = infos("m1").single()
        assertThat(fresh.id).isNotEqualTo(done.id)
        assertThat(fresh.state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun cancelRemovesWork() {
        UploadScheduler.enqueue(context, "m1")
        UploadScheduler.enqueue(context, "m2")

        runBlocking { UploadScheduler.cancel(context, "m1") }

        assertThat(infos("m1").single().state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(infos("m2").single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun cancelAllCancelsEveryUpload() {
        UploadScheduler.enqueue(context, "m1")
        UploadScheduler.enqueue(context, "m2")

        runBlocking { UploadScheduler.cancelAll(context) }

        // 注销账户：取消后连记录一起清掉（pruneWork），WorkManager 库里不留已删消息的 id。
        assertThat(infos("m1")).isEmpty()
        assertThat(infos("m2")).isEmpty()
    }

    @Test
    fun healAllEnqueuesOnlySharedPendingMessages() = runBlocking {
        suspend fun insert(id: String, share: String?, vararg states: String) {
            db.mediaDao().insertAll(
                states.mapIndexed { i, st ->
                    MediaItem(
                        messageId = id, index = i, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 1, height = 1,
                        byteLen = 0, blobSecret = ByteArray(0), blobId = "", state = st,
                    )
                },
            )
            db.dao().insert(
                ChatMessage(
                    id = id, peerUsername = "alice", direction = ChatMessage.DIRECTION_OUT, body = "[图片]",
                    timestamp = 1L, kind = ChatMessage.KIND_IMAGE, shareText = share,
                ),
            )
        }
        insert("shared-uploading", "🔒 a", MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING)
        insert("shared-failed", "🔒 b", MediaItem.STATE_FAILED)
        insert("unshared", null, MediaItem.STATE_FAILED)
        insert("all-sealed", "🔒 c", MediaItem.STATE_SEALED)
        insert("too-large", "🔒 d", MediaItem.STATE_FAILED)
        db.mediaDao().failPermanently("too-large", 0, MediaFailure.TOO_LARGE.name)
        val files = MediaFiles(File(context.cacheDir, "heal-${System.nanoTime()}"))
        val sender = MediaSender(
            dao = db.dao(),
            mediaDao = db.mediaDao(),
            files = files,
            crypto = UniffiMediaCrypto,
            transport = MediaTransport(relays = relaysOf("http://127.0.0.1:9")),
            sealFrame = { _, _ -> error("not used") },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { error("not used") },
            uploadNow = { error("not used") },
            shareHeaders = FakeShareHeaders,
        )

        UploadScheduler.healAll(context, sender)

        assertThat(infos("shared-uploading")).hasSize(1)
        assertThat(infos("shared-failed")).hasSize(1)
        assertThat(infos("unshared")).isEmpty()
        assertThat(infos("all-sealed")).isEmpty()
        assertThat(infos("too-large")).isEmpty() // 永久失败的项不再自愈（每次回前台白签一次名）
    }

    private fun senderFor(db: ChatDatabase) = MediaSender(
        dao = db.dao(),
        mediaDao = db.mediaDao(),
        files = MediaFiles(File(context.cacheDir, "heal-${System.nanoTime()}")),
        crypto = UniffiMediaCrypto,
        transport = MediaTransport(relays = relaysOf("http://127.0.0.1:9")),
        sealFrame = { _, _ -> error("not used") },
        inTransaction = db.inTransactionRunner(),
        uploadScheduler = { error("not used") },
        uploadNow = { error("not used") },
        shareHeaders = FakeShareHeaders,
    )

    @Test
    fun enqueueNowKeepsAFreshWorkThatHasNotRunYet() {
        // N1：冷启动时两处自愈（CcApp.onCreate、MainActivity.onStart）先后到——第二次不能把第一次刚建的任务顶掉。
        UploadScheduler.enqueue(context, "m1")
        val fresh = infos("m1").single()
        assertThat(fresh.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(fresh.runAttemptCount).isEqualTo(0)

        runBlocking { UploadScheduler.enqueueNow(context, "m1") }

        assertThat(infos("m1").single().id).isEqualTo(fresh.id)
    }

    @Test
    fun healAllReplacesAWorkWaitingInBackoff() = runBlocking {
        // N3：自愈必须走 enqueueNow（穿透退避），不是 enqueue（KEEP）。
        db.mediaDao().insertAll(
            listOf(
                MediaItem(
                    messageId = "m1", index = 0, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 1, height = 1,
                    byteLen = 0, blobSecret = ByteArray(0), blobId = "", state = MediaItem.STATE_UPLOADING,
                ),
            ),
        )
        db.dao().insert(
            ChatMessage(
                id = "m1", peerUsername = "alice", direction = ChatMessage.DIRECTION_OUT, body = "[图片]",
                timestamp = 1L, kind = ChatMessage.KIND_IMAGE, shareText = "🔒 a",
            ),
        )
        UploadScheduler.enqueue(context, "m1")
        startRunning("m1")
        val backingOff = awaitInfo("m1") { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }

        UploadScheduler.healAll(context, senderFor(db))

        val healed = infos("m1").single()
        assertThat(healed.id).isNotEqualTo(backingOff.id)
        assertThat(healed.runAttemptCount).isEqualTo(0)
    }
}
