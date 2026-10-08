package app.chencang.shared.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.media.FakeShareHeaders
import app.chencang.shared.media.MediaFiles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** clearThread 从不触碰加解密,桩永远不会被调用。 */
private class UnusedCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
        error("not used by clearThread tests")
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray): ByteArray =
        error("not used by clearThread tests")
}

/** [ChatRepository.clearThread] / [ChatRepository.deleteMessage] — 本地删除连带媒体。 */
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryClearThreadTest {
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository
    private lateinit var files: MediaFiles

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        files = MediaFiles(File(context.cacheDir, "media-clear-${System.nanoTime()}"))
        repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(UnusedCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = db.inTransactionRunner(),
            shareHeaders = FakeShareHeaders,
        )
    }

    @After
    fun tearDown() {
        db.close()
        files.deleteAll()
    }

    private fun item(msg: String) = MediaItem(
        messageId = msg, index = 0, kind = 2, durMs = 0, width = 1, height = 1, byteLen = 60L,
        blobSecret = ByteArray(32), blobId = "x".repeat(22), state = MediaItem.STATE_READY,
    )

    @Test
    fun `a media message inserted just before the clear transaction does not leave an orphan folder`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        files.write(files.bin("m1", 0), byteArrayOf(1))
        // 模拟并发：事务开始前的一瞬间，另一条媒体消息（行 + 明文目录）落了地。
        val racing = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(UnusedCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = { block ->
                db.dao().insert(ChatMessage("late", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE))
                files.write(files.bin("late", 0), byteArrayOf(2))
                db.inTransactionRunner()(block)
            },
            shareHeaders = FakeShareHeaders,
        )

        racing.clearThread("alice")

        assertThat(repo.observeThread("alice").first()).isEmpty()
        assertThat(files.dir("m1").exists()).isFalse()
        assertThat(files.dir("late").exists()).isFalse()
    }

    @Test
    fun `clearThread only clears the target peer, others survive`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "给alice", 1L))
        db.dao().insert(ChatMessage("m2", "alice", ChatMessage.DIRECTION_IN, "alice回", 2L))
        db.dao().insert(ChatMessage("m3", "bob", ChatMessage.DIRECTION_OUT, "给bob", 3L))

        repo.clearThread("alice")

        assertThat(repo.observeThread("alice").first()).isEmpty()
        val bobThread = repo.observeThread("bob").first()
        assertThat(bobThread).hasSize(1)
        assertThat(bobThread.single().id).isEqualTo("m3")
    }

    @Test
    fun `clearThread deletes that peer's media rows and media directories`() = runTest {
        db.dao().insert(ChatMessage("ma", "alice", ChatMessage.DIRECTION_IN, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("mb", "bob", ChatMessage.DIRECTION_IN, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE))
        db.mediaDao().insertAll(listOf(item("ma"), item("mb")))
        files.write(files.bin("ma", 0), byteArrayOf(1))
        files.write(files.bin("mb", 0), byteArrayOf(2))

        repo.clearThread("alice")

        assertThat(db.mediaDao().forMessage("ma")).isEmpty()
        assertThat(files.dir("ma").exists()).isFalse()
        assertThat(db.mediaDao().forMessage("mb")).hasSize(1)
        assertThat(files.bin("mb", 0).exists()).isTrue()
    }

    /**
     * Regression for fix round 1: `clearThread` used to abort the whole
     * `forEach` on the first `IOException`, permanently orphaning every
     * message dir after the failing one (their DB rows are already gone by
     * then). Make the SECOND message's directory undeletable and assert the
     * other two still get cleaned up, the DB rows for all three are gone
     * either way, and the surviving directory is named in the thrown
     * [IOException].
     */
    @Test
    fun `clearThread attempts every message dir even after one fails, then reports it`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m2", "alice", ChatMessage.DIRECTION_IN, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m3", "alice", ChatMessage.DIRECTION_IN, "[图片]", 3L, kind = ChatMessage.KIND_IMAGE))
        db.mediaDao().insertAll(listOf(item("m1"), item("m2"), item("m3")))
        files.write(files.bin("m1", 0), byteArrayOf(1))
        files.write(files.bin("m2", 0), byteArrayOf(2))
        files.write(files.bin("m3", 0), byteArrayOf(3))

        val stuckDir = files.dir("m2")
        check(stuckDir.setWritable(false)) { "test setup: could not mark $stuckDir read-only" }
        try {
            // Same platform caveat as MediaFilesTest: some JVM/user/CI-sandbox
            // combinations (notably root) ignore the read-only bit for unlink.
            assumeTrue(
                "this JVM/user does not enforce directory write permissions for delete",
                !files.bin("m2", 0).delete(),
            )

            var caught: IOException? = null
            try {
                repo.clearThread("alice")
            } catch (e: IOException) {
                caught = e
            }
            assertThat(caught).isNotNull()
            assertThat(caught!!).hasMessageThat().contains("m2")

            assertThat(files.dir("m1").exists()).isFalse()
            assertThat(files.dir("m2").exists()).isTrue() // still stuck — reported, not silently dropped
            assertThat(files.dir("m3").exists()).isFalse()

            // DB rows are gone for all three regardless of the file-deletion outcome.
            assertThat(db.dao().getById("m1")).isNull()
            assertThat(db.dao().getById("m2")).isNull()
            assertThat(db.dao().getById("m3")).isNull()
            assertThat(db.mediaDao().forMessage("m2")).isEmpty()
        } finally {
            stuckDir.setWritable(true) // let tearDown's files.deleteAll() succeed
        }
    }

    @Test
    fun `deleteMessage removes the row, its media items and its directory`() = runTest {
        db.dao().insert(ChatMessage("ma", "alice", ChatMessage.DIRECTION_IN, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.mediaDao().insertAll(listOf(item("ma")))
        files.write(files.bin("ma", 0), byteArrayOf(1))

        repo.deleteMessage("ma")

        assertThat(db.dao().getById("ma")).isNull()
        assertThat(db.mediaDao().forMessage("ma")).isEmpty()
        assertThat(files.dir("ma").exists()).isFalse()
    }

    /** 记录每次取消上传时，这条消息的目录还在不在（必须在：先取消、后删文件）。 */
    private fun cancellingRepo(log: MutableList<Pair<String, Boolean>>) = ChatRepository(
        dao = db.dao(),
        sessions = SessionManager(UnusedCrypto()),
        mediaDao = db.mediaDao(),
        mediaFiles = files,
        inTransaction = db.inTransactionRunner(),
        cancelUpload = { id -> log += id to files.dir(id).exists() },
        shareHeaders = FakeShareHeaders,
    )

    @Test
    fun `deleteMessage cancels the message's upload before deleting its files`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.mediaDao().insertAll(listOf(item("m1")))
        files.write(files.cca("m1", 0), byteArrayOf(1))
        val log = mutableListOf<Pair<String, Boolean>>()

        cancellingRepo(log).deleteMessage("m1")

        assertThat(log).containsExactly("m1" to true)
        assertThat(files.dir("m1").exists()).isFalse()
        assertThat(db.dao().getById("m1")).isNull()
    }

    @Test
    fun `a failing cancel does not make deleteMessage fail once the row is gone`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        files.write(files.cca("m1", 0), byteArrayOf(1))
        val broken = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(UnusedCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = db.inTransactionRunner(),
            cancelUpload = { throw IllegalStateException("work manager down") },
            shareHeaders = FakeShareHeaders,
        )

        broken.deleteMessage("m1") // 不抛：行已删，任务之后只会拿到「消息已删」

        assertThat(files.dir("m1").exists()).isFalse()
        assertThat(db.dao().getById("m1")).isNull()
    }

    @Test
    fun `clearThread cancels every media message's upload before deleting their files, and skips text`() = runTest {
        db.dao().insert(ChatMessage("ma", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("mb", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("mt", "alice", ChatMessage.DIRECTION_OUT, "文字", 3L))
        db.dao().insert(ChatMessage("mc", "bob", ChatMessage.DIRECTION_OUT, "[图片]", 4L, kind = ChatMessage.KIND_IMAGE))
        db.mediaDao().insertAll(listOf(item("ma"), item("mb"), item("mc")))
        listOf("ma", "mb", "mc").forEach { files.write(files.cca(it, 0), byteArrayOf(1)) }
        val log = mutableListOf<Pair<String, Boolean>>()

        cancellingRepo(log).clearThread("alice")

        assertThat(log).containsExactly("ma" to true, "mb" to true)
        assertThat(files.dir("ma").exists()).isFalse()
        assertThat(files.dir("mb").exists()).isFalse()
        assertThat(files.dir("mc").exists()).isTrue()
    }

    @Test
    fun `cancelling the caller mid-clear still deletes every media folder`() = runTest {
        val ids = (1..5).map { "m$it" }
        ids.forEachIndexed { i, id ->
            db.dao().insert(ChatMessage(id, "alice", ChatMessage.DIRECTION_OUT, "[图片]", i.toLong(), kind = ChatMessage.KIND_IMAGE))
            files.write(files.bin(id, 0), byteArrayOf(1)) // 明文
        }
        db.mediaDao().insertAll(ids.map { item(it) })
        var caller: Job? = null
        val repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(UnusedCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = db.inTransactionRunner(),
            // 第一次取消上传时，调用方（viewModelScope）被取消——比如用户删联系人后立刻返回。
            cancelUpload = {
                caller!!.cancel()
                yield()
            },
            shareHeaders = FakeShareHeaders,
        )

        caller = launch { repo.clearThread("alice") }
        caller.join()

        assertThat(caller.isCancelled).isTrue()
        ids.forEach { assertThat(files.dir(it).exists()).isFalse() }
        assertThat(db.dao().idsForPeer("alice")).isEmpty()
    }

    /**
     * N2b：调用方恰在事务**执行期间**被取消。Room 的事务提交了，但 `withContext` 返回时照样抛取消——
     * 事务之后的取消上传 / 删目录若不在同一个不可取消区间里，就会被跳过，留下没有任何行能触发清理的明文。
     * 这里的事务替身：先真跑事务体，再取消调用方并 `yield()`（模拟 `withContext` 返回时的取消检查）。
     */
    private fun repoCancelledInsideTransaction(caller: () -> Job, cancelled: MutableList<String>) = ChatRepository(
        dao = db.dao(),
        sessions = SessionManager(UnusedCrypto()),
        mediaDao = db.mediaDao(),
        mediaFiles = files,
        inTransaction = { block ->
            db.inTransactionRunner()(block)
            caller().cancel()
            yield()
        },
        cancelUpload = { cancelled += it },
        shareHeaders = FakeShareHeaders,
    )

    @Test
    fun `clearThread cancelled while its transaction runs still cancels uploads and deletes every folder`() = runTest {
        val ids = (1..3).map { "m$it" }
        ids.forEachIndexed { i, id ->
            db.dao().insert(ChatMessage(id, "alice", ChatMessage.DIRECTION_OUT, "[图片]", i.toLong(), kind = ChatMessage.KIND_IMAGE))
            files.write(files.bin(id, 0), byteArrayOf(1))
        }
        db.mediaDao().insertAll(ids.map { item(it) })
        val cancelled = mutableListOf<String>()
        var caller: Job? = null
        val repo = repoCancelledInsideTransaction({ caller!! }, cancelled)

        caller = launch { repo.clearThread("alice") }
        caller.join()

        assertThat(db.dao().idsForPeer("alice")).isEmpty()
        assertThat(cancelled).containsExactlyElementsIn(ids)
        ids.forEach { assertThat(files.dir(it).exists()).isFalse() }
    }

    @Test
    fun `deleteMessage cancelled while its transaction runs still cancels the upload and deletes the folder`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.mediaDao().insertAll(listOf(item("m1")))
        files.write(files.bin("m1", 0), byteArrayOf(1))
        val cancelled = mutableListOf<String>()
        var caller: Job? = null
        val repo = repoCancelledInsideTransaction({ caller!! }, cancelled)

        caller = launch { repo.deleteMessage("m1") }
        caller.join()

        assertThat(db.dao().getById("m1")).isNull()
        assertThat(cancelled).containsExactly("m1")
        assertThat(files.dir("m1").exists()).isFalse()
    }
}
