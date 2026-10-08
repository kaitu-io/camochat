package app.chencang.shared.media

/**
 * 后台上传引擎在 `:shared` 这一侧的样子（spec 2026-09-30 §1.1）。生产实现在 `:app`
 * （WorkManager 唯一任务 `upload-<messageId>`），由 `CcApp` 在构建 [app.chencang.shared.CcServiceLocator]
 * 之前装上。`:shared` 不依赖 WorkManager，只经这个接口交出/撤回消息。
 */
interface UploadEngine {
    /** 把一条已分享的消息交给引擎；同一条已经排着/在跑就什么都不做（幂等）。 */
    fun enqueue(messageId: String)

    /** 「现在就试」：不打断正在跑的；在退避里等着的或已结束的换成新任务（回前台自愈 / 手动重试）。 */
    suspend fun enqueueNow(messageId: String)

    /** 取消这条消息的上传（删消息/清会话时在删文件之前调）。 */
    suspend fun cancel(messageId: String)

    /** 取消所有上传（注销账户）。 */
    suspend fun cancelAll()
}
