package app.chencang.android.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.chat.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 分享热桥（spec 2026-09-30 §5.2）：系统分享面板在用户**选定目标 App** 时经 [callback] 的
 * IntentSender 发来这条广播（带 `EXTRA_CHOSEN_COMPONENT`）——这是 Android 能给的最强
 * 「交出去了」信号，据此把消息标成已送出。取消面板不会有广播，消息保持「封缄」。
 *
 * 不导出（manifest `exported=false`）：只有经我们自己的 PendingIntent 才能到这里。
 * extras 里只有 messageId 与 peer——绝不放 wire、明文或 blob secret。
 * 标记按「这条是 peer 线程里自己发出的」守卫（[ChatRepository.markSentIfOwned]），未知 id / 别的会话一律 no-op。
 */
class ShareCompletionReceiver : BroadcastReceiver() {

    /** 标记动作与应用作用域；生产取 [CcServiceLocator]，测试可替换（不可并行改写，见 [deps]）。 */
    class Handle(val scope: CoroutineScope, val mark: suspend (messageId: String, peer: String) -> ChatRepository.MarkResult)

    fun interface Deps {
        fun resolve(context: Context): Handle
    }

    override fun onReceive(context: Context, intent: Intent) {
        val target = parse(intent) ?: return
        val handle = deps.resolve(context)
        // 经系统派发时非空；单测直接调 onReceive 时为 null。
        val pending: PendingResult? = goAsync()
        handle.scope.launch {
            try {
                val result = handle.mark(target.messageId, target.peer)
                if (result == ChatRepository.MarkResult.NOT_OWNED) Log.w(TAG, "callback does not point to an own pending message; ignored")
            } catch (e: Exception) {
                // 只记类名：异常 message 里可能带路径等细节。
                Log.e(TAG, "mark shared failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    data class Target(val messageId: String, val peer: String)

    companion object {
        private const val TAG = "ShareCompletion"
        const val ACTION = "app.chencang.android.action.SHARE_COMPLETED"
        const val EXTRA_MESSAGE_ID = "app.chencang.android.extra.MESSAGE_ID"
        const val EXTRA_PEER = "app.chencang.android.extra.PEER"

        @Volatile
        internal var deps: Deps = Deps { ctx ->
            val locator = CcServiceLocator.from(ctx)
            Handle(locator.scope) { id, peer -> locator.chatRepository.markSentIfOwned(id, peer) }
        }

        /** 只认「真的选了目标」的回调：没有 `EXTRA_CHOSEN_COMPONENT` 或缺 id/peer 的一律丢弃。 */
        internal fun parse(intent: Intent): Target? {
            if (intent.action != ACTION || !intent.hasExtra(Intent.EXTRA_CHOSEN_COMPONENT)) return null
            val id = intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf { it.isNotEmpty() } ?: return null
            val peer = intent.getStringExtra(EXTRA_PEER)?.takeIf { it.isNotEmpty() } ?: return null
            return Target(id, peer)
        }

        /**
         * 给 `Intent.createChooser(send, title, sender)` 的回调。显式组件；`FLAG_MUTABLE` 是必须的——
         * 系统要往里填 `EXTRA_CHOSEN_COMPONENT`；`FLAG_UPDATE_CURRENT` + 按消息区分的 identifier/requestCode，
         * 保证两条排队消息的回调不会互相覆盖 extras。
         *
         * 信任模型（可变 PendingIntent 的残余风险）：`FLAG_MUTABLE` 意味着持有者可用 `Intent.fillIn`
         * 改写未设值的字段乃至覆盖 extras（包括 [EXTRA_MESSAGE_ID]/[EXTRA_PEER]）。但组件是显式的、
         * 接收器不导出，持有者只有系统分享面板——IntentSender 从不交给目标 App。所以 extras 只当「提示」：
         * 最坏情况是把**本机某条自己发出、待送出**的消息标成已送出，由 [ChatRepository.markSentIfOwned]
         * 兜住（未知 id / 别的会话 / 收到的消息一律 no-op），不泄露任何数据、不影响别的会话。
         */
        fun pendingIntent(context: Context, messageId: String, peer: String): PendingIntent {
            val intent = Intent(context, ShareCompletionReceiver::class.java)
                .setAction(ACTION)
                .setIdentifier(messageId) // 参与 filterEquals：每条消息一个独立的 PendingIntent
                .putExtra(EXTRA_MESSAGE_ID, messageId)
                .putExtra(EXTRA_PEER, peer)
            return PendingIntent.getBroadcast(
                context,
                messageId.hashCode(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun callback(context: Context, messageId: String, peer: String): IntentSender =
            pendingIntent(context, messageId, peer).intentSender
    }
}
