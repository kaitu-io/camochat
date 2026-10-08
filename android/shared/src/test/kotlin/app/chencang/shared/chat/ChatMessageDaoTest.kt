package app.chencang.shared.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatMessageDaoTest {
    private lateinit var db: ChatDatabase
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeThread returns only that peer, timestamp ascending`() = runTest {
        dao.insert(ChatMessage("m2", "alice", ChatMessage.DIRECTION_OUT, "后", 200L))
        dao.insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "先", 100L))
        dao.insert(ChatMessage("m3", "bob", ChatMessage.DIRECTION_IN, "别家", 150L))

        val thread = dao.observeThread("alice").first()
        assertThat(thread.map { it.id }).containsExactly("m1", "m2").inOrder()
    }

    @Test
    fun `clearAll wipes everything`() = runTest {
        dao.insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "x", 1L))
        dao.clearAll()
        assertThat(dao.observeThread("alice").first()).isEmpty()
    }

    @Test
    fun `close resets the companion singleton so create rebuilds an open db`() = runTest {
        // Exercises the real companion-cached ChatDatabase.create(context) path
        // (not the in-memory per-test db above), mirroring the account-wipe
        // sequence: wipeAll -> CcServiceLocator.reset() -> close() ->
        // CcServiceLocator.from(context) -> ChatDatabase.create(context) again,
        // all without a process restart (single-process app).
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val first = ChatDatabase.create(context)
            first.close()

            val second = ChatDatabase.create(context)
            assertThat(second).isNotSameInstanceAs(first)

            second.dao().insert(
                ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "还活着", 1L),
            )
            assertThat(second.dao().observeThread("alice").first().map { it.id })
                .containsExactly("m1")

            second.close()
        } finally {
            // Real ChatDatabase.create() is disk-backed by companion-singleton
            // design (unlike the in-memory `db` above) — drop the file so this
            // test's data can't bleed into another test that also touches the
            // real companion instance.
            context.deleteDatabase("cc-chat.db")
        }
    }

    @Test
    fun `updateStatus 更新状态`() = runTest {
        val msg = ChatMessage(id = "m1", peerUsername = "alice", direction = ChatMessage.DIRECTION_OUT, body = "hi", timestamp = 1L)
        dao.insert(msg)
        dao.updateStatus("m1", ChatMessage.STATUS_SENT)
        val row = dao.observeThread("alice").first().single()
        assertThat(row.status).isEqualTo(ChatMessage.STATUS_SENT)
    }

    @Test
    fun `observeLatestPerPeer 每个会话只出最新一条`() = runTest {
        dao.insert(ChatMessage("a1", "alice", ChatMessage.DIRECTION_OUT, "old", 1L))
        dao.insert(ChatMessage("a2", "alice", ChatMessage.DIRECTION_IN, "new", 2L))
        dao.insert(ChatMessage("b1", "bob", ChatMessage.DIRECTION_OUT, "only", 5L))
        val latest = dao.observeLatestPerPeer().first().associateBy { it.peerUsername }
        assertThat(latest.getValue("alice").body).isEqualTo("new")
        assertThat(latest.getValue("bob").body).isEqualTo("only")
        assertThat(latest).hasSize(2)
    }

    private fun inRow(id: String, share: String?) =
        ChatMessage(id, "alice", ChatMessage.DIRECTION_IN, "x", 1L, shareText = share)

    @Test
    fun `findByWire matches an exact share text`() = runTest {
        dao.insert(inRow("m1", "🔒abc"))
        assertThat(dao.findByWire("🔒abc")?.id).isEqualTo("m1")
    }

    @Test
    fun `findByWire matches the last line of a two line share text`() = runTest {
        dao.insert(inRow("m1", "🔒 header https://site.test/m/x\n🔒abc"))
        assertThat(dao.findByWire("🔒abc")?.id).isEqualTo("m1")
    }

    @Test
    fun `findByWire does not match a different wire or a mere suffix without a newline`() = runTest {
        dao.insert(inRow("m1", "🔒xyz🔒abc"))
        dao.insert(inRow("m2", "🔒abcd"))
        dao.insert(inRow("m3", null))
        assertThat(dao.findByWire("🔒abc")).isNull()
        assertThat(dao.findByWire("🔒other")).isNull()
    }

    @Test
    fun `guarded copy refuses to touch a sent row and guarded share upgrades copied`() = runTest {
        dao.insert(ChatMessage("o1", "alice", ChatMessage.DIRECTION_OUT, "x", 1L, status = ChatMessage.STATUS_SENT))
        assertThat(dao.markCopiedGuarded("o1", "alice")).isEqualTo(0)
        assertThat(dao.getById("o1")!!.status).isEqualTo(ChatMessage.STATUS_SENT)

        dao.insert(ChatMessage("o2", "alice", ChatMessage.DIRECTION_OUT, "y", 2L))
        assertThat(dao.markCopiedGuarded("o2", "bob")).isEqualTo(0)
        assertThat(dao.markCopiedGuarded("o2", "alice")).isEqualTo(1)
        assertThat(dao.markCopiedGuarded("o2", "alice")).isEqualTo(0)
        assertThat(dao.markSentGuarded("o2", "alice")).isEqualTo(1)
        assertThat(dao.getById("o2")!!.status).isEqualTo(ChatMessage.STATUS_SENT)
    }
}
