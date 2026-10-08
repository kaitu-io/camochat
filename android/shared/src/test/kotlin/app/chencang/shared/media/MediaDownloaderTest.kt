package app.chencang.shared.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.MediaItemDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.chencang.MediaBlob
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class CountingDecrypt : MediaCrypto {
    var decrypts = 0
    override fun encrypt(plaintext: ByteArray, kind: Int): MediaBlob = UniffiMediaCrypto.encrypt(plaintext, kind)
    override fun decrypt(blob: ByteArray, blobSecret: ByteArray, expectedKind: Int): ByteArray {
        decrypts++
        return UniffiMediaCrypto.decrypt(blob, blobSecret, expectedKind)
    }
}

@RunWith(RobolectricTestRunner::class)
class MediaDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: ChatDatabase
    private lateinit var server: MockWebServer
    private lateinit var files: MediaFiles
    private lateinit var crypto: CountingDecrypt
    private var clock = 10_000L

    @Before
    fun setUp() {
        uniffi.chencang.encodeWire(byteArrayOf())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        files = MediaFiles(File(tmp.root, "media"))
        crypto = CountingDecrypt()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun downloader() = MediaDownloader(
        dao = db.dao(),
        mediaDao = db.mediaDao(),
        files = files,
        crypto = crypto,
        transport = MediaTransport(
            relays = relaysOf(server),
            readTimeoutMs = 5_000,
        ),
        now = { clock },
    )

    /** 造一条收到的媒体消息：真加密出 blob，库里存对应 secret/blob_id/byte_len，返回 (blob, plaintext)。 */
    private suspend fun incoming(
        id: String,
        kind: Int = MediaConstants.KIND_IMAGE,
        sentAt: Long = 0L,
        peer: String = "alice",
    ): Pair<MediaBlob, ByteArray> {
        val plain = ByteArray(4_000) { (it * 7 + id.length).toByte() }
        val blob = UniffiMediaCrypto.encrypt(plain, kind)
        db.mediaDao().insertAll(
            listOf(
                MediaItem(
                    messageId = id, index = 0, kind = kind, durMs = if (kind == 2) 0 else 3_000,
                    width = 10, height = 10, byteLen = blob.blob.size.toLong(),
                    blobSecret = blob.blobSecret, blobId = blob.blobId, state = MediaItem.STATE_PENDING,
                ),
            ),
        )
        db.dao().insert(
            ChatMessage(
                id = id, peerUsername = peer, direction = ChatMessage.DIRECTION_IN, body = "[图片]",
                timestamp = sentAt, kind = ChatMessage.kindForMedia(kind),
            ),
        )
        return blob to plain
    }

    @Test
    fun `happy path writes bin, deletes cca and marks ready`() = runTest {
        val (blob, plain) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))

        assertThat(downloader().download("m1", 0)).isNull()

        assertThat(server.takeRequest().path).isEqualTo("/b/${blob.blobId}")
        val item = db.mediaDao().get("m1", 0)!!
        assertThat(item.state).isEqualTo(MediaItem.STATE_READY)
        assertThat(File(item.localPath!!).readBytes()).isEqualTo(plain)
        assertThat(files.cca("m1", 0).exists()).isFalse()
    }

    @Test
    fun `exactly 24h after the timestamp is expired and sends no request`() = runTest {
        incoming("m1", sentAt = 0L)
        clock = 86_400_000L
        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.GONE)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_EXPIRED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `one millisecond before 24h still downloads`() = runTest {
        val (blob, _) = incoming("m1", sentAt = 0L)
        clock = 86_399_999L
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        assertThat(downloader().download("m1", 0)).isNull()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    // ---- 先分享、后上传 spec §2：403/404 按本地收到时间区分「等待对方上传」与「已过期」----

    @Test
    fun `404 and 403 within 24h of receipt are awaiting, not expired`() = runTest {
        incoming("m1")
        incoming("m2")
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        val d = downloader()
        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.GONE)
        assertThat(d.download("m2", 0)).isEqualTo(MediaFailure.GONE)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(db.mediaDao().get("m2", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
    }

    @Test
    fun `a 403 that lands at 24h after receipt is expired`() = runTest {
        incoming("m1", sentAt = 0L)
        clock = 86_399_999L // 本地预判还没到期，发了请求
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                clock = 86_400_000L // 响应回来时已满 24 h
                return MockResponse().setResponseCode(403)
            }
        }
        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.GONE)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_EXPIRED)
    }

    @Test
    fun `an awaiting item can be fetched again and becomes ready`() = runTest {
        val (blob, plain) = incoming("m1")
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        val d = downloader()
        d.download("m1", 0)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(d.download("m1", 0)).isNull()
        val item = db.mediaDao().get("m1", 0)!!
        assertThat(item.state).isEqualTo(MediaItem.STATE_READY)
        assertThat(File(item.localPath!!).readBytes()).isEqualTo(plain)
    }

    @Test
    fun `an awaiting item past 24h is expired locally without any request`() = runTest {
        incoming("m1", sentAt = 0L)
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        clock = 86_400_000L
        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.GONE)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_EXPIRED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an awaiting refetch stays awaiting while the relay still answers 403`() = runTest {
        incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        val seen = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += runBlocking { db.mediaDao().get("m1", 0)!!.state }
                return MockResponse().setResponseCode(403)
            }
        }
        downloader().download("m1", 0)
        assertThat(seen).containsExactly(MediaItem.STATE_AWAITING) // 请求期间不闪成「下载中」
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
    }

    @Test
    fun `an awaiting refetch switches to downloading on the first progress callback`() = runTest {
        val (blob, _) = incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        server.enqueue(
            MockResponse().setBody(Buffer().write(blob.blob)).throttleBody(512, 50, TimeUnit.MILLISECONDS),
        )
        val d = downloader()
        val job = launch(Dispatchers.Default) { d.download("m1", 0) }
        db.mediaDao().observeForPeer("alice").first { items -> items.any { it.state == MediaItem.STATE_DOWNLOADING } }
        job.join()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    /** 连不上的中转（本机 1 号端口拒绝连接）→ NETWORK。 */
    private fun unreachableDownloader(readTimeoutMs: Int = 5_000, origin: String = "http://127.0.0.1:1") =
        MediaDownloader(
            dao = db.dao(),
            mediaDao = db.mediaDao(),
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(origin),
                readTimeoutMs = readTimeoutMs,
            ),
            now = { clock },
        )

    @Test
    fun `while awaiting, network, 5xx and 429 errors keep it awaiting silently`() = runTest {
        val (blob, _) = incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        assertThat(unreachableDownloader().download("m1", 0)).isEqualTo(MediaFailure.NETWORK)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)

        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        val d = downloader()
        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.SERVER)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.RATE_LIMITED)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(d.download("m1", 0)).isNull()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    @Test
    fun `an awaiting download failing midway falls back to awaiting`() = runTest {
        val (blob, _) = incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        // 头和第一块马上到（→ 切到下载中），之后卡住超过读超时 → 中途断网。
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)).throttleBody(1_024, 2, TimeUnit.SECONDS))
        val d = unreachableDownloader(readTimeoutMs = 500, origin = origin(server))
        val seen = mutableSetOf<String>()
        val watch = launch(Dispatchers.Default) {
            db.mediaDao().observeForPeer("alice").collect { items -> items.forEach { seen += it.state } }
        }
        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.NETWORK)
        watch.cancelAndJoin()
        assertThat(seen).contains(MediaItem.STATE_DOWNLOADING)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
    }

    @Test
    fun `a first fetch hitting a network error is still failed`() = runTest {
        incoming("m1")
        assertThat(unreachableDownloader().download("m1", 0)).isEqualTo(MediaFailure.NETWORK)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
    }

    @Test
    fun `a second fetch of an awaiting item already in flight is dropped`() = runTest {
        val (blob, _) = incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val d = downloader()
        val job = launch(Dispatchers.Default) { d.download("m1", 0) }
        d.busy.first { "m1:0" in it }
        assertThat(d.download("m1", 0)).isNull()
        job.join()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    @Test
    fun `recoverInterrupted leaves awaiting items alone across a cold start`() = runTest {
        incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        downloader().recoverInterrupted("alice")
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
    }

    @Test
    fun `expireStale also expires awaiting items older than 24h without any request`() = runTest {
        incoming("old", sentAt = 0L)
        incoming("fresh", sentAt = 50_000L)
        db.mediaDao().updateState("old", 0, MediaItem.STATE_AWAITING)
        db.mediaDao().updateState("fresh", 0, MediaItem.STATE_AWAITING)
        clock = 86_400_000L
        downloader().expireStale("alice")
        assertThat(db.mediaDao().get("old", 0)!!.state).isEqualTo(MediaItem.STATE_EXPIRED)
        assertThat(db.mediaDao().get("fresh", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `autoDownload leaves awaiting items to the poller`() = runTest {
        incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        downloader().autoDownload("alice")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `server error leaves the item failed and a later retry succeeds`() = runTest {
        val (blob, _) = incoming("m1")
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        val d = downloader()
        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.SERVER)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(d.download("m1", 0)).isNull()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    @Test
    fun `a 200 with the wrong length is a network failure, retryable, without decrypting`() = runTest {
        // 终审 F3：长度不符是传输问题，不是终态「文件已损坏」——点重试能再取。
        val (blob, _) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob.copyOf(blob.blob.size - 1))))
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        val d = downloader()
        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.NETWORK)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(crypto.decrypts).isEqualTo(0)
        assertThat(files.bin("m1", 0).exists()).isFalse()
        assertThat(d.download("m1", 0)).isNull()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    @Test
    fun `while awaiting, a 200 with the wrong length keeps it awaiting silently`() = runTest {
        val (blob, _) = incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_AWAITING)
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob.copyOf(blob.blob.size - 1))))
        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.NETWORK)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_AWAITING)
    }

    @Test
    fun `tampered blob is corrupt and leaves no bin`() = runTest {
        val (blob, _) = incoming("m1")
        val bad = blob.blob.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x01).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bad)))
        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.CORRUPT)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_CORRUPT)
        assertThat(files.bin("m1", 0).exists()).isFalse()
        assertThat(files.cca("m1", 0).exists()).isFalse()
    }

    @Test
    fun `autoDownload fetches voice and images, skips video, never more than two at once`() = runTest {
        val blobs = mutableMapOf<String, ByteArray>()
        for (i in 1..4) {
            val (blob, _) = incoming("img$i")
            blobs[blob.blobId] = blob.blob
        }
        val (voice, _) = incoming("voice", kind = MediaConstants.KIND_VOICE)
        blobs[voice.blobId] = voice.blob
        incoming("video", kind = MediaConstants.KIND_VIDEO)

        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(200)
                active.decrementAndGet()
                val id = request.path!!.removePrefix("/b/")
                return MockResponse().setBody(Buffer().write(blobs.getValue(id)))
            }
        }

        downloader().autoDownload("alice")

        assertThat(maxActive.get()).isAtMost(2)
        assertThat(server.requestCount).isEqualTo(5)
        for (id in listOf("img1", "img2", "img3", "img4", "voice")) {
            assertThat(db.mediaDao().get(id, 0)!!.state).isEqualTo(MediaItem.STATE_READY)
        }
        assertThat(db.mediaDao().get("video", 0)!!.state).isEqualTo(MediaItem.STATE_PENDING)
    }

    @Test
    fun `cancelling a download releases its busy key`() = runTest {
        val (blob, _) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)).setHeadersDelay(1, TimeUnit.SECONDS))
        val d = downloader()
        val job = launch(Dispatchers.Default) { d.download("m1", 0) }
        d.busy.first { "m1:0" in it }
        job.cancelAndJoin()
        assertThat(d.busy.value).doesNotContain("m1:0")
    }

    @Test
    fun `a download cancelled mid-transfer goes back to pending, not stuck in downloading`() = runTest {
        val (blob, _) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)).setHeadersDelay(1, TimeUnit.SECONDS))
        val d = downloader()
        val job = launch(Dispatchers.Default) { d.download("m1", 0) }
        // 等到真的进了传输（条目已落成 downloading），再取消——模拟离开线程 / 界面效应重启。
        db.mediaDao().observeForPeer("alice").first { items -> items.any { it.state == MediaItem.STATE_DOWNLOADING } }
        job.cancelAndJoin()
        val item = db.mediaDao().get("m1", 0)!!
        assertThat(item.state).isEqualTo(MediaItem.STATE_PENDING)
        assertThat(d.busy.value).doesNotContain("m1:0")
        // 放回 pending 后下一次下载能接上。
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        assertThat(d.download("m1", 0)).isNull()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    @Test
    fun `expireStale marks old pending videos expired without any request`() = runTest {
        incoming("old", kind = MediaConstants.KIND_VIDEO, sentAt = 0L)
        incoming("fresh", kind = MediaConstants.KIND_VIDEO, sentAt = 50_000L)
        clock = 86_400_000L
        downloader().expireStale("alice")
        assertThat(db.mediaDao().get("old", 0)!!.state).isEqualTo(MediaItem.STATE_EXPIRED)
        assertThat(db.mediaDao().get("fresh", 0)!!.state).isEqualTo(MediaItem.STATE_PENDING)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `recoverInterrupted puts a stale downloading item back to pending`() = runTest {
        incoming("m1")
        db.mediaDao().updateState("m1", 0, MediaItem.STATE_DOWNLOADING)
        downloader().recoverInterrupted("alice")
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_PENDING)
    }

    @Test
    fun `no space for the plaintext is STORAGE_FULL and the item can be retried`() = runTest {
        val (blob, _) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        files = MediaFiles(File(tmp.root, "media-full")) { 0L }
        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.STORAGE_FULL)
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
    }

    // ---- 下载途中消息被删（controller 裁决，mirrors iOS/MediaSender）----

    @Test
    fun `message deleted right after the transport call is abandoned without decrypting or writing anything`() = runTest {
        val (blob, _) = incoming("m1")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 传输调用完成的这一刻,用户长按删掉了这条消息(两张表都没了)。
                runBlocking {
                    db.mediaDao().deleteForMessage("m1")
                    db.dao().deleteById("m1")
                }
                return MockResponse().setBody(Buffer().write(blob.blob))
            }
        }

        assertThat(downloader().download("m1", 0)).isEqualTo(MediaFailure.DELETED)

        assertThat(crypto.decrypts).isEqualTo(0)
        assertThat(files.bin("m1", 0).exists()).isFalse()
        assertThat(files.dir("m1").exists()).isFalse()
    }

    @Test
    fun `message deleted right after decrypt cleans up the cca and writes no bin or rows`() = runTest {
        val (blob, _) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        val deletingCrypto = object : MediaCrypto {
            override fun encrypt(plaintext: ByteArray, kind: Int) = crypto.encrypt(plaintext, kind)
            override fun decrypt(blob: ByteArray, blobSecret: ByteArray, expectedKind: Int): ByteArray {
                val plain = crypto.decrypt(blob, blobSecret, expectedKind)
                // 解密成功的这一刻,用户长按删掉了这条消息(两张表都没了),但目录/`.cca`
                // 还在——落到 [MediaDownloader] 自己去清。
                runBlocking {
                    db.mediaDao().deleteForMessage("m1")
                    db.dao().deleteById("m1")
                }
                return plain
            }
        }
        val d = MediaDownloader(
            dao = db.dao(),
            mediaDao = db.mediaDao(),
            files = files,
            crypto = deletingCrypto,
            transport = MediaTransport(
                relays = relaysOf(server),
                readTimeoutMs = 5_000,
            ),
            now = { clock },
        )

        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.DELETED)

        assertThat(crypto.decrypts).isEqualTo(1)
        assertThat(files.bin("m1", 0).exists()).isFalse()
        assertThat(files.dir("m1").exists()).isFalse()
    }

    @Test
    fun `a DB failure persisting ready leaves the cca on disk so a retry can recover`() = runTest {
        val (blob, plain) = incoming("m1")
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        // 只让 updateStateAndPath 炸,其余方法照常委托给真的 DAO——模拟「明文已经写完了,
        // 落库『已就绪』这一步却失败/被杀」这个窗口。
        val throwingDao = object : MediaItemDao by db.mediaDao() {
            override suspend fun updateStateAndPath(messageId: String, index: Int, state: String, localPath: String) {
                throw RuntimeException("boom")
            }
        }
        val d = MediaDownloader(
            dao = db.dao(),
            mediaDao = throwingDao,
            files = files,
            crypto = crypto,
            transport = MediaTransport(
                relays = relaysOf(server),
                readTimeoutMs = 5_000,
            ),
            now = { clock },
        )

        assertThat(d.download("m1", 0)).isEqualTo(MediaFailure.NOT_READY)

        // 明文已经落盘了(顺序是先写 .bin 再落库),但落库失败——密文必须还在,
        // 不然重试要么白重新下载一次,要么撞上中转对象已经过期。
        assertThat(files.bin("m1", 0).readBytes()).isEqualTo(plain)
        assertThat(files.cca("m1", 0).exists()).isTrue()
        assertThat(db.mediaDao().get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
    }
}
