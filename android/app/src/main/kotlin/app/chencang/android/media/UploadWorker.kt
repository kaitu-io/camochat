package app.chencang.android.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import app.chencang.android.R
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.R as SharedR
import app.chencang.shared.media.MediaSender
import app.chencang.shared.media.UploadOutcome
import kotlinx.coroutines.CancellationException

/** [UploadWorker] 需要的那两步（生产 = [MediaSender]；测试 = 假实现）。 */
interface UploadTarget {
    suspend fun upload(messageId: String): UploadOutcome
    suspend fun markUploadFailed(messageId: String)

    /** 这条消息从何时起等着上传（分享文本落库 / 最近一次手动重试）；消息已删 → null。 */
    suspend fun pendingSince(messageId: String): Long?
}

private fun MediaSender.asUploadTarget(): UploadTarget {
    val sender = this
    return object : UploadTarget {
        override suspend fun upload(messageId: String) = sender.upload(messageId)
        override suspend fun markUploadFailed(messageId: String) = sender.markUploadFailed(messageId)
        override suspend fun pendingSince(messageId: String) = sender.uploadSince(messageId)
    }
}

/**
 * 上传一条已分享消息的所有未上传项（spec 2026-09-30 §1.1）。结果映射：
 * - `Done` → success
 * - `Retryable`（网络 / 3xx / 5xx / 403 / 408 / 429 / 读文件偶发错）→ retry（指数退避 10 s 起），直到**下一次
 *   尝试会落在「进入上传中」（分享那一刻 / 最近一次手动重试，`media_item.upload_since`）后 [GIVE_UP_AFTER_MS]（6 h）之外**：那就现在放弃——[MediaSender.markUploadFailed]
 *   （红 `!`「对方还看不到」）并 success。回前台的自愈 / 手动重试会再踢它一次（每次踢只多试一次）。
 *   按时间而不按次数：WorkManager 的退避上限是 5 h，20 次要 ~50 h；而且 REPLACE 会把次数清零，
 *   只有落库的「等了多久」在重新入队、进程被杀之后都不变。
 * - `Permanent`（太大 / 被拒 / `.cca` 丢失 / 消息已删）→ success（出问题的项 upload 已经标好了）。
 *
 * 由 WorkManager 默认工厂反射 `(Context, WorkerParameters)` 构造；进程被杀后由 WorkManager 拉起时，
 * `CcApp.onCreate` 已先跑完（按需初始化），[CcServiceLocator.from] 拿到的是装好上传引擎的同一套单例。
 */
class UploadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val target: () -> UploadTarget,
    private val now: () -> Long,
) : CoroutineWorker(context, params) {

    @Suppress("unused") // WorkManager 默认 WorkerFactory 反射调用
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        { CcServiceLocator.from(context.applicationContext).mediaSender.asUploadTarget() },
        System::currentTimeMillis,
    )

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(UploadScheduler.KEY_MESSAGE_ID) ?: return Result.failure()
        val sender = target()
        val outcome = try {
            sender.upload(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 意外异常（DAO 写失败等）：upload 抛出前已把未上传项标 failed；不再无限重试。
            return Result.failure()
        }
        return when (outcome) {
            UploadOutcome.Done -> Result.success()
            is UploadOutcome.Permanent -> Result.success()
            is UploadOutcome.Retryable -> {
                val since = sender.pendingSince(messageId) ?: return Result.success() // 消息已删
                if (now() + nextBackoffMs(runAttemptCount) - since <= GIVE_UP_AFTER_MS) {
                    Result.retry()
                } else {
                    sender.markUploadFailed(messageId)
                    Result.success()
                }
            }
        }
    }

    /** API < 31 的加急任务跑在前台服务里：低重要度渠道，只有固定文案，不含任何内容/对象信息。 */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(SharedR.string.media_upload_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle(applicationContext.getString(SharedR.string.media_upload_notification))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /** 分享后最多这么久还没传上去就标「对方还看不到」（spec §1.1.1）。 */
        const val GIVE_UP_AFTER_MS = 6 * 60 * 60 * 1000L

        /** 与 WorkManager 的 EXPONENTIAL 一致：第 n 次（0 起）失败后等 10 s·2ⁿ，封顶 5 h。 */
        internal fun nextBackoffMs(runAttemptCount: Int): Long {
            val base = UploadScheduler.BACKOFF_SECONDS * 1000L
            val max = WorkRequest.MAX_BACKOFF_MILLIS
            return if (runAttemptCount >= 20) max else minOf(base shl runAttemptCount, max)
        }
        private const val CHANNEL_ID = "media-upload"
        private const val NOTIFICATION_ID = 0x5E4D
    }
}
