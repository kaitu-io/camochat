package app.chencang.android.ui.chat

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadRowsTest {
    private fun item(msg: String, idx: Int, kind: Int = 2) = MediaItem(
        messageId = msg, index = idx, kind = kind, durMs = 0, width = 1, height = 1, byteLen = 60L,
        blobSecret = ByteArray(32), blobId = "x".repeat(22), state = MediaItem.STATE_PENDING,
    )

    @Test
    fun `text rows stay one per message and media rows expand one per item`() {
        val text = ChatMessage("t1", "alice", ChatMessage.DIRECTION_IN, "你好", 1L)
        val album = ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[3 张图片]", 2L, kind = ChatMessage.KIND_IMAGE)
        val voice = ChatMessage("v1", "alice", ChatMessage.DIRECTION_OUT, "[语音]", 3L, kind = ChatMessage.KIND_VOICE)
        val rows = ThreadRows.build(
            listOf(text, album, voice),
            listOf(item("m1", 2), item("m1", 0), item("v1", 0, kind = 1), item("m1", 1)),
            ccaExists = { _, _ -> true },
        )
        assertThat(rows.map { it.key }).containsExactly("t1", "m1:0", "m1:1", "m1:2", "v1:0").inOrder()
        assertThat(rows[0].item).isNull()
        assertThat(rows[2].item!!.index).isEqualTo(1)
    }

    @Test
    fun `unsupported placeholder renders as a plain row`() {
        val ph = ChatMessage("u1", "alice", ChatMessage.DIRECTION_IN, "", 1L, kind = ChatMessage.KIND_UNSUPPORTED)
        assertThat(ThreadRows.build(listOf(ph), emptyList(), ccaExists = { _, _ -> true }).single().item).isNull()
    }

    @Test
    fun `a media message whose items have not landed yet is not shown`() {
        val album = ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[图片]", 2L, kind = ChatMessage.KIND_IMAGE)
        assertThat(ThreadRows.build(listOf(album), emptyList(), ccaExists = { _, _ -> true })).isEmpty()
    }

    private fun rowOf(msg: ChatMessage, it: MediaItem) = ThreadRow("${msg.id}:${it.index}", msg, it)

    private val inAlbum = ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "[3 张图片]", 2L, kind = ChatMessage.KIND_IMAGE)

    private fun ready(idx: Int) = item("m1", idx).copy(state = MediaItem.STATE_READY, localPath = "/f/$idx")

    @Test
    fun `single forward needs that item's file on this device`() {
        assertThat(ThreadRows.hasLocalFile(rowOf(inAlbum, ready(0)))).isTrue()
        // 收到的：还没下载（无路径）或路径在但没下完 → 不能转发
        assertThat(ThreadRows.hasLocalFile(rowOf(inAlbum, item("m1", 0)))).isFalse()
        assertThat(ThreadRows.hasLocalFile(rowOf(inAlbum, item("m1", 0).copy(localPath = "/f/0")))).isFalse()
        // 自己发的：有本地路径即可
        val out = inAlbum.copy(direction = ChatMessage.DIRECTION_OUT)
        assertThat(ThreadRows.hasLocalFile(rowOf(out, item("m1", 0).copy(localPath = "/f/0")))).isTrue()
        assertThat(ThreadRows.hasLocalFile(ThreadRow("t1", inAlbum, null))).isFalse()
    }

    @Test
    fun `forward all only when every album item is on this device`() {
        val all = listOf(ready(0), ready(1), ready(2)).map { rowOf(inAlbum, it) }
        assertThat(ThreadRows.canForwardAll(all)).isTrue()
        val oneMissing = listOf(ready(0), item("m1", 1), ready(2)).map { rowOf(inAlbum, it) }
        assertThat(ThreadRows.canForwardAll(oneMissing)).isFalse()
        // 单张不出「转发全部」
        assertThat(ThreadRows.canForwardAll(all.take(1))).isFalse()
        assertThat(ThreadRows.canForwardAll(emptyList())).isFalse()
    }
}
