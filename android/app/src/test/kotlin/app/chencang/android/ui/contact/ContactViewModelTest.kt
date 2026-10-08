package app.chencang.android.ui.contact

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.chencang.android.ui.chat.FakeShareHeaders
import app.chencang.shared.CcRepository
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatMessageDao
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.chat.inTransactionRunner
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.crypto.SessionStateDao
import app.chencang.shared.crypto.SessionStateEntity
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Records every `delete` call so a test can assert exactly which key
 *  [RatchetSessionStore.remove] passed through — `dao.delete(name)` fires
 *  unconditionally regardless of whether a session is cached in memory, so
 *  this doesn't require minting a real uniffi `Session`. Mirrors the private
 *  fixture in `PairingWizardViewModelTest` (not shared across test files). */
private class FakeSessionStateDao : SessionStateDao {
    val deleted = mutableListOf<String>()
    override suspend fun all(): List<SessionStateEntity> = emptyList()
    override suspend fun upsert(row: SessionStateEntity) = Unit
    override suspend fun delete(label: String) {
        deleted += label
    }
    override suspend fun clear() = Unit
}

/** None of [ContactViewModel]'s methods touch encryption. */
private class UnusedCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
        error("not used by contact view model tests")
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray): ByteArray =
        error("not used by contact view model tests")
}

private fun contact(fp: String, username: String = fp, verified: Boolean = false) = Contact(
    fingerprintHex = fp,
    username = username,
    displayName = "联系人 $fp",
    pairedAt = 0L,
    safetyEmoji = listOf("🦊", "🐻", "🐼", "🦁", "🐯", "🐨", "🐮", "🐷"),
    verified = verified,
)

/**
 * Robolectric + a real in-memory [ChatDatabase] (not a hand-rolled fake) so a
 * regression in [ChatMessageDao.clearPeer]'s actual Room query would fail
 * here — same fixture [PairingWizardViewModelTest]/[ConversationViewModelTest]
 * use for the same repository. [SessionStateDao] stays faked
 * ([FakeSessionStateDao]): [RatchetSessionStore.remove] calls
 * `dao?.delete(name)` unconditionally, so a recording fake observes the exact
 * call without needing a real uniffi `Session` to seed a row first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Stock Application — skips CcApp's eager keystore-touching init (no
// AndroidKeyStore provider on the host JVM); this test only needs a Context.
@Config(application = Application::class)
class ContactViewModelTest {

    private lateinit var chatDb: ChatDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        chatDb = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        chatDb.close()
        Dispatchers.resetMain()
    }

    private fun chatRepo(dao: ChatMessageDao = chatDb.dao()) =
        ChatRepository(
            dao = dao,
            sessions = SessionManager(UnusedCrypto()),
            mediaDao = chatDb.mediaDao(),
            mediaFiles = MediaFiles(File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "media-test")),
            inTransaction = chatDb.inTransactionRunner(),
            shareHeaders = FakeShareHeaders,
        )

    private fun TestScope.vm(
        fingerprintHex: String,
        repo: CcRepository,
        chat: ChatRepository = chatRepo(),
        sessionStore: RatchetSessionStore = RatchetSessionStore(dao = FakeSessionStateDao()),
        forgetPeer: suspend (String) -> Unit = {},
        responses: kotlinx.coroutines.flow.Flow<List<PairingResponseRecord>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    ) = ContactViewModel(
        fingerprintHex = fingerprintHex,
        repository = repo,
        chatRepository = chat,
        sessionStore = sessionStore,
        forgetPeer = forgetPeer,
        responses = responses,
    )

    @Test
    fun `contact resolves by fingerprintHex`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a"))
        repo.upsertContact(contact("fp-b"))
        val vm = vm("fp-a", repo)

        val resolved = vm.contact.first { it != null }!!
        assertThat(resolved.fingerprintHex).isEqualTo("fp-a")
    }

    @Test
    fun `confirm marks the contact verified`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a"))
        val vm = vm("fp-a", repo)

        vm.confirm()
        advanceUntilIdle()

        assertThat(repo.contacts.first().first { it.fingerprintHex == "fp-a" }.verified).isTrue()
    }

    @Test
    fun `rename delegates to repository renameContact`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a"))
        val vm = vm("fp-a", repo)

        vm.rename("  老王  ")
        advanceUntilIdle()

        assertThat(repo.contacts.first().first { it.fingerprintHex == "fp-a" }.displayName).isEqualTo("老王")
    }

    @Test
    fun `clearMessages only wipes this peer's thread`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a", username = "user-a"))
        chatDb.dao().insert(
            ChatMessage(id = "m1", peerUsername = "user-a", direction = ChatMessage.DIRECTION_OUT, body = "hi a", timestamp = 0L),
        )
        chatDb.dao().insert(
            ChatMessage(id = "m2", peerUsername = "user-b", direction = ChatMessage.DIRECTION_OUT, body = "hi b", timestamp = 0L),
        )
        val vm = vm("fp-a", repo)

        // clearThread's Room delete genuinely hops off the test scheduler
        // onto Room's own query executor thread, so synchronize on the real
        // emission via turbine instead of advanceUntilIdle() — same caveat
        // PairingWizardViewModelTest's awaitStage kdoc documents.
        vm.clearMessages()
        chatDb.dao().observeThread("user-a").test {
            var items = awaitItem()
            while (items.isNotEmpty()) items = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(chatDb.dao().observeThread("user-b").first()).hasSize(1)
    }

    @Test
    fun `deleteContact removes contact, session by username, and thread, then emits deleted`() = runTest {
        val fp = "fp-mismatch"
        val username = "user-mismatch"
        val repo = CcRepository.forTest()
        repo.upsertContact(contact(fp, username = username))
        chatDb.dao().insert(
            ChatMessage(id = "m1", peerUsername = username, direction = ChatMessage.DIRECTION_OUT, body = "hi", timestamp = 0L),
        )
        val sessionDao = FakeSessionStateDao()
        val vm = vm(fp, repo, sessionStore = RatchetSessionStore(dao = sessionDao))

        // deleteContact's coroutine sets/emits `deleted` only after all three
        // deletes (including the real Room clearThread write) complete, so
        // observing this emission is proof the deletes already ran.
        vm.deleted.test {
            vm.deleteContact()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(repo.contacts.first().map { it.fingerprintHex }).doesNotContain(fp)
        // Locks the key choice: RatchetSessionStore is keyed by username —
        // same choice PairingWizardViewModel.rejectMismatch makes.
        assertThat(sessionDao.deleted).containsExactly(username)
        assertThat(chatDb.dao().observeThread(username).first()).isEmpty()
    }

    @Test
    fun `deleteContact forgets the pending response`() = runTest {
        // Review Focus 3: the invitee deletes the contact before ever sending the response back —
        // the stored response must go with it, or 「配对中」 keeps a row for nobody.
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a", username = "user-a"))
        val forgotten = mutableListOf<String>()
        val vm = vm("fp-a", repo, forgetPeer = { forgotten += it })

        vm.deleted.test {
            vm.deleteContact()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Keyed by fingerprint (the response store's key), not by username.
        assertThat(forgotten).containsExactly("fp-a")
    }

    @Test
    fun `resendableResponse is the response wire only once it has been shared`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a"))
        repo.upsertContact(contact("fp-b"))
        repo.upsertContact(contact("fp-c"))
        val records = kotlinx.coroutines.flow.flowOf(
            listOf(
                PairingResponseRecord("fp-a", "🔒resp-a", "d", 0L, lastSharedAtMillis = 5L),
                PairingResponseRecord("fp-b", "🔒resp-b", "d", 0L, lastSharedAtMillis = null),
            ),
        )
        vm("fp-a", repo, responses = records).resendableResponse.test {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertThat(v).isEqualTo("🔒resp-a")
            cancelAndIgnoreRemainingEvents()
        }
        // 还没分享过：在「配对中」里处理，详情页不显示。
        assertThat(vm("fp-b", repo, responses = records).resendableResponse.first()).isNull()
        // 没有回应记录。
        assertThat(vm("fp-c", repo, responses = records).resendableResponse.first()).isNull()
    }

    @Test
    fun `resendableResponse disappears as soon as the response record is cleared`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contact("fp-a"))
        val responses = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(PairingResponseRecord("fp-a", "🔒resp-a", "d", 0L, lastSharedAtMillis = 5L)),
        )
        vm("fp-a", repo, responses = responses).resendableResponse.test {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertThat(v).isEqualTo("🔒resp-a")
            responses.value = emptyList() // 对方的第一条消息到了 → forgetPeer
            assertThat(awaitItem()).isNull()
        }
    }
}
