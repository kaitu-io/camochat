package app.chencang.shared.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.media.FakeShareHeaders
import app.chencang.shared.media.MediaFiles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** 恒等「加密」:密文 = 明文帧;decryptFromBytesAny 恒定说发件人是 alice。 */
private class IdentityCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) =
        SessionCrypto.DecryptedAnyBytes(senderKey = "alice", plaintext = ciphertext)
}

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(IdentityCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = MediaFiles(File(context.cacheDir, "media-test")),
            inTransaction = db.inTransactionRunner(),
            now = { 42L },
            newId = { "fixed-id" },
            shareHeaders = FakeShareHeaders,
        )
    }

    @After
    fun tearDown() = db.close()

    /** 同一个内存 db,换一套 [SessionCrypto],造出行为不同的 repo 变体测异常契约。 */
    private fun repoWith(
        crypto: SessionCrypto,
        onIncomingStored: suspend (String) -> Unit = {},
    ): ChatRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(crypto),
            mediaDao = db.mediaDao(),
            mediaFiles = MediaFiles(File(context.cacheDir, "media-test-${crypto.hashCode()}")),
            inTransaction = db.inTransactionRunner(),
            onIncomingStored = onIncomingStored,
            shareHeaders = FakeShareHeaders,
        )
    }

    @Test
    fun `sendText returns lock-prefixed wire and persists out message`() = runTest {
        val wire = repo.sendText("alice", "明天老地方见")
        assertThat(wire).startsWith("🔒") // 🔒
        val thread = repo.observeThread("alice").first()
        assertThat(thread).hasSize(1)
        assertThat(thread[0].direction).isEqualTo(ChatMessage.DIRECTION_OUT)
        assertThat(thread[0].body).isEqualTo("明天老地方见")
    }

    @Test
    fun `receiveWireText round-trips a sent wire into an in message`() = runTest {
        val wire = repo.sendText("alice", "你好")
        val received = repo.receiveWireText("  $wire  ") // 容忍两端空白
        assertThat(received).isNotNull()
        assertThat(received!!.peerUsername).isEqualTo("alice")
        assertThat(received.direction).isEqualTo(ChatMessage.DIRECTION_IN)
        assertThat(received.body).isEqualTo("你好")
    }

    @Test
    fun `receiveWireText returns null on non-wire text`() = runTest {
        assertThat(repo.receiveWireText("这只是普通文字")).isNull()
    }

    @Test
    fun `receiveWireText returns null when no cached session matches the ciphertext`() = runTest {
        val noMatch = repoWith(
            object : SessionCrypto {
                override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
                override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
                override suspend fun decryptFromBytesAny(ciphertext: ByteArray): SessionCrypto.DecryptedAnyBytes =
                    throw RatchetSessionStore.NoSessionMatched(emptyList(), "no session matched")
            },
        )
        val wire = repo.sendText("alice", "你好")
        assertThat(noMatch.receiveWireText(wire)).isNull()
    }

    @Test
    fun `receiveWireText returns null when the crypto backend has no multi-session support`() = runTest {
        // SessionCrypto.decryptFromBytesAny's default implementation returns null
        // (documented contract for backends that don't support multi-session lookup).
        val noSupport = repoWith(
            object : SessionCrypto {
                override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
                override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
            },
        )
        val wire = repo.sendText("alice", "你好")
        assertThat(noSupport.receiveWireText(wire)).isNull()
    }

    @Test
    fun `receiveWireText propagates a decrypt-time persistence failure instead of treating it as not-ours`() = runTest {
        // RatchetSessionStore#142: decryptFromBytesAny persists ratchet state via a
        // suspend Room write even on failed attempts; if that write throws, the
        // exception must reach the caller (→ IntakeFailure.SAVE_FAILED),
        // never be swallowed into "not our ciphertext".
        val brokenPersist = repoWith(
            object : SessionCrypto {
                override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
                override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
                override suspend fun decryptFromBytesAny(ciphertext: ByteArray): SessionCrypto.DecryptedAnyBytes =
                    throw IOException("ratchet persist failed: disk full")
            },
        )
        val wire = repo.sendText("alice", "你好")
        var thrown: Throwable? = null
        try {
            brokenPersist.receiveWireText(wire)
        } catch (e: IOException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
    }

    @Test
    fun `markSent 落库 sent 状态`() = runTest {
        repo.sendText("alice", "hello")
        val id = repo.observeThread("alice").first().single().id
        repo.markSent(id)
        assertThat(repo.observeThread("alice").first().single().status).isEqualTo(ChatMessage.STATUS_SENT)
    }

    @Test
    fun `markSentIfOwned marks only an outgoing message of the named peer`() = runTest {
        repo.sendText("alice", "hi")
        val id = repo.observeThread("alice").first().single().id

        assertThat(repo.markSentIfOwned(id, "bob")).isEqualTo(ChatRepository.MarkResult.NOT_OWNED)
        assertThat(repo.markSentIfOwned("unknown-id", "alice")).isEqualTo(ChatRepository.MarkResult.NOT_OWNED)
        assertThat(repo.observeThread("alice").first().single().status).isEqualTo(ChatMessage.STATUS_SEALED)

        assertThat(repo.markSentIfOwned(id, "alice")).isEqualTo(ChatRepository.MarkResult.MARKED)
        assertThat(repo.observeThread("alice").first().single().status).isEqualTo(ChatMessage.STATUS_SENT)
        assertThat(repo.markSentIfOwned(id, "alice")).isEqualTo(ChatRepository.MarkResult.UNCHANGED)
    }

    @Test
    fun `markSentIfOwned never touches an incoming message`() = runTest {
        db.dao().insert(ChatMessage("in-1", "alice", ChatMessage.DIRECTION_IN, "hey", 1L))
        assertThat(repo.markSentIfOwned("in-1", "alice")).isEqualTo(ChatRepository.MarkResult.NOT_OWNED)
        assertThat(db.dao().getById("in-1")!!.status).isEqualTo(ChatMessage.STATUS_SEALED)
    }

    @Test
    fun `an outgoing text keeps its wire on the row so it can be handed off again`() = runTest {
        val wire = repo.sendText("alice", "hi")
        assertThat(repo.observeThread("alice").first().single().shareText).isEqualTo(wire)
    }

    @Test
    fun `receiveWireText invokes onIncomingStored with the sender`() = runTest {
        val stored = mutableListOf<String>()
        val notifying = repoWith(IdentityCrypto(), onIncomingStored = { stored += it })
        val wire = notifying.sendText("alice", "你好")
        assertThat(stored).isEmpty() // sending is not receiving

        val received = notifying.receiveWireText(wire)

        assertThat(received).isNotNull()
        assertThat(stored).containsExactly("alice")
    }

    @Test
    fun `receiveWireText does not invoke onIncomingStored when nothing decrypts`() = runTest {
        val stored = mutableListOf<String>()
        val noMatch = repoWith(
            object : SessionCrypto {
                override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
                override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
                override suspend fun decryptFromBytesAny(ciphertext: ByteArray): SessionCrypto.DecryptedAnyBytes =
                    throw RatchetSessionStore.NoSessionMatched(emptyList(), "no session matched")
            },
            onIncomingStored = { stored += it },
        )
        val wire = repo.sendText("alice", "你好")

        assertThat(noMatch.receiveWireText(wire)).isNull()
        assertThat(noMatch.receiveWireText("这只是普通文字")).isNull()
        assertThat(stored).isEmpty()
    }

    @Test
    fun `receiveWireText still returns the stored message when onIncomingStored fails`() = runTest {
        // The message is on disk and the ratchet has advanced: a failed cleanup must not be
        // reported as a failed receive.
        val failing = repoWith(IdentityCrypto(), onIncomingStored = { throw IOException("store unavailable") })
        val wire = failing.sendText("alice", "你好")

        val received = failing.receiveWireText(wire)

        assertThat(received!!.body).isEqualTo("你好")
        assertThat(db.dao().getById(received.id)).isNotNull()
    }

    @Test
    fun `receiveWireText still succeeds when onIncomingStored throws CancellationException`() = runTest {
        // The callback runs inside the non-cancellable store section: its own cancellation is a
        // failed cleanup, not a cancelled receive.
        val cancelling = repoWith(
            IdentityCrypto(),
            onIncomingStored = { throw kotlinx.coroutines.CancellationException("cleanup scope cancelled") },
        )
        val wire = cancelling.sendText("alice", "你好")

        val received = cancelling.receiveWireText(wire)

        assertThat(received!!.body).isEqualTo("你好")
        assertThat(db.dao().getById(received.id)).isNotNull()
    }
}
