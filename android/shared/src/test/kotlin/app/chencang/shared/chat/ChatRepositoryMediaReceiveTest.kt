package app.chencang.shared.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.media.FakeShareHeaders
import app.chencang.shared.media.MediaFiles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.chencang.DecodedMessage
import uniffi.chencang.MediaRef
import uniffi.chencang.decodeFrame
import uniffi.chencang.decodeWire
import uniffi.chencang.encodeMediaRefFrame
import uniffi.chencang.encodeWire
import uniffi.chencang.mediaBlobId
import java.io.File

/**
 * 恒等「加密」:密文 = 明文帧;decryptFromBytesAny 恒定说发件人是 alice。
 * 名字与 ChatRepositoryTest 里的同款桩不同:同包两个同名 private 顶层类会编译冲突。
 */
private class ReceiveIdentityCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) =
        SessionCrypto.DecryptedAnyBytes(senderKey = "alice", plaintext = ciphertext)
}

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryMediaReceiveTest {
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(ReceiveIdentityCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = MediaFiles(File(context.cacheDir, "media-recv")),
            inTransaction = db.inTransactionRunner(),
            now = { 5_000L },
            shareHeaders = FakeShareHeaders,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun items0(refs: List<MediaRef>) = mediaBlobId(refs.first().blobSecret)

    private fun ref(kind: Int, seed: Int, w: Int = 0, h: Int = 0, dur: Int = 0) = MediaRef(
        kind = kind.toUByte(),
        durMs = dur.toUShort(),
        width = w.toUShort(),
        height = h.toUShort(),
        byteLen = 123_456u,
        blobSecret = ByteArray(32) { seed.toByte() },
    )

    @Test
    fun `two-line image paste lands as an image message with one pending item`() = runTest {
        val r = ref(2, 7, w = 1920, h = 1080)
        val wire = encodeWire(encodeMediaRefFrame(listOf(r)))
        val pasted = "MH:2:1:${mediaBlobId(r.blobSecret)}\n$wire"

        val msg = repo.receiveWireText(pasted)!!

        assertThat(msg.peerUsername).isEqualTo("alice")
        assertThat(msg.direction).isEqualTo(ChatMessage.DIRECTION_IN)
        assertThat(msg.kind).isEqualTo(ChatMessage.KIND_IMAGE)
        assertThat(msg.body).isEqualTo("")
        // Copy gives back the full two lines, not only the wire line.
        assertThat(msg.shareText).isEqualTo(pasted)
        assertThat(msg.timestamp).isEqualTo(5_000L)
        val item = db.mediaDao().forMessage(msg.id).single()
        assertThat(item.state).isEqualTo(MediaItem.STATE_PENDING)
        assertThat(item.kind).isEqualTo(2)
        assertThat(item.width).isEqualTo(1920)
        assertThat(item.height).isEqualTo(1080)
        assertThat(item.byteLen).isEqualTo(123_456L)
        assertThat(item.blobSecret).isEqualTo(r.blobSecret)
        assertThat(item.blobId).isEqualTo(mediaBlobId(r.blobSecret))
        assertThat(item.localPath).isNull()
    }

    @Test
    fun `wire-only paste of a media frame still stores the full R1 two lines`() = runTest {
        val r = ref(1, 5, dur = 3_000)
        val wire = encodeWire(encodeMediaRefFrame(listOf(r)))
        val msg = repo.receiveWireText(wire)!!
        assertThat(msg.shareText).isEqualTo(
            "MH:1:1:${mediaBlobId(r.blobSecret)}\n$wire",
        )
    }

    @Test
    fun `a failing message insert rolls back the media items and surfaces the error`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failing = object : ChatMessageDao by db.dao() {
            override suspend fun insert(msg: ChatMessage): Unit = throw IllegalStateException("disk full")
        }
        val broken = ChatRepository(
            dao = failing,
            sessions = SessionManager(ReceiveIdentityCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = MediaFiles(File(context.cacheDir, "media-recv")),
            inTransaction = db.inTransactionRunner(),
            shareHeaders = FakeShareHeaders,
        )
        val wire = encodeWire(encodeMediaRefFrame(listOf(ref(2, 9, w = 10, h = 10), ref(2, 8, w = 10, h = 10))))

        var surfaced: Throwable? = null
        try {
            broken.receiveWireText(wire)
        } catch (e: IllegalStateException) {
            surfaced = e
        }

        assertThat(surfaced).isNotNull()
        assertThat(db.mediaDao().observeForPeer("alice").first()).isEmpty()
        assertThat(db.query("SELECT COUNT(*) FROM media_item", null).use { c -> c.moveToFirst(); c.getInt(0) }).isEqualTo(0)
    }

    @Test
    fun `nine refs make one message with nine ordered items`() = runTest {
        val refs = (0 until 9).map { ref(2, it + 1, w = 100, h = 100) }
        val msg = repo.receiveWireText(encodeWire(encodeMediaRefFrame(refs)))!!
        assertThat(msg.body).isEqualTo("")
        assertThat(msg.shareText).startsWith("MH:2:9:${items0(refs)}\n")
        val items = db.mediaDao().forMessage(msg.id)
        assertThat(items.map { it.index }).containsExactlyElementsIn(0..8).inOrder()
        assertThat(items.map { it.blobId }.toSet()).hasSize(9)
    }

    @Test
    fun `voice ref keeps its duration`() = runTest {
        val msg = repo.receiveWireText(encodeWire(encodeMediaRefFrame(listOf(ref(1, 3, dur = 12_345)))))!!
        assertThat(msg.kind).isEqualTo(ChatMessage.KIND_VOICE)
        assertThat(msg.body).isEqualTo("")
        assertThat(db.mediaDao().forMessage(msg.id).single().durMs).isEqualTo(12_345)
    }

    @Test
    fun `undecodable frame after a successful decrypt becomes the upgrade placeholder`() = runTest {
        // L2 header with an unknown msg_type 0x7F: decrypt "succeeds" (identity), decodeFrame throws.
        val wire = encodeWire(byteArrayOf(0xCC.toByte(), 0x10, 0x7F, 0x00, 0x01))
        val msg = repo.receiveWireText(wire)!!
        assertThat(msg.kind).isEqualTo(ChatMessage.KIND_UNSUPPORTED)
        assertThat(msg.body).isEqualTo("")
        assertThat(msg.peerUsername).isEqualTo("alice")
        assertThat(repo.observeThread("alice").first()).hasSize(1)
    }

    @Test
    fun `text with a wechat nickname line above still decrypts as text`() = runTest {
        val wire = repo.sendText("alice", "明早见")
        val msg = repo.receiveWireText("阿明:\n$wire")!!
        assertThat(msg.kind).isEqualTo(ChatMessage.KIND_TEXT)
        assertThat(msg.body).isEqualTo("明早见")
    }

    @Test
    fun `sealMediaFrame produces a wire that decodes back to the same refs`() = runTest {
        val refs = listOf(ref(3, 4, w = 1280, h = 720, dur = 9_000))
        val wire = repo.sealMediaFrame("alice", refs)
        assertThat(wire).startsWith("🔒")
        val decoded = decodeFrame(decodeWire(wire)) as DecodedMessage.Media
        assertThat(decoded.refs.single().durMs.toInt()).isEqualTo(9_000)
        assertThat(decoded.refs.single().blobSecret).isEqualTo(refs.single().blobSecret)
    }

    @Test
    fun `mixed-kind refs in one frame become the upgrade placeholder, not a mislabeled media message`() = runTest {
        val refs = listOf(ref(1, 1, dur = 2_000), ref(2, 2, w = 10, h = 10))
        val msg = repo.receiveWireText(encodeWire(encodeMediaRefFrame(refs)))!!
        assertThat(msg.kind).isEqualTo(ChatMessage.KIND_UNSUPPORTED)
        assertThat(msg.body).isEqualTo("")
        assertThat(msg.peerUsername).isEqualTo("alice")
        assertThat(db.mediaDao().forMessage(msg.id)).isEmpty()
    }
}
