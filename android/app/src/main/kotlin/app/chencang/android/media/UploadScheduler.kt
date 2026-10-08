package app.chencang.android.media

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.media.MediaSender
import app.chencang.shared.media.UploadEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 媒体后台上传引擎的调度面（spec 2026-09-30 §1.1 / §1.2）：每条已分享的消息一个 WorkManager 唯一任务
 * `upload-<messageId>`，由 [UploadWorker] 调 [MediaSender.upload]。
 *
 * 隐私：任务里只带 messageId（唯一名 + input data），tag 只有固定的 [TAG]；不带 blob secret / blob id / wire。
 */
object UploadScheduler {
    /** 所有上传任务共用的固定 tag（注销账户时一把撤掉）。 */
    const val TAG = "media-upload"
    internal const val KEY_MESSAGE_ID = "messageId"
    internal const val BACKOFF_SECONDS = 10L
    private const val LOG_TAG = "Chencang"

    private fun uniqueName(messageId: String) = "upload-$messageId"

    internal fun inputFor(messageId: String): Data = workDataOf(KEY_MESSAGE_ID to messageId)

    /**
     * 首次交给引擎（seal 之后）：同名任务还在排队/在跑（含退避等待中）就保持原样（`KEEP`）；已经结束的会被新任务顶替。
     * 联网才跑；失败指数退避起步 10 s；尽量加急（配额用完退成普通任务）。
     */
    fun enqueue(context: Context, messageId: String) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(messageId), ExistingWorkPolicy.KEEP, request(messageId))
    }

    /**
     * 「现在就试」（回前台自愈、App 启动、手动重试）：
     * - 正在跑的不打断（一个快传完的大 PUT 不值得重来）；
     * - 排着队、还一次都没跑过的（`ENQUEUED` 且 `runAttemptCount == 0`：刚入队、或在等网络）也不动——
     *   它本来就「一有网就跑」，顶掉只会浪费（N1：冷启动时 `CcApp.onCreate` 与 `MainActivity.onStart` 的两次自愈）；
     * - 其余——在退避里等着的、已经结束的、没有的——`REPLACE` 成新任务，不再等剩下的退避。
     * 进程内用 [enqueueNowLock] 串行：两个调用方不会都读到「退避中」再先后 REPLACE、互相顶掉刚建的任务。
     * 剩下的只有 WorkManager 自己在「读状态」与「入队」之间恰好开跑那一次：安全（PUT 幂等、412 算成功，
     * 同一条消息的 upload 串行），只是浪费。
     */
    suspend fun enqueueNow(context: Context, messageId: String) = enqueueNowLock.withLock {
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWorkFlow(uniqueName(messageId)).first()
        if (infos.any { it.state == WorkInfo.State.RUNNING || it.isFreshlyQueued() }) return@withLock
        wm.enqueueUniqueWork(uniqueName(messageId), ExistingWorkPolicy.REPLACE, request(messageId)).await()
        Unit
    }

    private val enqueueNowLock = Mutex()

    private fun WorkInfo.isFreshlyQueued() = state == WorkInfo.State.ENQUEUED && runAttemptCount == 0

    private fun request(messageId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputFor(messageId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()

    /** 取消这条消息的上传（正在跑的 Worker 会被停下）；等 WorkManager 落库了才返回。 */
    suspend fun cancel(context: Context, messageId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(messageId)).await()
    }

    /** 注销账户：取消全部上传，再把已结束的记录清掉——WorkManager 库里不留已删消息的 id。 */
    suspend fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(TAG).await()
        wm.pruneWork().await()
    }

    /**
     * 自愈：把所有「已分享、还有 `uploading` 项或可重试的 `failed` 项」的发出消息「现在就试」（[enqueueNow]，
     * 不打断在跑的）。**永久失败**的项（记了 `upload_failure`：太大 / 被拒收 / 文件丢失）不算——终态，手动重试也不再传。
     */
    suspend fun healAll(context: Context, sender: MediaSender = CcServiceLocator.from(context).mediaSender) {
        for (id in sender.pendingUploadIds()) enqueueNow(context, id)
    }

    /**
     * App 启动 / 回前台时调：在进程级 scope 里跑 [healAll]，不跟界面生命周期走。
     * 失败（DB 打不开之类）只记日志——下次回前台还会再试。
     */
    fun healInBackground(context: Context) {
        val app = context.applicationContext
        CcServiceLocator.from(app).scope.launch {
            try {
                healAll(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "upload self-heal failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** `:shared` 侧 [UploadEngine] 的 WorkManager 实现。 */
    fun engine(context: Context): UploadEngine {
        val app = context.applicationContext
        return object : UploadEngine {
            override fun enqueue(messageId: String) = enqueue(app, messageId)
            override suspend fun enqueueNow(messageId: String) = enqueueNow(app, messageId)
            override suspend fun cancel(messageId: String) = cancel(app, messageId)
            override suspend fun cancelAll() = cancelAll(app)
        }
    }
}
