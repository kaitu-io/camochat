package app.chencang.android.share

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.android.ui.chat.FakeShareHeaders
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.chat.inTransactionRunner
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.media.MediaFiles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private object NoCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) = null
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareCompletionReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository
    /** 接收器的 goAsync 协程都挂在这个 Job 下：[drain] 等它们全部结束，断言就不靠睡眠。 */
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val writes = mutableListOf<Pair<String, String>>()
    private val originalDeps = ShareCompletionReceiver.deps

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(NoCrypto),
            mediaDao = db.mediaDao(),
            mediaFiles = MediaFiles(File(context.cacheDir, "receiver-test")),
            inTransaction = db.inTransactionRunner(),
            shareHeaders = FakeShareHeaders,
        )
        ShareCompletionReceiver.deps = ShareCompletionReceiver.Deps {
            ShareCompletionReceiver.Handle(scope) { id, peer ->
                writes += id to peer
                repo.markSentIfOwned(id, peer)
            }
        }
        runBlocking {
            db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "hi", 1L, shareText = "🔒W"))
        }
    }

    @After
    fun tearDown() {
        ShareCompletionReceiver.deps = originalDeps
        scope.cancel()
        db.close()
    }

    private fun callbackIntent(id: String?, peer: String?, chosen: Boolean = true) =
        Intent(context, ShareCompletionReceiver::class.java).setAction(ShareCompletionReceiver.ACTION).apply {
            id?.let { putExtra(ShareCompletionReceiver.EXTRA_MESSAGE_ID, it) }
            peer?.let { putExtra(ShareCompletionReceiver.EXTRA_PEER, it) }
            if (chosen) putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI"))
        }

    private fun status(id: String) = runBlocking { db.dao().getById(id)!!.status }

    /** 等所有 goAsync 协程跑完（含 Room 写）。 */
    private fun drain() = runBlocking { job.children.toList().forEach { it.join() } }

    @Test
    fun `a chosen-target callback marks the message sent`() {
        ShareCompletionReceiver().onReceive(context, callbackIntent("m1", "alice"))
        drain()
        assertThat(status("m1")).isEqualTo(ChatMessage.STATUS_SENT)
        assertThat(writes).containsExactly("m1" to "alice")
    }

    @Test
    fun `no chosen component, wrong peer, unknown id or missing extras is a no-op`() {
        val r = ShareCompletionReceiver()
        r.onReceive(context, callbackIntent("m1", "alice", chosen = false))
        r.onReceive(context, callbackIntent("m1", "bob"))
        r.onReceive(context, callbackIntent("nope", "alice"))
        r.onReceive(context, callbackIntent(null, "alice"))
        r.onReceive(context, callbackIntent("m1", null))
        drain()
        assertThat(status("m1")).isEqualTo(ChatMessage.STATUS_SEALED)
        // 缺 chosen component / id / peer 的根本不进仓库；错 peer / 未知 id 进了但被守卫拒掉。
        assertThat(writes).containsExactly("m1" to "bob", "nope" to "alice")
    }

    @Test
    fun `the callback PendingIntent is explicit, mutable, per message and carries only id and peer`() {
        val a = ShareCompletionReceiver.pendingIntent(context, "m1", "alice")
        val b = ShareCompletionReceiver.pendingIntent(context, "m2", "alice")
        val sa = shadowOf(a)
        assertThat(sa.isBroadcast).isTrue()
        assertThat(sa.flags and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
        assertThat(sa.flags and PendingIntent.FLAG_UPDATE_CURRENT).isNotEqualTo(0)
        assertThat(sa.requestCode).isEqualTo("m1".hashCode())
        val saved = sa.savedIntent
        assertThat(saved.component).isEqualTo(ComponentName(context, ShareCompletionReceiver::class.java))
        assertThat(saved.extras!!.keySet())
            .containsExactly(ShareCompletionReceiver.EXTRA_MESSAGE_ID, ShareCompletionReceiver.EXTRA_PEER)
        assertThat(saved.getStringExtra(ShareCompletionReceiver.EXTRA_MESSAGE_ID)).isEqualTo("m1")
        // 两条消息的回调互不覆盖。
        assertThat(a).isNotEqualTo(b)
        assertThat(shadowOf(b).savedIntent.getStringExtra(ShareCompletionReceiver.EXTRA_MESSAGE_ID)).isEqualTo("m2")
    }
}
