package app.chencang.shared.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemDaoTest {
    private lateinit var db: ChatDatabase
    private lateinit var dao: MediaItemDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        dao = db.mediaDao()
    }

    @After
    fun tearDown() = db.close()

    private fun item(msg: String, idx: Int, state: String = MediaItem.STATE_PENDING, kind: Int = 2) = MediaItem(
        messageId = msg, index = idx, kind = kind, durMs = 0, width = 100, height = 50, byteLen = 1_050L,
        blobSecret = ByteArray(32) { idx.toByte() }, blobId = "b$idx".padEnd(22, 'x'), state = state,
    )

    @Test
    fun `observeForPeer joins on the thread and orders by message time then index`() = runTest {
        db.dao().insert(ChatMessage("m2", "alice", ChatMessage.DIRECTION_IN, "[图片]", 200L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[2 张图片]", 100L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m3", "bob", ChatMessage.DIRECTION_IN, "[图片]", 150L, kind = ChatMessage.KIND_IMAGE))
        dao.insertAll(listOf(item("m2", 0), item("m1", 1), item("m1", 0), item("m3", 0)))

        val keys = dao.observeForPeer("alice").first().map { "${it.messageId}/${it.index}" }
        assertThat(keys).containsExactly("m1/0", "m1/1", "m2/0").inOrder()
    }

    @Test
    fun `observeAlbumSizes lists only messages with more than one item`() = runTest {
        dao.insertAll(listOf(item("m1", 0), item("m1", 1), item("m1", 2), item("m2", 0)))
        val sizes = dao.observeAlbumSizes().first().associate { it.messageId to it.count }
        assertThat(sizes).containsExactly("m1", 3)
    }

    @Test
    fun `updateSealedBlob stores secret id length and state`() = runTest {
        dao.insertAll(listOf(item("m1", 0, MediaItem.STATE_ENCRYPTING)))
        val secret = ByteArray(32) { 9 }
        dao.updateSealedBlob("m1", 0, secret, "Z".repeat(22), 2_000L, MediaItem.STATE_UPLOADING)
        val got = dao.get("m1", 0)!!
        assertThat(got.blobSecret).isEqualTo(secret)
        assertThat(got.blobId).isEqualTo("Z".repeat(22))
        assertThat(got.byteLen).isEqualTo(2_000L)
        assertThat(got.state).isEqualTo(MediaItem.STATE_UPLOADING)
    }

    @Test
    fun `resetStates only touches this peer, listed states, and non-busy messages`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m2", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m3", "bob", ChatMessage.DIRECTION_OUT, "[图片]", 3L, kind = ChatMessage.KIND_IMAGE))
        dao.insertAll(
            listOf(
                item("m1", 0, MediaItem.STATE_UPLOADING),
                item("m2", 0, MediaItem.STATE_UPLOADING),
                item("m3", 0, MediaItem.STATE_UPLOADING),
            ),
        )
        dao.resetStates(
            peerUsername = "alice",
            from = listOf(MediaItem.STATE_ENCRYPTING, MediaItem.STATE_UPLOADING),
            to = MediaItem.STATE_FAILED,
            busy = listOf("m2"),
        )
        assertThat(dao.get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(dao.get("m2", 0)!!.state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(dao.get("m3", 0)!!.state).isEqualTo(MediaItem.STATE_UPLOADING)
    }

    @Test
    fun `resetStates with an empty busy list still resets every matching row`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 1L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m2", "alice", ChatMessage.DIRECTION_OUT, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE))
        db.dao().insert(ChatMessage("m3", "bob", ChatMessage.DIRECTION_OUT, "[图片]", 3L, kind = ChatMessage.KIND_IMAGE))
        dao.insertAll(
            listOf(
                item("m1", 0, MediaItem.STATE_UPLOADING),
                item("m2", 0, MediaItem.STATE_ENCRYPTING),
                item("m3", 0, MediaItem.STATE_UPLOADING),
            ),
        )
        dao.resetStates(
            peerUsername = "alice",
            from = listOf(MediaItem.STATE_ENCRYPTING, MediaItem.STATE_UPLOADING),
            to = MediaItem.STATE_FAILED,
            busy = emptyList(),
        )
        assertThat(dao.get("m1", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(dao.get("m2", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(dao.get("m3", 0)!!.state).isEqualTo(MediaItem.STATE_UPLOADING) // bob 不受影响
    }

    @Test
    fun `expireStaleIncoming only touches old incoming pending or failed items of that peer`() = runTest {
        db.dao().insert(ChatMessage("old", "alice", ChatMessage.DIRECTION_IN, "[视频]", 100L, kind = ChatMessage.KIND_VIDEO))
        db.dao().insert(ChatMessage("new", "alice", ChatMessage.DIRECTION_IN, "[视频]", 500L, kind = ChatMessage.KIND_VIDEO))
        db.dao().insert(ChatMessage("mine", "alice", ChatMessage.DIRECTION_OUT, "[视频]", 100L, kind = ChatMessage.KIND_VIDEO))
        db.dao().insert(ChatMessage("done", "alice", ChatMessage.DIRECTION_IN, "[图片]", 100L, kind = ChatMessage.KIND_IMAGE))
        dao.insertAll(
            listOf(
                item("old", 0, MediaItem.STATE_PENDING, kind = 3),
                item("new", 0, MediaItem.STATE_PENDING, kind = 3),
                item("mine", 0, MediaItem.STATE_FAILED, kind = 3),
                item("done", 0, MediaItem.STATE_READY),
            ),
        )
        dao.expireStaleIncoming(
            peerUsername = "alice",
            cutoffMs = 100L,
            from = listOf(MediaItem.STATE_PENDING, MediaItem.STATE_FAILED),
            to = MediaItem.STATE_EXPIRED,
        )
        assertThat(dao.get("old", 0)!!.state).isEqualTo(MediaItem.STATE_EXPIRED)
        assertThat(dao.get("new", 0)!!.state).isEqualTo(MediaItem.STATE_PENDING)
        assertThat(dao.get("mine", 0)!!.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(dao.get("done", 0)!!.state).isEqualTo(MediaItem.STATE_READY)
    }

    @Test
    fun `deleteForPeer removes only that peer's items`() = runTest {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[语音]", 1L, kind = ChatMessage.KIND_VOICE))
        db.dao().insert(ChatMessage("m3", "bob", ChatMessage.DIRECTION_IN, "[语音]", 3L, kind = ChatMessage.KIND_VOICE))
        dao.insertAll(listOf(item("m1", 0, kind = 1), item("m3", 0, kind = 1)))
        dao.deleteForPeer("alice")
        assertThat(dao.forMessage("m1")).isEmpty()
        assertThat(dao.forMessage("m3")).hasSize(1)
    }

    @Test
    fun `latest-per-peer returns the newest row, media included`() = runTest {
        db.dao().insert(ChatMessage("t1", "alice", ChatMessage.DIRECTION_IN, "你好", 1L))
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "", 2L, kind = ChatMessage.KIND_IMAGE))
        val latest = db.dao().observeLatestPerPeer().first().single()
        assertThat(latest.id).isEqualTo("m1")
        assertThat(latest.kind).isEqualTo(ChatMessage.KIND_IMAGE)
    }
}
