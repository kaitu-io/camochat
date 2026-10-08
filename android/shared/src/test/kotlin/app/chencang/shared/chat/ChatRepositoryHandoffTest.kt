package app.chencang.shared.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.media.FakeShareHeaders
import app.chencang.shared.media.MediaFiles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private class PassCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) =
        SessionCrypto.DecryptedAnyBytes(senderKey = "alice", plaintext = ciphertext)
}

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryHandoffTest {
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(PassCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = MediaFiles(File(context.cacheDir, "media-handoff-test")),
            inTransaction = db.inTransactionRunner(),
            now = { 42L },
            newId = { "out-1" },
            shareHeaders = FakeShareHeaders,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun status() = db.dao().getById("out-1")!!.status

    @Test fun `copy marks a sealed own message copied`() = runTest {
        repo.sendText("alice", "hi")
        assertThat(repo.markCopiedIfOwned("out-1", "alice")).isEqualTo(ChatRepository.MarkResult.MARKED)
        assertThat(status()).isEqualTo("copied")
        assertThat(repo.markCopiedIfOwned("out-1", "alice")).isEqualTo(ChatRepository.MarkResult.UNCHANGED)
    }

    @Test fun `share after copy upgrades to sent`() = runTest {
        repo.sendText("alice", "hi")
        repo.markCopiedIfOwned("out-1", "alice")
        assertThat(repo.markSentIfOwned("out-1", "alice")).isEqualTo(ChatRepository.MarkResult.MARKED)
        assertThat(status()).isEqualTo("sent")
    }

    @Test fun `copy after share never downgrades`() = runTest {
        repo.sendText("alice", "hi")
        repo.markSentIfOwned("out-1", "alice")
        assertThat(repo.markCopiedIfOwned("out-1", "alice")).isEqualTo(ChatRepository.MarkResult.UNCHANGED)
        assertThat(status()).isEqualTo("sent")
    }

    @Test fun `marking an incoming or foreign message is NOT_OWNED`() = runTest {
        db.dao().insert(ChatMessage("in-1", "alice", ChatMessage.DIRECTION_IN, "hey", 1L))
        repo.sendText("alice", "hi")
        for (mark in listOf<suspend (String, String) -> ChatRepository.MarkResult>(
            repo::markSentIfOwned, repo::markCopiedIfOwned,
        )) {
            assertThat(mark("in-1", "alice")).isEqualTo(ChatRepository.MarkResult.NOT_OWNED)
            assertThat(mark("out-1", "bob")).isEqualTo(ChatRepository.MarkResult.NOT_OWNED)
            assertThat(mark("nope", "alice")).isEqualTo(ChatRepository.MarkResult.NOT_OWNED)
        }
        assertThat(status()).isEqualTo("sealed")
        assertThat(db.dao().getById("in-1")!!.status).isEqualTo("sealed")
    }
}
