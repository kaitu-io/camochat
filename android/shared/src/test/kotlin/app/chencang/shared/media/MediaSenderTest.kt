package app.chencang.shared.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.R
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatMessageDao
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.MediaItemDao
import app.chencang.shared.chat.WireLocator
import app.chencang.shared.chat.inTransactionRunner
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.i18n.UiText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.chencang.DecodedMessage
import uniffi.chencang.MediaBlob
import uniffi.chencang.MediaRef
import java.util.concurrent.atomic.AtomicInteger
import uniffi.chencang.decodeFrame
import uniffi.chencang.decodeWire
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class IdentityCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) =
        SessionCrypto.DecryptedAnyBytes(senderKey = "alice", plaintext = ciphertext)
}

/** 包住真 uniffi 实现，只数调用次数——「重试不重新加密」靠它断言。 */
private class CountingCrypto(private val inner: MediaCrypto = UniffiMediaCrypto) : MediaCrypto {
    var encrypts = 0
    /** 加密进行中注入的动作（如「用户此刻删了这条消息」）。 */
    var onEncrypt: () -> Unit = {}
    override fun encrypt(plaintext: ByteArray, kind: Int): MediaBlob {
        encrypts++
        onEncrypt()
        return inner.encrypt(plaintext, kind)
    }
    override fun decrypt(blob: ByteArray, blobSecret: ByteArray, expectedKind: Int) =
        inner.decrypt(blob, blobSecret, expectedKind)
}

@RunWith(RobolectricTestRunner::class)
class MediaSenderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: ChatDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: ChatRepository
    private lateinit var crypto: CountingCrypto

    /** 记录型上传调度：seal/retry 交给上传引擎的 messageId，按调用顺序。 */
    private val scheduled = mutableListOf<String>()

    /** 记录型「现在就试」：已分享消息的手动重试交给上传引擎（`enqueueNow`，不等退避）的 messageId。 */
    private val rescheduledNow = mutableListOf<String>()

    @Before
    fun setUp() {
        uniffi.chencang.encodeWire(byteArrayOf())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        crypto = CountingCrypto()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun sender(
        files: MediaFiles = MediaFiles(File(tmp.root, "media")),
        transport: MediaTransport = MediaTransport(
            relays = relaysOf(server),
        ),
        now: () -> Long = System::currentTimeMillis,
        sealFrame: (suspend (String, List<MediaRef>) -> String)? = null,
    ): MediaSender {
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        return MediaSender(
            dao = db.dao(),
            mediaDao = db.mediaDao(),
            files = files,
            crypto = crypto,
            transport = transport,
            sealFrame = sealFrame ?: { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            now = now,
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
        )
    }

    private fun prepared(kind: Int = MediaConstants.KIND_IMAGE, size: Int = 1_000, name: String = "p${System.nanoTime()}") =
        PreparedMedia(
            kind = kind,
            file = tmp.newFile(name).apply { writeBytes(ByteArray(size) { (it * 31).toByte() }) },
            durMs = if (kind == MediaConstants.KIND_IMAGE) 0 else 12_000,
            width = if (kind == MediaConstants.KIND_VOICE) 0 else 640,
            height = if (kind == MediaConstants.KIND_VOICE) 0 else 480,
        )

    private fun enqueueSigner() =
        server.enqueue(MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}"""))

    @Test
    fun `send encrypts, seals and composes the R1 share text, then upload signs and PUTs`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val s = sender()

        val result = s.send("alice", listOf(prepared(size = 1_000))) as MediaSender.Result.Sealed
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(s.upload(result.messageId)).isEqualTo(UploadOutcome.Done)

        val signReq = server.takeRequest()
        val item = db.mediaDao().forMessage(result.messageId).single()
        assertThat(signReq.path).isEqualTo("/api/upload?blob_id=${item.blobId}&byte_len=1050&kind=2")
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.bodySize).isEqualTo(1_050L)

        assertThat(item.state).isEqualTo(MediaItem.STATE_SEALED)
        assertThat(item.byteLen).isEqualTo(1_050L)
        assertThat(item.blobSecret.size).isEqualTo(32)
        assertThat(File(item.localPath!!).exists()).isTrue()

        val msg = db.dao().getById(result.messageId)!!
        assertThat(msg.kind).isEqualTo(ChatMessage.KIND_IMAGE)
        assertThat(msg.body).isEqualTo("")
        assertThat(msg.direction).isEqualTo(ChatMessage.DIRECTION_OUT)
        assertThat(msg.shareText).isEqualTo(result.shareText)

        assertThat(result.summary).isEqualTo(UiText.Res(R.string.media_preview_image))
        val lines = result.shareText.split("\n")
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo("MH:2:1:${item.blobId}")
        assertThat(WireLocator.extract(result.shareText)).isEqualTo(lines[1])
        val refs = (decodeFrame(decodeWire(lines[1])) as DecodedMessage.Media).refs
        assertThat(refs.single().blobSecret).isEqualTo(item.blobSecret)
        assertThat(refs.single().byteLen.toLong()).isEqualTo(1_050L)
        assertThat(refs.single().width.toInt()).isEqualTo(640)
    }

    @Test
    fun `cca is deleted after upload and bin (sender plaintext) is kept`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(files.cca(r.messageId, 0).exists()).isTrue() // 分享后、上传前 .cca 必须还在
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)
        assertThat(files.cca(r.messageId, 0).exists()).isFalse()
        assertThat(files.bin(r.messageId, 0).exists()).isTrue()
    }

    @Test
    fun `a second upload attempt re-presigns and PUTs the same cca without calling encryptMediaBlob again`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(500))
        val s = sender()

        val first = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(first.messageId)).isEqualTo(UploadOutcome.Retryable(MediaFailure.SERVER))
        val failedItem = db.mediaDao().forMessage(first.messageId).single()
        assertThat(failedItem.state).isEqualTo(MediaItem.STATE_UPLOADING)
        server.takeRequest() // signer
        val firstPut = server.takeRequest().body.readByteArray()
        assertThat(crypto.encrypts).isEqualTo(1)

        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val second = s.upload(first.messageId)

        assertThat(second).isEqualTo(UploadOutcome.Done)
        assertThat(crypto.encrypts).isEqualTo(1)
        val sign2 = server.takeRequest()
        assertThat(sign2.path).contains("blob_id=${failedItem.blobId}&")
        assertThat(server.takeRequest().body.readByteArray()).isEqualTo(firstPut)
        assertThat(db.mediaDao().forMessage(first.messageId).single().blobId).isEqualTo(failedItem.blobId)
    }

    @Test
    fun `429 from the signer is a retryable RATE_LIMITED and leaves the item uploading`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val s = sender()
        val r = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Retryable(MediaFailure.RATE_LIMITED))
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
    }

    @Test
    fun `no space for the cca is STORAGE_FULL`() = runTest {
        val r = sender(MediaFiles(File(tmp.root, "media")) { 0L }).send("alice", listOf(prepared())) as MediaSender.Result.Failed
        assertThat(r.failure).isEqualTo(MediaFailure.STORAGE_FULL)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `three images upload one by one and seal into a single frame`() = runTest {
        repeat(3) {
            enqueueSigner()
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val s = sender()
        val r = s.send("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Sealed
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)
        assertThat(server.requestCount).isEqualTo(6)
        assertThat(db.mediaDao().forMessage(r.messageId).map { it.state }.toSet()).containsExactly(MediaItem.STATE_SEALED)
        val firstBlobId = db.mediaDao().forMessage(r.messageId).first().blobId
        assertThat(r.shareText.lines().first()).isEqualTo("MH:2:3:$firstBlobId")
        assertThat(r.summary).isEqualTo(UiText.Plural(R.plurals.media_preview_images, 3))
        val refs = (decodeFrame(decodeWire(r.shareText.lines()[1])) as DecodedMessage.Media).refs
        assertThat(refs).hasSize(3)
        assertThat(db.dao().getById(r.messageId)!!.body).isEqualTo("")
    }

    @Test
    fun `recoverInterrupted leaves a shared message's uploading items to the upload engine`() = runTest {
        // 「上传到一半离开线程/进程被杀」：上传失败后项停在 uploading，busy 里已经没有它。
        // 旧逻辑会把它打成 failed（红 `!`）；现在它归 WorkManager 管，进线程不能动它（Review Focus 2）。
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(500))
        val s = sender()
        val r = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isInstanceOf(UploadOutcome.Retryable::class.java)
        assertThat(s.busy.value).doesNotContain(r.messageId)

        s.recoverInterrupted("alice")
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)

        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_SEALED)
        assertThat(crypto.encrypts).isEqualTo(1)
    }

    @Test
    fun `recoverInterrupted still fails items whose seal was interrupted, and leaves shared failed items as they are`() = runTest {
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
                    id = id, peerUsername = "alice", direction = ChatMessage.DIRECTION_OUT, body = "",
                    timestamp = 1L, kind = ChatMessage.KIND_IMAGE, shareText = share,
                ),
            )
        }
        // 加密途中被杀：encrypting；加密完、封帧前被杀：uploading 但没有分享文本。
        insert("unsealed", null, MediaItem.STATE_ENCRYPTING, MediaItem.STATE_UPLOADING)
        insert("shared", "🔒 share", MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING, MediaItem.STATE_FAILED)
        val s = sender()

        s.recoverInterrupted("alice")

        assertThat(db.mediaDao().forMessage("unsealed").map { it.state })
            .containsExactly(MediaItem.STATE_FAILED, MediaItem.STATE_FAILED).inOrder()
        assertThat(db.mediaDao().forMessage("shared").map { it.state })
            .containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING, MediaItem.STATE_FAILED).inOrder()
    }

    @Test
    fun `when one image of an album fails with a retryable error, the uploaded one is sealed and the rest stay uploading`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(500)) // 第二张的签名就失败
        val s = sender()
        val r = s.send("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Retryable(MediaFailure.SERVER))
        val states = db.mediaDao().forMessage(r.messageId).map { it.state }
        assertThat(states).containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING, MediaItem.STATE_UPLOADING).inOrder()

        s.markUploadFailed(r.messageId)
        val after = db.mediaDao().forMessage(r.messageId).map { it.state }
        assertThat(after).containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_FAILED, MediaItem.STATE_FAILED).inOrder()
    }

    @Test
    fun `deleting the message during its upload leaves it deleted and does not crash`() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    // 用户在上传进行中长按「删除」
                    runBlocking { db.dao().idsForPeer("alice").forEach { repo.deleteMessage(it) } }
                    MockResponse().setResponseCode(200)
                }
        }

        val r = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.DELETED))
        assertThat(db.dao().getById(r.messageId)).isNull()
        assertThat(db.mediaDao().forMessage(r.messageId)).isEmpty()
        assertThat(files.dir(r.messageId).exists()).isFalse()
        assertThat(s.busy.value).isEmpty()
    }

    @Test
    fun `forward re-encrypts from the plaintext bin with a new secret for another peer`() = runTest {
        repeat(2) {
            enqueueSigner()
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val s = sender()
        val original = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed

        val fwd = s.forward(original.messageId, "bob", null) as MediaSender.Result.Sealed

        assertThat(crypto.encrypts).isEqualTo(2)
        val a = db.mediaDao().forMessage(original.messageId).single()
        val b = db.mediaDao().forMessage(fwd.messageId).single()
        assertThat(b.blobId).isNotEqualTo(a.blobId)
        assertThat(b.blobSecret).isNotEqualTo(a.blobSecret)
        assertThat(db.dao().getById(fwd.messageId)!!.peerUsername).isEqualTo("bob")
        assertThat(File(a.localPath!!).exists()).isTrue() // 原件不动
    }

    @Test
    fun `forward with one index sends only that item's content`() = runTest {
        // 原发 3 项 + 转发 1 项 = 4 组 signer+PUT
        repeat(4) { enqueueSigner(); server.enqueue(MockResponse().setResponseCode(200)) }
        val s = sender()
        val original = s.send("alice", listOf(prepared(size = 1_000), prepared(size = 2_000), prepared(size = 3_000))) as MediaSender.Result.Sealed

        val fwd = s.forward(original.messageId, "bob", listOf(1)) as MediaSender.Result.Sealed

        val items = db.mediaDao().forMessage(fwd.messageId)
        assertThat(items).hasSize(1)
        assertThat(File(items.single().localPath!!).length()).isEqualTo(2_000L)
        // 原消息三张都还在
        assertThat(db.mediaDao().forMessage(original.messageId)).hasSize(3)
    }

    @Test
    fun `forward with null forwards every item and duplicate indices collapse`() = runTest {
        repeat(6) { enqueueSigner(); server.enqueue(MockResponse().setResponseCode(200)) }
        val s = sender()
        val original = s.send("alice", listOf(prepared(size = 1_000), prepared(size = 2_000))) as MediaSender.Result.Sealed

        val all = s.forward(original.messageId, "bob", null) as MediaSender.Result.Sealed
        assertThat(db.mediaDao().forMessage(all.messageId).map { File(it.localPath!!).length() }).containsExactly(1_000L, 2_000L).inOrder()

        val dup = s.forward(original.messageId, "bob", listOf(1, 0, 1)) as MediaSender.Result.Sealed
        assertThat(db.mediaDao().forMessage(dup.messageId).map { File(it.localPath!!).length() }).containsExactly(1_000L, 2_000L).inOrder()
    }

    @Test
    fun `forward with an out-of-range index throws and sends nothing`() = runTest {
        repeat(2) { enqueueSigner(); server.enqueue(MockResponse().setResponseCode(200)) }
        val s = sender()
        val original = s.send("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed
        val before = server.requestCount

        val ex = runCatching { s.forward(original.messageId, "bob", listOf(0, 2)) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { s.forward(original.messageId, "bob", listOf(-1)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(before)
        assertThat(db.dao().observeThread("bob").first()).isEmpty()
    }

    @Test
    fun `forward with an empty index list throws and sends nothing`() = runTest {
        repeat(1) { enqueueSigner(); server.enqueue(MockResponse().setResponseCode(200)) }
        val s = sender()
        val original = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        val before = server.requestCount

        val ex = runCatching { s.forward(original.messageId, "bob", emptyList()) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(before)
        assertThat(db.dao().observeThread("bob").first()).isEmpty()
    }

    // ---- 并发/失败纪律裁决（同一组件 iOS 复审裁决，Android 一并落实并补测）----

    @Test
    fun `sealed state is persisted in the DB before the cca file is deleted`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val files = MediaFiles(File(tmp.root, "media"))
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        var checkedSealed = false
        val spyMediaDao = object : MediaItemDao by db.mediaDao() {
            override suspend fun updateState(messageId: String, index: Int, state: String) {
                if (state == MediaItem.STATE_SEALED) {
                    // 断言在「状态落库」这一刻，`.cca` 还没被删——顺序必须是先落库再删文件。
                    assertThat(files.cca(messageId, index).exists()).isTrue()
                    checkedSealed = true
                }
                db.mediaDao().updateState(messageId, index, state)
            }
        }
        val s = MediaSender(
            dao = db.dao(),
            mediaDao = spyMediaDao,
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
        )

        val r = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)

        assertThat(checkedSealed).isTrue()
        assertThat(files.cca(r.messageId, 0).exists()).isFalse()
    }

    @Test
    fun `a DB write failure while sealing an item is not swallowed, marks the item failed, and propagates`() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        val throwingMediaDao = object : MediaItemDao by db.mediaDao() {
            override suspend fun updateSealedBlob(
                messageId: String,
                index: Int,
                blobSecret: ByteArray,
                blobId: String,
                byteLen: Long,
                state: String,
            ): Unit = throw IllegalStateException("boom")
        }
        val id = "fixed-id"
        val s = MediaSender(
            dao = db.dao(),
            mediaDao = throwingMediaDao,
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
            newId = { id },
        )

        val thrown = try {
            s.send("alice", listOf(prepared()))
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertThat(thrown).isNotNull()
        // 从没到过网络——DAO 写失败发生在加密落盘之前(先落库新 secret 再写 .cca)。
        assertThat(server.requestCount).isEqualTo(0)
        // 落库先于写文件：DAO 一炸，`.cca` 根本没被写过，磁盘上不会多出一份配着空
        // blob_id 的孤儿密文(同一组件 iOS 复审 Important 修复，首次加密路径同样适用)。
        assertThat(files.cca(id, 0).exists()).isFalse()
        assertThat(db.mediaDao().forMessage(id).single().state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(s.busy.value).doesNotContain(id)
    }

    @Test
    fun `before sharing, an item with a secret but a missing cca re-encrypts with a brand-new secret on retry`() = runTest {
        var sessionUp = false
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files) { peer, refs -> if (sessionUp) repo.sealMediaFrame(peer, refs) else error("no session") }
        val first = s.send("alice", listOf(prepared())) as MediaSender.Result.Failed
        assertThat(first.failure).isEqualTo(MediaFailure.SESSION_LOST)
        assertThat(db.dao().getById(first.messageId)!!.shareText).isNull() // 没分享过：允许重新加密
        val before = db.mediaDao().forMessage(first.messageId).single()
        assertThat(before.blobId).isNotEmpty()
        assertThat(before.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(files.cca(first.messageId, 0).exists()).isTrue()

        // 模拟 `.cca` 丢失（例如低存储清理），DB 里还记得旧 secret/blob_id。
        assertThat(files.cca(first.messageId, 0).delete()).isTrue()

        sessionUp = true
        val second = s.retry(first.messageId) as MediaSender.Result.Sealed

        assertThat(crypto.encrypts).isEqualTo(2) // 重新加密了，不是复用旧密文
        val after = db.mediaDao().forMessage(second.messageId).single()
        assertThat(after.blobId).isNotEqualTo(before.blobId)
        assertThat(after.blobSecret).isNotEqualTo(before.blobSecret)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a retry for a message already in flight returns InProgress without sealing it twice`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sealCalls = AtomicInteger(0)
        val s = sender { peer, refs ->
            if (sealCalls.incrementAndGet() == 1) error("no session")
            started.countDown()
            release.await()
            repo.sealMediaFrame(peer, refs)
        }
        val first = s.send("alice", listOf(prepared())) as MediaSender.Result.Failed

        val firstRetryResult = java.util.concurrent.atomic.AtomicReference<MediaSender.Result>()
        val firstRetryThread = Thread { firstRetryResult.set(runBlocking { s.retry(first.messageId) }) }
        firstRetryThread.start()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

        val second = s.retry(first.messageId)

        assertThat(second).isEqualTo(MediaSender.Result.InProgress(first.messageId))
        release.countDown()
        firstRetryThread.join(5_000)
        assertThat(firstRetryThread.isAlive).isFalse()
        // 真正在跑的那次重试正常跑到底成功了；被拒的那次没有再封一次帧。
        assertThat(firstRetryResult.get()).isInstanceOf(MediaSender.Result.Sealed::class.java)
        assertThat(sealCalls.get()).isEqualTo(2)
        assertThat(scheduled).containsExactly(first.messageId)
        assertThat(s.busy.value).isEmpty()
    }

    @Test
    fun `sending a voice message composes the voice R1 caption`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val r = sender().send("alice", listOf(prepared(kind = MediaConstants.KIND_VOICE))) as MediaSender.Result.Sealed
        assertThat(r.summary).isEqualTo(UiText.Res(R.string.media_preview_voice))
        assertThat(r.shareText.lines().first()).startsWith("MH:1:1:")
    }

    @Test
    fun `sending a video message composes the video R1 caption`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val r = sender().send("alice", listOf(prepared(kind = MediaConstants.KIND_VIDEO))) as MediaSender.Result.Sealed
        assertThat(r.summary).isEqualTo(UiText.Res(R.string.media_preview_video))
        assertThat(r.shareText.lines().first()).startsWith("MH:3:1:")
    }

    // ---- 复审 fix round 1 补测 ----

    @Test
    fun `a DAO failure while persisting a re-encrypted secret leaves no orphan cca, and a working retry later produces a decryptable blob`() = runTest {
        var sessionUp = false
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files) { peer, refs -> if (sessionUp) repo.sealMediaFrame(peer, refs) else error("no session") }
        val plaintext = ByteArray(1_000) { (it * 31).toByte() }
        val first = s.send("alice", listOf(prepared(size = 1_000))) as MediaSender.Result.Failed
        val before = db.mediaDao().forMessage(first.messageId).single()
        assertThat(before.blobId).isNotEmpty()
        // 模拟 `.cca` 丢失(低存储清理)——这正是 ruling(4)/复审 Important 的重新加密路径（仅限未分享）。
        assertThat(files.cca(first.messageId, 0).delete()).isTrue()

        val throwingMediaDao = object : MediaItemDao by db.mediaDao() {
            override suspend fun updateSealedBlob(
                messageId: String,
                index: Int,
                blobSecret: ByteArray,
                blobId: String,
                byteLen: Long,
                state: String,
            ): Unit = throw IllegalStateException("boom")
        }
        val throwingSender = MediaSender(
            dao = db.dao(),
            mediaDao = throwingMediaDao,
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
        )
        val thrown = try {
            throwingSender.retry(first.messageId)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertThat(thrown).isNotNull()
        // 先落库再写文件：DAO 炸在落库这一步，`.cca` 根本没被重新写过。
        assertThat(files.cca(first.messageId, 0).exists()).isFalse()
        val stale = db.mediaDao().forMessage(first.messageId).single()
        assertThat(stale.blobId).isEqualTo(before.blobId) // 旧字段没被半更新
        assertThat(stale.blobSecret).isEqualTo(before.blobSecret)
        assertThat(server.requestCount).isEqualTo(0) // 没到过网络

        sessionUp = true
        s.retry(first.messageId) as MediaSender.Result.Sealed
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        assertThat(s.upload(first.messageId)).isEqualTo(UploadOutcome.Done)

        val after = db.mediaDao().forMessage(first.messageId).single()
        assertThat(after.blobId).isNotEqualTo(before.blobId)
        server.takeRequest() // 签名
        val uploadedBytes = server.takeRequest().body.readByteArray() // 真正落盘上传的密文
        val decrypted = crypto.decrypt(uploadedBytes, after.blobSecret, MediaConstants.KIND_IMAGE)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `send claims the message id before any DB row exists, so recoverInterrupted cannot race it`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val files = MediaFiles(File(tmp.root, "media"))
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        lateinit var senderRef: MediaSender
        var checkedBusyDuringInsert = false
        val spyDao = object : ChatMessageDao by db.dao() {
            override suspend fun insert(msg: ChatMessage) {
                assertThat(senderRef.busy.value).contains(msg.id)
                checkedBusyDuringInsert = true
                db.dao().insert(msg)
            }
        }
        val s = MediaSender(
            dao = spyDao,
            mediaDao = db.mediaDao(),
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
        )
        senderRef = s

        s.send("alice", listOf(prepared()))

        assertThat(checkedBusyDuringInsert).isTrue()
    }

    @Test
    fun `send wraps the item and message inserts in one transaction, so a mid-insert failure leaves neither`() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val id = "fixed-tx-id"
        val throwingDao = object : ChatMessageDao by db.dao() {
            override suspend fun insert(msg: ChatMessage): Unit = throw IllegalStateException("boom")
        }
        val s = MediaSender(
            dao = throwingDao,
            mediaDao = db.mediaDao(),
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { _, _ -> error("not reached") },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
            newId = { id },
        )

        val thrown = try {
            s.send("alice", listOf(prepared()))
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertThat(thrown).isNotNull()
        // 事务回滚：消息行插入失败，先前插入的媒体条目也不该留下孤儿记录。
        assertThat(db.mediaDao().forMessage(id)).isEmpty()
        assertThat(db.dao().getById(id)).isNull()
    }

    @Test
    fun `when marking items failed also fails, the original exception keeps propagating instead of being replaced`() = runTest {
        // 注：kotlinx.coroutines 跨挂起点传播异常时会做「stack trace recovery」，可能拿同类型
        // +同消息的新实例替换正在传播的异常对象——这层拷贝不搬 addSuppressed 挂的东西。这里能
        // 稳定断言到的、也是复审真正要保的契约是「不被顶替」：markUnsealedFailed 自己再炸一次，
        // 传出去的必须还是最初那个失败(first)，不能变成标记阶段的失败(second)。生产代码里的
        // `e.addSuppressed(marking)` 在不跨越这层拷贝的调用路径下仍然是把两个异常都留痕的正确
        // 写法（比如同一线程内非 suspend 的调用），只是这条特定契约在这条 suspend 调用链上没法
        // 用公共 API 稳定断言到。
        val files = MediaFiles(File(tmp.root, "media"))
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        val id = "double-fail-id"
        val doubleThrowingMediaDao = object : MediaItemDao by db.mediaDao() {
            override suspend fun updateSealedBlob(
                messageId: String,
                index: Int,
                blobSecret: ByteArray,
                blobId: String,
                byteLen: Long,
                state: String,
            ): Unit = throw IllegalStateException("first")
            override suspend fun updateState(messageId: String, index: Int, state: String): Unit =
                throw IllegalArgumentException("second")
            override suspend fun failUnsealed(messageId: String): Unit =
                throw IllegalArgumentException("second")
        }
        val s = MediaSender(
            dao = db.dao(),
            mediaDao = doubleThrowingMediaDao,
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
            newId = { id },
        )

        val thrown = try {
            s.send("alice", listOf(prepared()))
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).isEqualTo("first")
    }

    @Test
    fun `retry on an already fully sealed message reschedules nothing and never re-seals`() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        var sealCalls = 0
        val files = MediaFiles(File(tmp.root, "media"))
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        val s = MediaSender(
            dao = db.dao(),
            mediaDao = db.mediaDao(),
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> sealCalls++; repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
        )
        val first = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(first.messageId)).isEqualTo(UploadOutcome.Done)
        assertThat(sealCalls).isEqualTo(1)

        assertThat(s.retry(first.messageId)).isEqualTo(MediaSender.Result.Rescheduled(first.messageId))

        assertThat(sealCalls).isEqualTo(1) // 没有再走一次封帧，不占用额外棘轮步
        assertThat(db.dao().getById(first.messageId)!!.shareText).isEqualTo(first.shareText)
        assertThat(server.requestCount).isEqualTo(2) // 没有发起新的签名/上传请求
        assertThat(scheduled).containsExactly(first.messageId) // 全部已上传：不再交给上传引擎
        assertThat(rescheduledNow).isEmpty()
    }

    @Test
    fun `a partial staging failure during forward does not leave orphan staged files`() = runTest {
        repeat(3) {
            enqueueSigner()
            server.enqueue(MockResponse().setResponseCode(200))
        }
        var trackForward = false
        var forwardEnsureCalls = 0
        val mediaRoot = File(tmp.root, "media")
        val files = MediaFiles(mediaRoot) {
            if (!trackForward) {
                Long.MAX_VALUE
            } else {
                forwardEnsureCalls++
                if (forwardEnsureCalls <= 1) Long.MAX_VALUE else 0L
            }
        }
        val s = sender(files)
        val original = s.send("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Sealed
        trackForward = true

        val r = s.forward(original.messageId, "bob", null) as MediaSender.Result.Failed

        assertThat(r.failure).isEqualTo(MediaFailure.STORAGE_FULL)
        val staging = File(mediaRoot, "staging")
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `forward leaves no staged files behind on success either`() = runTest {
        repeat(2) {
            enqueueSigner()
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val mediaRoot = File(tmp.root, "media")
        val files = MediaFiles(mediaRoot)
        val s = sender(files)
        val original = s.send("alice", listOf(prepared())) as MediaSender.Result.Sealed

        s.forward(original.messageId, "bob", null) as MediaSender.Result.Sealed

        val staging = File(mediaRoot, "staging")
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    // ---- 先分享、后上传（2026-09-30 spec）----

    @Test
    fun sealReturnsShareTextWithoutAnyNetworkCall() = runTest {
        val s = sender()

        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(db.mediaDao().forMessage(r.messageId).map { it.state })
            .containsExactly(MediaItem.STATE_UPLOADING, MediaItem.STATE_UPLOADING)
        assertThat(r.shareText).isNotEmpty()
        assertThat(db.dao().getById(r.messageId)!!.shareText).isEqualTo(r.shareText)
        assertThat(s.isShared(r.messageId)).isTrue()
        assertThat(scheduled).containsExactly(r.messageId)
    }

    @Test
    fun uploadMarksSealedAndDeletesCca() = runTest {
        repeat(2) {
            enqueueSigner()
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)

        val items = db.mediaDao().forMessage(r.messageId)
        assertThat(items.map { it.state }).containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_SEALED)
        items.forEach { assertThat(files.cca(r.messageId, it.index).exists()).isFalse() }
        assertThat(db.dao().getById(r.messageId)!!.shareText).isEqualTo(r.shareText) // 分享文本不变
        assertThat(s.busy.value).isEmpty()
    }

    @Test
    fun upload412IsSuccess() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(412))
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)

        assertThat(server.takeRequest().path).startsWith("/api/upload?") // 每次尝试都重新 presign
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("*")
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_SEALED)
        assertThat(files.cca(r.messageId, 0).exists()).isFalse()
    }

    @Test
    fun uploadNetworkErrorIsRetryableAndKeepsCca() = runTest {
        // 连不上（端口 9 无人监听）：HttpURLConnection 的 IOException → NETWORK。不用 MockWebServer
        // 断连——HttpURLConnection 会对 GET 静默重试一次，再卡在空队列上等满读超时。
        val files = MediaFiles(File(tmp.root, "media"))
        val dead = MediaTransport(relays = relaysOf("http://127.0.0.1:9"), connectTimeoutMs = 500)
        val s = sender(files, transport = dead)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Retryable(MediaFailure.NETWORK))

        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(files.cca(r.messageId, 0).exists()).isTrue()
        assertThat(s.busy.value).isEmpty()
    }

    @Test
    fun uploadTooLargeIsPermanentAndMarksFailed() = runTest {
        server.enqueue(MockResponse().setResponseCode(413))
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.TOO_LARGE))

        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(files.cca(r.messageId, 0).exists()).isTrue() // 已分享：密文冻结，不删
    }

    @Test
    fun uploadOfAMissingMessageIsPermanentDeleted() = runTest {
        assertThat(sender().upload("no-such-message")).isEqualTo(UploadOutcome.Permanent(MediaFailure.DELETED))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun uploadMissingCcaIsPermanentAndNeverReencrypts() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        val before = db.mediaDao().forMessage(r.messageId).single()
        val encryptsBefore = crypto.encrypts
        assertThat(files.cca(r.messageId, 0).delete()).isTrue()

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.FILE_MISSING))

        val after = db.mediaDao().forMessage(r.messageId).single()
        assertThat(after.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(after.blobId).isEqualTo(before.blobId)
        assertThat(after.blobSecret).isEqualTo(before.blobSecret)
        assertThat(crypto.encrypts).isEqualTo(encryptsBefore)
        assertThat(server.requestCount).isEqualTo(0)

        // 用户再点重试也不能重新加密：`.cca` 不在就不交给上传引擎（不闪「上传中」），上传引擎照样判「文件丢失」。
        assertThat(s.retry(r.messageId)).isEqualTo(MediaSender.Result.Rescheduled(r.messageId))
        assertThat(rescheduledNow).isEmpty()
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.FILE_MISSING))
        assertThat(crypto.encrypts).isEqualTo(encryptsBefore)
        assertThat(files.cca(r.messageId, 0).exists()).isFalse()
    }

    @Test
    fun retryAfterShareOnlyReschedulesNeverReencrypts() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isInstanceOf(UploadOutcome.Retryable::class.java)
        s.markUploadFailed(r.messageId)
        val before = db.mediaDao().forMessage(r.messageId).single()
        assertThat(before.state).isEqualTo(MediaItem.STATE_FAILED)
        val encryptsBefore = crypto.encrypts
        val requestsBefore = server.requestCount

        assertThat(s.retry(r.messageId)).isEqualTo(MediaSender.Result.Rescheduled(r.messageId))

        assertThat(db.dao().getById(r.messageId)!!.shareText).isEqualTo(r.shareText)
        assertThat(crypto.encrypts).isEqualTo(encryptsBefore)
        // 手动重试走「现在就试」（不排在退避后面），不是 seal 之后那次 KEEP 入队
        assertThat(scheduled).containsExactly(r.messageId)
        assertThat(rescheduledNow).containsExactly(r.messageId)
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(server.requestCount).isEqualTo(requestsBefore) // retry 自己不发请求
        val after = db.mediaDao().forMessage(r.messageId).single()
        assertThat(after.blobId).isEqualTo(before.blobId)
        assertThat(after.blobSecret).isEqualTo(before.blobSecret)
        assertThat(files.cca(r.messageId, 0).exists()).isTrue()
    }

    @Test
    fun retryBeforeShareReencrypts() = runTest {
        var full = true
        val files = MediaFiles(File(tmp.root, "media")) { if (full) 0L else Long.MAX_VALUE }
        val s = sender(files)
        val first = s.seal("alice", listOf(prepared())) as MediaSender.Result.Failed
        assertThat(first.failure).isEqualTo(MediaFailure.STORAGE_FULL)
        assertThat(s.isShared(first.messageId)).isFalse()
        assertThat(scheduled).isEmpty()
        assertThat(crypto.encrypts).isEqualTo(1)

        full = false
        val second = s.retry(first.messageId) as MediaSender.Result.Sealed

        assertThat(crypto.encrypts).isEqualTo(2)
        assertThat(second.shareText).isNotEmpty()
        assertThat(s.isShared(first.messageId)).isTrue()
        assertThat(db.mediaDao().forMessage(first.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(files.cca(first.messageId, 0).exists()).isTrue()
        assertThat(scheduled).containsExactly(first.messageId)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun forwardSealsAndSchedulesTheNewMessageOnly() = runTest {
        val s = sender()
        val original = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed

        val fwd = s.forward(original.messageId, "bob", null) as MediaSender.Result.Sealed

        assertThat(scheduled).containsExactly(original.messageId, fwd.messageId).inOrder()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun aSessionLossWhileSealingMarksItemsFailedAndDoesNotSchedule() = runTest {
        val s = sender { _, _ -> error("no session") }
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Failed
        assertThat(r.failure).isEqualTo(MediaFailure.SESSION_LOST)
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun concurrentUploadsOfOneMessagePutEachItemOnce() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val puts = AtomicInteger(0)
        val s = sender()
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    started.countDown()
                    release.await()
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    puts.incrementAndGet()
                    MockResponse().setResponseCode(200)
                }
        }
        val firstOutcome = java.util.concurrent.atomic.AtomicReference<UploadOutcome>()
        val t = Thread { firstOutcome.set(runBlocking { s.upload(r.messageId) }) }
        t.start()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

        val secondOutcome = java.util.concurrent.atomic.AtomicReference<UploadOutcome>()
        val second = Thread { secondOutcome.set(runBlocking { s.upload(r.messageId) }) }.apply { start() }
        release.countDown()
        t.join(5_000)
        second.join(5_000)

        assertThat(t.isAlive).isFalse()
        assertThat(second.isAlive).isFalse() // 第二个调用者真的被唤醒并跑完了，没挂在等待上
        assertThat(firstOutcome.get()).isEqualTo(UploadOutcome.Done)
        assertThat(secondOutcome.get()).isEqualTo(UploadOutcome.Done)
        assertThat(puts.get()).isEqualTo(1) // 第二次等第一次放手后发现已上传，不重复 PUT
        assertThat(s.busy.value).isEmpty()
    }

    // ---- 复审 fix round 1 ----

    @Test
    fun aMissingCcaInTheMiddleOfAnAlbumFailsOnlyThatItemAndTheRestStillUpload() = runTest {
        repeat(2) {
            enqueueSigner()
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Sealed
        assertThat(files.cca(r.messageId, 1).delete()).isTrue()

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.FILE_MISSING))

        assertThat(db.mediaDao().forMessage(r.messageId).map { it.state })
            .containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_FAILED, MediaItem.STATE_SEALED).inOrder()
        assertThat(server.requestCount).isEqualTo(4) // 两组 签名+PUT：第 1、3 项
        assertThat(files.cca(r.messageId, 0).exists()).isFalse()
        assertThat(files.cca(r.messageId, 2).exists()).isFalse()
    }

    @Test
    fun a413InTheMiddleOfAnAlbumFailsOnlyThatItemAndKeepsItsCca() = runTest {
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(413)) // 第 2 项签名 413
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.TOO_LARGE))

        assertThat(db.mediaDao().forMessage(r.messageId).map { it.state })
            .containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_FAILED, MediaItem.STATE_SEALED).inOrder()
        assertThat(files.cca(r.messageId, 1).exists()).isTrue() // 已分享：密文冻结，不删
    }

    @Test
    fun markUploadFailedNeverDemotesAnItemSealedConcurrently() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        repo = ChatRepository(db.dao(), SessionManager(IdentityCrypto()), db.mediaDao(), files, db.inTransactionRunner(), shareHeaders = FakeShareHeaders)
        // 模拟竞态：markUploadFailed 读到「uploading」之后、写之前，另一个上传把第 0 项落成 sealed。
        var raceOnNextRead = false
        val racingMediaDao = object : MediaItemDao by db.mediaDao() {
            override suspend fun forMessage(messageId: String): List<MediaItem> {
                val snapshot = db.mediaDao().forMessage(messageId)
                if (raceOnNextRead) {
                    raceOnNextRead = false
                    db.mediaDao().updateState(messageId, 0, MediaItem.STATE_SEALED)
                }
                return snapshot
            }
        }
        val s = MediaSender(
            dao = db.dao(),
            mediaDao = racingMediaDao,
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
            ),
            sealFrame = { peer, refs -> repo.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduled += it },
            uploadNow = { rescheduledNow += it },
            shareHeaders = FakeShareHeaders,
        )
        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed
        raceOnNextRead = true

        s.markUploadFailed(r.messageId)
        // 实现若不走「先读」，竞态在它写完之后才发生（并发上传此刻才落 sealed）——同样必须以 sealed 收场。
        if (raceOnNextRead) db.mediaDao().updateState(r.messageId, 0, MediaItem.STATE_SEALED)

        assertThat(db.mediaDao().forMessage(r.messageId).map { it.state })
            .containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_FAILED).inOrder()
    }

    @Test
    fun aRetryThatLosesTheRaceToAConcurrentSealDoesNotSealTwice() = runTest {
        // M1：retry 先认领再看分享文本。第一个 retry 卡在封帧里时，第二个拿不到认领 → InProgress；
        // 第一个封完之后再 retry，读到的是已分享 → 不再封帧。
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sealCalls = AtomicInteger(0)
        val s = sender { peer, refs ->
            if (sealCalls.incrementAndGet() == 1) error("no session")
            started.countDown()
            release.await()
            repo.sealMediaFrame(peer, refs)
        }
        val first = s.send("alice", listOf(prepared())) as MediaSender.Result.Failed
        val t = Thread { runBlocking { s.retry(first.messageId) } }.apply { start() }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(s.retry(first.messageId)).isEqualTo(MediaSender.Result.InProgress(first.messageId))
        release.countDown()
        t.join(5_000)
        assertThat(t.isAlive).isFalse()
        val shareText = db.dao().getById(first.messageId)!!.shareText

        assertThat(s.retry(first.messageId)).isEqualTo(MediaSender.Result.Rescheduled(first.messageId))

        assertThat(sealCalls.get()).isEqualTo(2)
        assertThat(db.dao().getById(first.messageId)!!.shareText).isEqualTo(shareText)
    }

    @Test
    fun anUnreadableButPresentCcaIsRetryableReadFailedAndKeepsTheCca() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        val cca = files.cca(r.messageId, 0)
        val original = cca.readBytes()
        // 用同名目录顶替文件：exists() 为真，readBytes() 抛 IOException。
        assertThat(cca.delete()).isTrue()
        assertThat(cca.mkdir()).isTrue()

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Retryable(MediaFailure.READ_FAILED))
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(server.requestCount).isEqualTo(0)

        cca.delete()
        cca.writeBytes(original)
        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Done)
    }

    @Test
    fun legacyPartiallyUploadedMessageRetrySealsTheFrameWithoutReencryptingAndSchedulesTheRest() = runTest {
        // 旧版流程「先上传、后封帧」留下的数据：第 0 项已 sealed（.cca 已删、secret 在库里），
        // 第 1 项 failed 但 .cca 还在，share_text 为空。
        var sessionUp = false
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files) { peer, refs -> if (sessionUp) repo.sealMediaFrame(peer, refs) else error("no session") }
        val first = s.send("alice", listOf(prepared(), prepared())) as MediaSender.Result.Failed
        assertThat(db.dao().getById(first.messageId)!!.shareText).isNull()
        db.mediaDao().updateState(first.messageId, 0, MediaItem.STATE_SEALED)
        assertThat(files.cca(first.messageId, 0).delete()).isTrue()
        val stored = db.mediaDao().forMessage(first.messageId)
        val encryptsBefore = crypto.encrypts

        sessionUp = true
        val r = s.retry(first.messageId) as MediaSender.Result.Sealed

        assertThat(crypto.encrypts).isEqualTo(encryptsBefore)
        val refs = (decodeFrame(decodeWire(r.shareText.lines()[1])) as DecodedMessage.Media).refs
        assertThat(refs.map { it.blobSecret.toList() }).containsExactly(stored[0].blobSecret.toList(), stored[1].blobSecret.toList()).inOrder()
        assertThat(db.mediaDao().forMessage(first.messageId).map { it.state })
            .containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING).inOrder()
        assertThat(scheduled).containsExactly(first.messageId)
        assertThat(server.requestCount).isEqualTo(0)

        enqueueSigner()
        server.enqueue(MockResponse().setResponseCode(200))
        assertThat(s.upload(first.messageId)).isEqualTo(UploadOutcome.Done)
        assertThat(server.requestCount).isEqualTo(2) // 只传了剩下那一项
    }

    @Test
    fun uploadRejectedByTheRelayIsPermanentAndMarksFailedKeepingTheCca() = runTest {
        // 404/400 之类「请求本身不被接受」：重试多少次都一样，立即放弃（与 iOS 同口径）。
        server.enqueue(MockResponse().setResponseCode(404))
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.REJECTED))

        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(files.cca(r.messageId, 0).exists()).isTrue() // 已分享：密文冻结，不删
    }

    @Test
    fun uploadA408IsRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(408))
        val s = sender()
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Retryable(MediaFailure.SERVER))
        assertThat(db.mediaDao().forMessage(r.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
    }

    @Test
    fun aMessageDeletedWhileItsOnlyItemIsBeingEncryptedLeavesNoOrphanDirectory() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        crypto.onEncrypt = { runBlocking { db.dao().idsForPeer("alice").forEach { repo.deleteMessage(it) } } }

        val r = s.seal("alice", listOf(prepared()))

        assertThat(r).isInstanceOf(MediaSender.Result.Failed::class.java)
        assertThat((r as MediaSender.Result.Failed).failure).isEqualTo(MediaFailure.DELETED)
        assertThat(files.dir(r.messageId).exists()).isFalse() // 写 .cca 时重建出来的目录也要清掉
        assertThat(db.dao().getById(r.messageId)).isNull()
        assertThat(scheduled).isEmpty()
        assertThat(s.busy.value).isEmpty()
    }

    @Test
    fun aMessageDeletedWhileAnAlbumIsBeingEncryptedLeavesNoOrphanDirectory() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        crypto.onEncrypt = { runBlocking { db.dao().idsForPeer("alice").forEach { repo.deleteMessage(it) } } }

        val r = s.seal("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Failed

        assertThat(r.failure).isEqualTo(MediaFailure.DELETED)
        assertThat(files.dir(r.messageId).exists()).isFalse()
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun aMessageDeletedWhileTheFrameIsBeingSealedIsNotReportedSealedNorScheduled() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files) { peer, refs ->
            val wire = repo.sealMediaFrame(peer, refs)
            db.dao().idsForPeer("alice").forEach { repo.deleteMessage(it) }
            wire
        }

        val r = s.seal("alice", listOf(prepared()))

        assertThat(r).isInstanceOf(MediaSender.Result.Failed::class.java)
        assertThat((r as MediaSender.Result.Failed).failure).isEqualTo(MediaFailure.DELETED)
        assertThat(files.dir(r.messageId).exists()).isFalse()
        assertThat(scheduled).isEmpty()
    }

    @Test
    fun pendingUploadIdsListsOnlySharedOutgoingMessagesWithUnuploadedItems() = runTest {
        suspend fun insert(id: String, direction: String, share: String?, vararg states: String) {
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
                    id = id, peerUsername = "p-$id", direction = direction, body = "",
                    timestamp = 1L, kind = ChatMessage.KIND_IMAGE, shareText = share,
                ),
            )
        }
        val out = ChatMessage.DIRECTION_OUT
        insert("shared-uploading", out, "🔒 a", MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING)
        insert("shared-failed", out, "🔒 b", MediaItem.STATE_FAILED)
        insert("shared-done", out, "🔒 c", MediaItem.STATE_SEALED, MediaItem.STATE_SEALED)
        insert("unshared-failed", out, null, MediaItem.STATE_FAILED)
        insert("unshared-empty", out, "", MediaItem.STATE_UPLOADING)
        insert("incoming", ChatMessage.DIRECTION_IN, "🔒 d", MediaItem.STATE_FAILED)

        assertThat(sender().pendingUploadIds()).containsExactly("shared-uploading", "shared-failed")
    }

    @Test
    fun uploadSinceStartsWhenTheShareTextIsWrittenAndIsNullOnceDeleted() = runTest {
        var clock = 1_000L
        val s = sender(now = { clock })
        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed
        assertThat(db.mediaDao().forMessage(r.messageId).map { it.uploadSince }).containsExactly(1_000L, 1_000L)
        clock = 5_000L
        assertThat(s.uploadSince(r.messageId)).isEqualTo(1_000L)
        repo.deleteMessage(r.messageId)
        assertThat(s.uploadSince(r.messageId)).isNull()
    }

    @Test
    fun uploadSinceFallsBackToTheMessageTimeForRowsFromBeforeTheColumn() = runTest {
        val s = sender(now = { 7_000L })
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        db.openHelper.writableDatabase.execSQL("UPDATE media_item SET upload_since = NULL")
        assertThat(s.uploadSince(r.messageId)).isEqualTo(db.dao().getById(r.messageId)!!.timestamp)
    }

    @Test
    fun aManualRetryRestartsTheWindowOfRetryableItemsAndLeavesPermanentFailuresTerminal() = runTest {
        // N2a：6 小时上限从「进入上传中」算，手动重试重新起算。
        // 终审 F2 / spec §1.3：永久失败（413 / 被拒 / .cca 丢失）是终态——手动重试也不再碰它。
        var clock = 1_000L
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files, now = { clock })
        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed
        server.dispatcher = object : Dispatcher() {
            var puts = 0
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/api/upload")) {
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    MockResponse().setResponseCode(if (puts++ == 0) 413 else 500)
                }
        }
        assertThat(s.upload(r.messageId)).isInstanceOf(UploadOutcome.Retryable::class.java)
        s.markUploadFailed(r.messageId)

        clock = 9_000_000L
        assertThat(s.retry(r.messageId)).isEqualTo(MediaSender.Result.Rescheduled(r.messageId))

        val (tooLarge, retryable) = db.mediaDao().forMessage(r.messageId)
        assertThat(tooLarge.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(tooLarge.uploadFailure).isEqualTo(MediaFailure.TOO_LARGE.name)
        assertThat(retryable.state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(retryable.uploadFailure).isNull()
        assertThat(retryable.uploadSince).isEqualTo(9_000_000L)
        assertThat(rescheduledNow).containsExactly(r.messageId)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/api/upload")) {
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    MockResponse().setResponseCode(200)
                }
        }
        val before = server.requestCount
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.TOO_LARGE))
        assertThat(server.requestCount - before).isEqualTo(2) // 只签名 + PUT 了第 1 项
    }

    @Test
    fun aManualRetryOfAMessageWithOnlyPermanentFailuresSchedulesNothing() = runTest {
        server.enqueue(MockResponse().setResponseCode(413))
        val s = sender()
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.TOO_LARGE))

        s.retry(r.messageId)

        val item = db.mediaDao().forMessage(r.messageId).single()
        assertThat(item.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(item.uploadFailure).isEqualTo(MediaFailure.TOO_LARGE.name)
        assertThat(rescheduledNow).isEmpty()
    }

    @Test
    fun aRetryBeforeSharingStartsTheUploadWindowAtTheNewShare() = runTest {
        // N2a：封缄失败、6 小时后才点重试——窗口从这次分享起算，不从消息行创建时间。
        var clock = 1_000L
        var sessionUp = false
        val s = sender(now = { clock }) { peer, refs -> if (sessionUp) repo.sealMediaFrame(peer, refs) else error("no session") }
        val first = s.seal("alice", listOf(prepared())) as MediaSender.Result.Failed
        clock = 1_000L + 7 * 60 * 60 * 1000L
        sessionUp = true
        s.retry(first.messageId) as MediaSender.Result.Sealed
        assertThat(s.uploadSince(first.messageId)).isEqualTo(clock)
    }

    @Test
    fun permanentUploadFailuresPersistTheirReasonPerItem() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared(), prepared(), prepared())) as MediaSender.Result.Sealed
        assertThat(files.cca(r.messageId, 2).delete()).isTrue()
        // 第 0 项 413，第 1 项 404（REJECTED），第 2 项 .cca 丢了
        server.dispatcher = object : Dispatcher() {
            var puts = 0
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/api/upload")) {
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    MockResponse().setResponseCode(if (puts++ == 0) 413 else 404)
                }
        }

        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.TOO_LARGE))

        assertThat(db.mediaDao().forMessage(r.messageId).map { it.uploadFailure })
            .containsExactly(MediaFailure.TOO_LARGE.name, MediaFailure.REJECTED.name, MediaFailure.FILE_MISSING.name)
            .inOrder()
    }

    @Test
    fun aRetryableGiveUpLeavesNoReasonSoHealingStillPicksItUp() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val s = sender()
        val r = s.seal("alice", listOf(prepared())) as MediaSender.Result.Sealed
        assertThat(s.upload(r.messageId)).isInstanceOf(UploadOutcome.Retryable::class.java)
        s.markUploadFailed(r.messageId)
        val item = db.mediaDao().forMessage(r.messageId).single()
        assertThat(item.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(item.uploadFailure).isNull()
        assertThat(s.pendingUploadIds()).containsExactly(r.messageId)
    }

    @Test
    fun healingSkipsItemsThatFailedPermanentlyAndUploadDoesNotRetryThem() = runTest {
        // 相册：第 0 项 413（永久），第 1 项 500（可重试）。自愈只该再踢第 1 项，第 0 项不再签名。
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed
        server.dispatcher = object : Dispatcher() {
            var puts = 0
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/api/upload")) {
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    MockResponse().setResponseCode(if (puts++ == 0) 413 else 500)
                }
        }
        assertThat(s.upload(r.messageId)).isInstanceOf(UploadOutcome.Retryable::class.java)
        s.markUploadFailed(r.messageId)
        assertThat(s.pendingUploadIds()).containsExactly(r.messageId) // 第 1 项还能救

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/api/upload")) {
                    MockResponse().setBody("""{"url":"${server.url("/put/obj")}","expires_in":300}""")
                } else {
                    MockResponse().setResponseCode(200)
                }
        }
        val before = server.requestCount
        assertThat(s.upload(r.messageId)).isEqualTo(UploadOutcome.Permanent(MediaFailure.TOO_LARGE))
        assertThat(server.requestCount - before).isEqualTo(2) // 只签名 + PUT 了第 1 项
        assertThat(db.mediaDao().forMessage(r.messageId).map { it.state })
            .containsExactly(MediaItem.STATE_FAILED, MediaItem.STATE_SEALED).inOrder()
        assertThat(s.pendingUploadIds()).isEmpty() // 只剩永久失败的项：自愈不再理它
    }

    @Test
    fun aRetryOfASharedAlbumOnlyReschedulesItemsWhoseCcaIsStillThere() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        val s = sender(files)
        val r = s.seal("alice", listOf(prepared(), prepared())) as MediaSender.Result.Sealed
        s.markUploadFailed(r.messageId)
        assertThat(files.cca(r.messageId, 0).delete()).isTrue()

        assertThat(s.retry(r.messageId)).isEqualTo(MediaSender.Result.Rescheduled(r.messageId))

        val items = db.mediaDao().forMessage(r.messageId)
        assertThat(items.map { it.state }).containsExactly(MediaItem.STATE_FAILED, MediaItem.STATE_UPLOADING).inOrder()
        assertThat(items[0].uploadFailure).isEqualTo(MediaFailure.FILE_MISSING.name)
        assertThat(rescheduledNow).containsExactly(r.messageId)
        assertThat(s.ccaExists(r.messageId, 0)).isFalse()
        assertThat(s.ccaExists(r.messageId, 1)).isTrue()
    }
}
