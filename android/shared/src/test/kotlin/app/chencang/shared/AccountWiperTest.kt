package app.chencang.shared

import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatMessageDao
import app.chencang.shared.media.MediaFiles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** In-memory stand-in for the Room DAO, just enough to exercise clearAll(). */
private class FakeChatMessageDao : ChatMessageDao {
    private val state = MutableStateFlow<List<ChatMessage>>(emptyList())

    override fun observeThread(peerUsername: String): Flow<List<ChatMessage>> =
        state.map { list -> list.filter { it.peerUsername == peerUsername } }

    override suspend fun insert(msg: ChatMessage) {
        state.update { it + msg }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }

    override suspend fun clearPeer(peerUsername: String) {
        state.update { list -> list.filterNot { it.peerUsername == peerUsername } }
    }

    override suspend fun updateStatus(id: String, status: String) {
        state.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
    }

    private fun guarded(id: String, peer: String, to: String, allowed: (String) -> Boolean): Int {
        var n = 0
        state.update { list ->
            list.map {
                if (it.id == id && it.direction == ChatMessage.DIRECTION_OUT && it.peerUsername == peer && allowed(it.status)) {
                    n = 1
                    it.copy(status = to)
                } else it
            }
        }
        return n
    }

    override suspend fun markCopiedGuarded(id: String, peer: String): Int =
        guarded(id, peer, ChatMessage.STATUS_COPIED) { it != ChatMessage.STATUS_COPIED && it != ChatMessage.STATUS_SENT }

    override suspend fun markSentGuarded(id: String, peer: String): Int =
        guarded(id, peer, ChatMessage.STATUS_SENT) { it != ChatMessage.STATUS_SENT }

    override fun observeLatestPerPeer(): Flow<List<ChatMessage>> =
        state.map { list -> list.groupBy { it.peerUsername }.values.mapNotNull { it.maxByOrNull(ChatMessage::timestamp) } }

    override suspend fun idsForPeer(peerUsername: String): List<String> =
        state.value.filter { it.peerUsername == peerUsername }.map { it.id }

    override suspend fun getById(id: String): ChatMessage? = state.value.firstOrNull { it.id == id }

    override suspend fun deleteById(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun updateShareText(id: String, shareText: String) {
        state.update { list -> list.map { if (it.id == id) it.copy(shareText = shareText) else it } }
    }

    override suspend fun findByWire(wire: String): ChatMessage? =
        state.value.firstOrNull { it.shareText == wire || it.shareText?.endsWith("\n$wire") == true }
}

class AccountWiperTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun wiper(
        record: MutableList<String>,
        failOn: Set<String> = emptySet(),
        chatDao: ChatMessageDao = FakeChatMessageDao(),
        mediaFiles: MediaFiles = MediaFiles(File(tmp.root, "media")),
    ): AccountWiper {
        fun step(name: String): suspend () -> Unit = {
            record += name
            if (name in failOn) error("boom-$name")
        }
        return AccountWiper(
            clearChat = {
                record += "chat"
                if ("chat" in failOn) error("boom-chat")
                chatDao.clearAll()
            },
            clearMedia = {
                record += "media"
                if ("media" in failOn) error("boom-media")
                mediaFiles.deleteAll()
            },
            clearSessions = step("sessions"),
            clearContacts = step("contacts"),
            clearPendingPairing = step("pendingPairing"),
            clearDevicePrefs = step("devicePrefs"),
            clearMyProfile = step("myProfile"),
            wipeSpk = step("spk"),
            wipeIdentity = step("identity"),
            deleteKeystoreKey = step("keystoreKey"),
        )
    }

    @Test
    fun `wipeAll runs every step in data-first identity-last order`() = runTest {
        val record = mutableListOf<String>()
        wiper(record).wipeAll()
        assertThat(record).containsExactly(
            "pendingPairing", "chat", "media", "sessions", "contacts",
            "devicePrefs", "myProfile",
            "spk", "identity", "keystoreKey",
        ).inOrder()
    }

    @Test
    fun `a failing step does not stop later steps and failures aggregate`() = runTest {
        val record = mutableListOf<String>()
        var caught: AccountWiper.WipeFailed? = null
        try {
            wiper(record, failOn = setOf("sessions", "spk")).wipeAll()
        } catch (e: AccountWiper.WipeFailed) {
            caught = e
        }
        assertThat(caught).isNotNull()
        assertThat(record).hasSize(10) // identity 依然被清了
        assertThat(caught!!.failures.map { it.first }).containsExactly("sessions", "spk")
    }

    @Test
    fun `clearMedia throwing surfaces as media in WipeFailed and later steps still run`() = runTest {
        val record = mutableListOf<String>()
        var caught: AccountWiper.WipeFailed? = null
        try {
            wiper(record, failOn = setOf("media")).wipeAll()
        } catch (e: AccountWiper.WipeFailed) {
            caught = e
        }
        assertThat(caught).isNotNull()
        assertThat(caught!!.failures.map { it.first }).containsExactly("media")
        assertThat(record).containsExactly(
            "pendingPairing", "chat", "media", "sessions", "contacts",
            "devicePrefs", "myProfile", "spk", "identity", "keystoreKey",
        ).inOrder()
    }

    @Test
    fun `a failing myProfile step is reported and later steps still run`() = runTest {
        val record = mutableListOf<String>()
        var caught: AccountWiper.WipeFailed? = null
        try {
            wiper(record, failOn = setOf("myProfile")).wipeAll()
        } catch (e: AccountWiper.WipeFailed) {
            caught = e
        }
        assertThat(caught).isNotNull()
        assertThat(caught!!.failures.map { it.first }).containsExactly("myProfile")
        assertThat(record).containsExactly(
            "pendingPairing", "chat", "media", "sessions", "contacts",
            "devicePrefs", "myProfile", "spk", "identity", "keystoreKey",
        ).inOrder()
    }

    @Test
    fun `wipeAll clears persisted chat history`() = runTest {
        val chatDao = FakeChatMessageDao()
        chatDao.insert(
            ChatMessage(
                id = "1",
                peerUsername = "alice",
                direction = ChatMessage.DIRECTION_OUT,
                body = "hi",
                timestamp = 1L,
            ),
        )
        assertThat(chatDao.observeThread("alice").first()).hasSize(1)

        wiper(mutableListOf(), chatDao = chatDao).wipeAll()

        assertThat(chatDao.observeThread("alice").first()).isEmpty()
    }

    @Test
    fun `wipeAll deletes the whole media directory`() = runTest {
        val files = MediaFiles(File(tmp.root, "media"))
        files.write(files.bin("m1", 0), byteArrayOf(1))
        files.write(files.cca("m2", 3), byteArrayOf(2))

        wiper(mutableListOf(), mediaFiles = files).wipeAll()

        assertThat(files.root.exists()).isFalse()
    }

    @Test
    fun `wipeAll also deletes plaintext left in the cache scratch dirs (prep, voice, capture)`() = runTest {
        val cache = File(tmp.root, "cache")
        val scratch = MediaFiles.scratchDirsUnder(cache)
        val files = MediaFiles(File(tmp.root, "media"), scratchDirs = scratch)
        scratch.forEach { dir -> File(dir.apply { mkdirs() }, "left-behind").writeBytes(byteArrayOf(9)) }
        val unrelated = File(cache, "other").apply { mkdirs() }

        wiper(mutableListOf(), mediaFiles = files).wipeAll()

        assertThat(scratch.map { it.name }).containsExactly("prep", "voice", "capture")
        scratch.forEach { assertThat(it.exists()).isFalse() }
        assertThat(unrelated.exists()).isTrue()
    }
}
