package app.chencang.android.media

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.chencang.shared.R as SharedR
import app.chencang.shared.media.MediaFailure
import app.chencang.shared.media.UploadOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 假 sender：按预设结果回答 upload，记下 upload / markUploadFailed 的调用。 */
private class FakeTarget(
    private val pendingSince: Long? = NOW,
    private val outcome: () -> UploadOutcome,
) : UploadTarget {
    val uploads = mutableListOf<String>()
    val markedFailed = mutableListOf<String>()

    override suspend fun upload(messageId: String): UploadOutcome {
        uploads += messageId
        return outcome()
    }

    override suspend fun markUploadFailed(messageId: String) {
        markedFailed += messageId
    }

    override suspend fun pendingSince(messageId: String): Long? = pendingSince
}

private const val NOW = 1_000_000_000_000L
private const val HOUR = 3_600_000L

@RunWith(RobolectricTestRunner::class)
// Stock Application：跳过 CcApp 的 keystore 初始化；Worker 的依赖由工厂注入。
@Config(application = Application::class)
class UploadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(target: UploadTarget, attempt: Int = 0, messageId: String? = "m1", now: Long = NOW): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(context)
            .apply { if (messageId != null) setInputData(UploadScheduler.inputFor(messageId)) }
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        UploadWorker(appContext, workerParameters, { target }, { now })
                },
            )
            .build()

    private fun run(w: UploadWorker): ListenableWorker.Result = runBlocking { w.doWork() }

    @Test
    fun doneIsSuccess() {
        val target = FakeTarget { UploadOutcome.Done }
        assertThat(run(worker(target))).isEqualTo(ListenableWorker.Result.success())
        assertThat(target.uploads).containsExactly("m1")
        assertThat(target.markedFailed).isEmpty()
    }

    @Test
    fun retryableIsRetryWhileTheNextAttemptStillFallsInsideTheSixHourWindow() {
        val target = FakeTarget { UploadOutcome.Retryable(MediaFailure.NETWORK) }
        assertThat(run(worker(target, attempt = 0))).isEqualTo(ListenableWorker.Result.retry())
        // 第 10 次失败：下次在 10 s·2¹⁰ ≈ 2.8 h 后，距分享约 2.8 h 以内 → 继续
        assertThat(run(worker(target, attempt = 10, now = NOW + HOUR))).isEqualTo(ListenableWorker.Result.retry())
        assertThat(target.markedFailed).isEmpty()
    }

    @Test
    fun retryableGivesUpWhenTheNextBackoffWouldLandPastSixHours() {
        // 自然退避下第 11 次失败发生在分享后约 5.7 h，下一次退避 5 h 会落到 10.7 h → 现在就放弃。
        val target = FakeTarget { UploadOutcome.Retryable(MediaFailure.SERVER) }
        assertThat(run(worker(target, attempt = 11, now = NOW + 5 * HOUR + 40 * 60_000L)))
            .isEqualTo(ListenableWorker.Result.success())
        assertThat(target.markedFailed).containsExactly("m1")
    }

    @Test
    fun anOldMessageHealedLaterGetsOneAttemptPerKickThenGivesUp() {
        // 分享已过 6 h（例如早就标了「对方还看不到」、这次是回前台自愈/手动重试重新入队）：只试这一次。
        val target = FakeTarget(pendingSince = NOW - 7 * HOUR) { UploadOutcome.Retryable(MediaFailure.NETWORK) }
        assertThat(run(worker(target, attempt = 0))).isEqualTo(ListenableWorker.Result.success())
        assertThat(target.markedFailed).containsExactly("m1")
    }

    @Test
    fun aRetryableForAMessageThatVanishedMeanwhileEndsQuietly() {
        val target = FakeTarget(pendingSince = null) { UploadOutcome.Retryable(MediaFailure.NETWORK) }
        assertThat(run(worker(target))).isEqualTo(ListenableWorker.Result.success())
        assertThat(target.markedFailed).isEmpty()
    }

    @Test
    fun permanentIsSuccessWithoutMarkingAgain() {
        // upload 已把出问题的那几项标了 failed（或消息已删，什么都不该写）。
        for (f in listOf(MediaFailure.TOO_LARGE, MediaFailure.REJECTED, MediaFailure.FILE_MISSING, MediaFailure.DELETED)) {
            val target = FakeTarget { UploadOutcome.Permanent(f) }
            assertThat(run(worker(target))).isEqualTo(ListenableWorker.Result.success())
            assertThat(target.markedFailed).isEmpty()
        }
    }

    @Test
    fun anUnexpectedExceptionFailsTheWorkInsteadOfRetryingForever() {
        // MediaSender.upload 遇到意外异常（DAO 写失败）时已把未上传项标 failed 再抛出。
        val target = FakeTarget { throw IllegalStateException("db closed") }
        assertThat(run(worker(target))).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun missingInputFailsWithoutTouchingAnything() {
        val target = FakeTarget { UploadOutcome.Done }
        assertThat(run(worker(target, messageId = null))).isEqualTo(ListenableWorker.Result.failure())
        assertThat(target.uploads).isEmpty()
    }

    @Test
    fun foregroundInfoIsALowImportanceDataSyncNotificationWithTheFixedCopyOnly() {
        val info = runBlocking { worker(FakeTarget { UploadOutcome.Done }).getForegroundInfo() }

        assertThat(info.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertThat(info.notification.smallIcon.resId).isEqualTo(app.chencang.android.R.drawable.ic_stat_upload)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(info.notification.channelId)
        assertThat(channel.name.toString()).isEqualTo(context.getString(SharedR.string.media_upload_channel))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        val extras = info.notification.extras
        assertThat(extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(SharedR.string.media_upload_notification))
        // 除固定文案外不带任何内容（不含 messageId / blob 信息）。
        assertThat(extras.getCharSequence(Notification.EXTRA_TEXT)).isNull()
    }
}
