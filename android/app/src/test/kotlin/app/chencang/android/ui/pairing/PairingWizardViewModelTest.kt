package app.chencang.android.ui.pairing

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.chencang.android.ui.chat.FakeShareHeaders
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.chencang.shared.CcRepository
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.intake.IntakeOutcome
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
import app.chencang.shared.pairing.PairingCopy
import app.chencang.shared.pairing.inband.IncomingOutcome
import app.chencang.shared.pairing.inband.IncomingRejection
import app.chencang.shared.pairing.inband.PairingCoordinator
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingPairingRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
 *  this doesn't require minting a real uniffi `Session`. */
private class FakeSessionStateDao : SessionStateDao {
    val deleted = mutableListOf<String>()
    override suspend fun all(): List<SessionStateEntity> = emptyList()
    override suspend fun upsert(row: SessionStateEntity) = Unit
    override suspend fun delete(label: String) {
        deleted += label
    }
    override suspend fun clear() = Unit
}

/** [rejectMismatch]/[ChatRepository.clearThread] never touch encryption. */
private class UnusedCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
        error("not used by wizard tests")
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray): ByteArray =
        error("not used by wizard tests")
}

/**
 * Robolectric + a real in-memory [ChatDatabase] (not a hand-rolled fake) so a
 * regression in [ChatMessageDao.clearPeer]'s actual Room query would fail
 * here — same fixture [ConversationViewModelTest] uses for the same
 * repository. [SessionStateDao] stays faked ([FakeSessionStateDao]):
 * [RatchetSessionStore.remove] calls `dao?.delete(name)` unconditionally, so
 * a recording fake observes the exact call without needing a real uniffi
 * `Session` to seed a row first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Stock Application — skips CcApp's eager keystore-touching init (no
// AndroidKeyStore provider on the host JVM); this test only needs a Context.
@Config(application = Application::class)
class PairingWizardViewModelTest {

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

    /**
     * Awaits [wizard]'s [WizardUi.stage] reaching stage type [T], polling the
     * real emission stream via turbine rather than trusting [advanceUntilIdle].
     * [rejectMismatch]'s coroutine now ends with a REAL Room write
     * ([ChatRepository.clearThread] against [chatDb]) before it sets the
     * terminal stage — that write hops onto Room's own query-executor thread,
     * which is outside any [kotlinx.coroutines.test.TestCoroutineScheduler]
     * and so cannot be fast-forwarded by [advanceUntilIdle] (same caveat
     * [ConversationViewModelTest] documents for its own Room-backed
     * assertions). Turbine's [app.cash.turbine.ReceiveTurbine.awaitItem]
     * suspends on real time instead, so it reliably observes the stage once
     * the real write actually finishes.
     */
    private suspend inline fun <reified T : WizardStage> awaitStage(wizard: PairingWizardViewModel): T {
        var found: T? = null
        wizard.ui.test {
            var stage = awaitItem().stage
            while (stage !is T) stage = awaitItem().stage
            found = stage
            cancelAndIgnoreRemainingEvents()
        }
        return found!!
    }

    /** [TestScope] extension so every call site shares [TestScope.testScheduler]
     *  with `runTest` — an unlinked `StandardTestDispatcher()` would never be
     *  driven by [advanceUntilIdle], leaving `withContext(ioDispatcher)` calls
     *  permanently suspended (same wiring the former invite/redeem view-model
     *  tests used). */
    private fun TestScope.vm(
        entry: WizardEntry,
        driver: FakePairingDriver,
        repo: CcRepository = CcRepository.forTest(),
        chat: ChatRepository = chatRepo(),
        sessionStore: RatchetSessionStore = RatchetSessionStore(dao = FakeSessionStateDao()),
        saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
        intake: FakeIntake = FakeIntake(),
        shareCompleted: Flow<String> = emptyFlow(),
        pendingInvites: Flow<List<PendingPairingRecord>> = flowOf(emptyList()),
        myName: () -> String = { "" },
        namePromptDone: () -> Boolean = { true },
        saveName: (String?) -> Unit = {},
    ) = PairingWizardViewModel(
        entry = entry,
        pairing = driver,
        intake = intake,
        repository = repo,
        chatRepository = chat,
        sessionStore = sessionStore,
        discardScope = this,
        shareCompleted = shareCompleted,
        pendingInvites = pendingInvites,
        myDisplayName = myName,
        namePromptDone = namePromptDone,
        saveName = saveName,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        savedState = saved,
    )

    /** Owns [vm] in a [ViewModelStore] so the test can clear it the way navigation does ([ViewModel.onCleared]). */
    private fun owned(vm: PairingWizardViewModel): ViewModelStore {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        )[PairingWizardViewModel::class.java]
        return store
    }

    @Test
    fun `initiator full path shows invite, receives response, confirms and opens thread`() = runTest {
        val contactA = Contact(fingerprintHex = "fp-a", username = "user-a", displayName = "Contact fp-a", pairedAt = 0L)
        val driver = FakePairingDriver(
            startInvite = { name -> assertThat(name).isEqualTo(""); inviteRecord("🔒invite") },
            handleIncoming = { wire, _ ->
                assertThat(wire).isEqualTo("🔒response")
                IncomingOutcome.Completed(PairingCoordinator.CompleteOutcome(EMO, contactA))
            },
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(contactA) // mirrors what the real PairingCoordinator.completeIncoming persists
        val wizard = vm(WizardEntry.Initiator, driver, repo = repo)

        wizard.start(); advanceUntilIdle()
        val shown = wizard.ui.value
        assertThat(shown.stepIndex).isEqualTo(0)
        val showStage = shown.stage as WizardStage.Show
        assertThat(showStage.wire).isEqualTo("🔒invite")
        assertThat(showStage.isResponse).isFalse()

        wizard.advanceFromShow()
        val receiving = wizard.ui.value
        assertThat(receiving.stepIndex).isEqualTo(1)
        assertThat(receiving.stage).isEqualTo(WizardStage.Receive(waitingForPeer = true))

        wizard.openThread.test {
            wizard.submitWire("🔒response"); advanceUntilIdle()
            val confirming = wizard.ui.value
            assertThat(confirming.stepIndex).isEqualTo(2)
            val confirmStage = confirming.stage as WizardStage.Confirm
            assertThat(confirmStage.emoji).hasSize(8)
            assertThat(confirmStage.fingerprintHex).isEqualTo("fp-a")
            assertThat(confirmStage.peerUsername).isEqualTo("user-a")

            wizard.confirmMatch("")
            assertThat(awaitItem()).isEqualTo("user-a")
        }
        assertThat(repo.contacts.first().first { it.fingerprintHex == "fp-a" }.verified).isTrue()
    }

    @Test
    fun `redeemer full path receives invite, shows response, confirms and opens thread`() = runTest {
        val contactB = Contact(fingerprintHex = "fp-b", username = "user-b", displayName = "Contact fp-b", pairedAt = 0L)
        val driver = FakePairingDriver(
            handleIncoming = { wire, name ->
                assertThat(wire).isEqualTo("🔒invite-from-a")
                assertThat(name).isEqualTo("")
                IncomingOutcome.Accepted(PairingCoordinator.AcceptOutcome("🔒response", EMO, contactB))
            },
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(contactB)
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo)

        wizard.start(); advanceUntilIdle()
        val receiving = wizard.ui.value
        assertThat(receiving.stepIndex).isEqualTo(0)
        assertThat(receiving.stage).isEqualTo(WizardStage.Receive())

        wizard.submitWire("🔒invite-from-a"); advanceUntilIdle()
        val shown = wizard.ui.value
        assertThat(shown.stepIndex).isEqualTo(1)
        val showStage = shown.stage as WizardStage.Show
        assertThat(showStage.wire).isEqualTo("🔒response")
        assertThat(showStage.isResponse).isTrue()

        wizard.advanceFromShow()
        val confirming = wizard.ui.value
        assertThat(confirming.stepIndex).isEqualTo(2)
        val confirmStage = confirming.stage as WizardStage.Confirm
        assertThat(confirmStage.fingerprintHex).isEqualTo("fp-b")
        assertThat(confirmStage.peerUsername).isEqualTo("user-b")

        wizard.openThread.test {
            wizard.confirmMatch("")
            assertThat(awaitItem()).isEqualTo("user-b")
        }
    }

    @Test
    fun `blank paste surfaces retry-able error without ever touching the driver`() = runTest {
        // No lambdas wired — any call errors loudly, proving submitWire short-circuits on blank.
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver())
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("   ")
        val errored = wizard.ui.value
        assertThat(errored.stepIndex).isEqualTo(0)
        val notice = (errored.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.pairing_error_not_pairing)
        assertThat(notice.isHint).isTrue()
    }

    @Test
    fun `driver exception surfaces the same human copy, not the raw message`() = runTest {
        val driver = FakePairingDriver(
            handleIncoming = { _, _ -> throw IllegalArgumentException("not a pairing invite") },
        )
        val wizard = vm(WizardEntry.Redeemer, driver)
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("🔒garbage"); advanceUntilIdle()
        val stage = wizard.ui.value.stage as WizardStage.Receive
        assertThat(stage.error).isEqualTo(R.string.pairing_error_submit)
    }

    @Test
    fun `rejectMismatch deletes contact, session by username, and thread, then fails terminally`() = runTest {
        // fingerprintHex and username deliberately differ so the assertion below
        // proves which one sessionStore.remove actually received.
        val fp = "fp-mismatch"
        val username = "user-mismatch"
        val contact = Contact(fingerprintHex = fp, username = username, displayName = "Contact x", pairedAt = 0L)
        val forgotten = mutableListOf<String>()
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒invite") },
            handleIncoming = { _, _ -> IncomingOutcome.Completed(PairingCoordinator.CompleteOutcome(EMO, contact)) },
            forgetPeer = { forgotten += it },
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(contact)
        chatDb.dao().insert(ChatMessage(id = "m1", peerUsername = username, direction = ChatMessage.DIRECTION_OUT, body = "hi", timestamp = 0L))
        val sessionDao = FakeSessionStateDao()
        val wizard = vm(
            WizardEntry.Initiator,
            driver,
            repo = repo,
            sessionStore = RatchetSessionStore(dao = sessionDao),
        )

        wizard.start(); advanceUntilIdle()
        wizard.advanceFromShow()
        wizard.submitWire("🔒response"); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)

        wizard.rejectMismatch()
        // rejectMismatch's coroutine sets Failed only after all three deletes
        // (including the real Room clearThread write) complete, so observing
        // Failed here is proof the deletes already ran — see awaitStage's kdoc.
        val failed = awaitStage<WizardStage.Failed>(wizard)

        assertThat(repo.contacts.first().map { it.fingerprintHex }).doesNotContain(fp)
        // Locks the key choice: RatchetSessionStore is keyed by username (see
        // PairingWizardViewModel's kdoc) — NOT by fingerprintHex.
        assertThat(sessionDao.deleted).containsExactly(username)
        assertThat(chatDb.dao().observeThread(username).first()).isEmpty()
        assertThat(failed.messageRes).isEqualTo(R.string.pairing_mismatch_deleted)
        // (3) 回应记录一并清掉，否则该对端会被「已配对过」永久拦住。
        assertThat(forgotten).containsExactly(fp)
    }

    @Test
    fun `start is idempotent — a second call no-ops and preserves stage and state`() = runTest {
        // Regression for the M4 final-review I1 finding: `PairingWizardScreen`'s
        // `LaunchedEffect(Unit) { vm.start() }` re-fires on scan-detour
        // return-from-composition and on Activity recreation (rotation/dark
        // mode). Without the idempotency gate, a second start() would re-mint
        // a fresh invite and reset stepIndex to 0, blowing away an in-flight
        // Initiator wizard that already advanced past 出示.
        var startInviteCalls = 0
        val driver = FakePairingDriver(startInvite = { startInviteCalls++; inviteRecord("🔒invite-$startInviteCalls") })
        val wizard = vm(WizardEntry.Initiator, driver)

        wizard.start(); advanceUntilIdle()
        wizard.advanceFromShow() // moves to 接收(1), the state a second start() must not clobber
        val beforeReplay = wizard.ui.value
        assertThat(beforeReplay.stepIndex).isEqualTo(1)
        assertThat(beforeReplay.stage).isEqualTo(WizardStage.Receive(waitingForPeer = true))
        assertThat(startInviteCalls).isEqualTo(1)

        wizard.start(); advanceUntilIdle() // the LaunchedEffect(Unit) replay

        assertThat(startInviteCalls).isEqualTo(1) // no second mint
        assertThat(wizard.ui.value).isEqualTo(beforeReplay) // stage/state untouched
    }

    @Test
    fun `retry from a terminal failure restarts the whole wizard`() = runTest {
        val contact = Contact(fingerprintHex = "fp-c", username = "user-c", displayName = "Contact fp-c", pairedAt = 0L)
        var startInviteCalls = 0
        val driver = FakePairingDriver(
            startInvite = { startInviteCalls++; inviteRecord("🔒invite-$startInviteCalls") },
            handleIncoming = { _, _ -> IncomingOutcome.Completed(PairingCoordinator.CompleteOutcome(EMO, contact)) },
            forgetPeer = {},
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(contact)
        val sessionDao = FakeSessionStateDao()
        val wizard = vm(WizardEntry.Initiator, driver, repo = repo, sessionStore = RatchetSessionStore(dao = sessionDao))

        wizard.start(); advanceUntilIdle()
        wizard.advanceFromShow()
        wizard.submitWire("🔒response"); advanceUntilIdle()
        wizard.rejectMismatch()
        awaitStage<WizardStage.Failed>(wizard)

        wizard.retry(); advanceUntilIdle()

        assertThat(startInviteCalls).isEqualTo(2)
        val restarted = wizard.ui.value
        assertThat(restarted.stepIndex).isEqualTo(0)
        assertThat((restarted.stage as WizardStage.Show).wire).isEqualTo("🔒invite-2")
    }

    // ---- 表 W（计划头部）----

    private val contactX = Contact(fingerprintHex = "fp-x", username = "user-x", displayName = "Contact fp-x", pairedAt = 0L, safetyEmoji = EMO)
    private fun completed(c: Contact = contactX) = IncomingOutcome.Completed(PairingCoordinator.CompleteOutcome(EMO, c))
    private fun accepted(c: Contact = contactX) =
        IncomingOutcome.Accepted(PairingCoordinator.AcceptOutcome("🔒response-wire", EMO, c))

    private fun TestScope.titles(w: PairingWizardViewModel) = w.ui.value.stepTitles

    @Test
    fun `w01 redeemer pastes a response and lands on confirm with show-first titles`() = runTest {
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> completed() }))
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)
        assertThat(wizard.ui.value.stepIndex).isEqualTo(2)
        assertThat(titles(wizard)).isEqualTo(PairingWizardViewModel.SHOW_FIRST)
    }

    @Test
    fun `w02 initiator pastes someone else's invite and shows a response, original invite untouched`() = runTest {
        var deleted = 0
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine") },
            handleIncoming = { _, _ -> accepted() },
            deleteInvite = { deleted++ },
        )
        val wizard = vm(WizardEntry.Initiator, driver)
        wizard.start(); advanceUntilIdle()
        wizard.advanceFromShow()
        wizard.submitWire("🔒theirs"); advanceUntilIdle()
        val show = wizard.ui.value.stage as WizardStage.Show
        assertThat(show.isResponse).isTrue()
        assertThat(show.wire).isEqualTo("🔒response-wire")
        assertThat(wizard.ui.value.stepIndex).isEqualTo(1)
        assertThat(titles(wizard)).isEqualTo(PairingWizardViewModel.RECEIVE_FIRST)
        assertThat(deleted).isEqualTo(0)
    }

    @Test
    fun `w03 the four contactless rejections show the fixed copy as hints`() = runTest {
        val cases = listOf(
            IncomingRejection.NoMatchingInvite to R.string.pairing_error_no_matching_invite,
            IncomingRejection.SessionCiphertext to R.string.pairing_error_is_message,
            IncomingRejection.OwnInvite to R.string.pairing_error_own_code,
            IncomingRejection.NotPairingWire to R.string.pairing_error_not_pairing,
        )
        for ((reason, text) in cases) {
            val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> IncomingOutcome.Rejected(reason) }))
            wizard.start(); advanceUntilIdle()
            wizard.submitWire("🔒x"); advanceUntilIdle()
            val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
            assertThat(notice.textRes).isEqualTo(text)
            assertThat(notice.isHint).isTrue()
            assertThat(notice.contactFingerprintHex).isNull()
        }
    }

    @Test
    fun `w04 a handshake failure is marked as a failure with the existing constant`() = runTest {
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> throw IllegalStateException("boom") }))
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒x"); advanceUntilIdle()
        val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.pairing_error_submit)
        assertThat(notice.isHint).isFalse()
    }

    @Test
    fun `w05 resuming an unshared invite shows the stored wire`() = runTest {
        val driver = FakePairingDriver(pendingInvite = { id -> assertThat(id).isEqualTo("p-1"); inviteRecord("🔒stored", "p-1", note = "老周") })
        val wizard = vm(WizardEntry.ResumeInvite("p-1"), driver)
        wizard.start(); advanceUntilIdle()
        val show = wizard.ui.value.stage as WizardStage.Show
        assertThat(show.wire).isEqualTo("🔒stored")
        assertThat(show.isResponse).isFalse()
        assertThat(wizard.inviteId.value).isEqualTo("p-1")
        assertThat(wizard.note.value).isEqualTo("老周")
        assertThat(wizard.canResendInvite.value).isTrue()
    }

    @Test
    fun `w06 resuming a shared or migrated invite lands on receive at step 2`() = runTest {
        for (record in listOf(inviteRecord("🔒stored", lastSharedAtMillis = 5L), inviteRecord("", lastSharedAtMillis = null))) {
            val wizard = vm(WizardEntry.ResumeInvite("pairing-1"), FakePairingDriver(pendingInvite = { record }))
            wizard.start(); advanceUntilIdle()
            assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Receive::class.java)
            assertThat(wizard.ui.value.stepIndex).isEqualTo(1)
            assertThat(titles(wizard)).isEqualTo(PairingWizardViewModel.SHOW_FIRST)
        }
    }

    @Test
    fun `w07 resend from receive goes back to the same invite`() = runTest {
        val driver = FakePairingDriver(pendingInvite = { inviteRecord("🔒stored", lastSharedAtMillis = 5L) })
        val wizard = vm(WizardEntry.ResumeInvite("pairing-1"), driver)
        wizard.start(); advanceUntilIdle()
        wizard.backToShow()
        val show = wizard.ui.value.stage as WizardStage.Show
        assertThat(show.wire).isEqualTo("🔒stored")
        assertThat(show.isResponse).isFalse()
        assertThat(wizard.ui.value.stepIndex).isEqualTo(0)
    }

    @Test
    fun `w08 resuming a response shows it and next goes to confirm with the stored emoji`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val driver = FakePairingDriver(pendingResponse = { fp -> assertThat(fp).isEqualTo("fp-x"); responseRecord(fp, "🔒resp") })
        val wizard = vm(WizardEntry.ResumeResponse("fp-x"), driver, repo = repo)
        wizard.start(); advanceUntilIdle()
        val show = wizard.ui.value.stage as WizardStage.Show
        assertThat(show.wire).isEqualTo("🔒resp")
        assertThat(show.isResponse).isTrue()
        wizard.advanceFromShow()
        val confirm = wizard.ui.value.stage as WizardStage.Confirm
        assertThat(confirm.emoji).isEqualTo(EMO)
        assertThat(confirm.fingerprintHex).isEqualTo("fp-x")
    }

    @Test
    fun `w09 copy marks the invite or the response depending on what is shown`() = runTest {
        val marked = mutableListOf<String>()
        val invite = vm(
            WizardEntry.Initiator,
            FakePairingDriver(startInvite = { inviteRecord("🔒mine", "p-9") }, markInviteShared = { marked += "invite:$it" }),
        )
        invite.start(); advanceUntilIdle()
        invite.copied(); advanceUntilIdle()

        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val response = vm(
            WizardEntry.ResumeResponse("fp-x"),
            FakePairingDriver(pendingResponse = { responseRecord(it, "🔒r") }, markResponseShared = { marked += "response:$it" }),
            repo = repo,
        )
        response.start(); advanceUntilIdle()
        response.openThread.test {
            response.copied(); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo("user-x")
        }
        assertThat(marked).containsExactly("invite:p-9", "response:fp-x").inOrder()
        // remote hand-off: no Confirm, and the contact stays unverified
        assertThat(response.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
        assertThat(repo.contacts.first().single { it.fingerprintHex == "fp-x" }.verified).isFalse()
    }

    @Test
    fun `w10 a 27-byte note is clamped to 24 bytes before it is stored`() = runTest {
        val stored = mutableListOf<String>()
        val driver = FakePairingDriver(startInvite = { inviteRecord("🔒mine", "p-1") }, updateNote = { _, n -> stored += n })
        val wizard = vm(WizardEntry.Initiator, driver)
        wizard.start(); advanceUntilIdle()
        wizard.setNote("一二三四五六七八九"); advanceUntilIdle()
        assertThat(stored.last()).isEqualTo("一二三四五六七八")
        assertThat(wizard.note.value).isEqualTo("一二三四五六七八")
        // 控制字符：编排层会拒绝，所以根本不写。
        val before = stored.size
        wizard.setNote("a\nb"); advanceUntilIdle()
        assertThat(stored.size).isEqualTo(before)
    }

    @Test
    fun `w11 deleting the invite calls the driver and closes the wizard`() = runTest {
        val deleted = mutableListOf<String>()
        val driver = FakePairingDriver(startInvite = { inviteRecord("🔒mine", "p-1") }, deleteInvite = { deleted += it })
        val wizard = vm(WizardEntry.Initiator, driver)
        wizard.start(); advanceUntilIdle()
        wizard.closed.test {
            wizard.deleteInvite("p-1"); advanceUntilIdle()
            awaitItem()
        }
        assertThat(deleted).containsExactly("p-1")
    }

    @Test
    fun `w12 blank submit shows the not-pairing-wire hint`() = runTest {
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver())
        wizard.start(); advanceUntilIdle()
        wizard.submitWire(" \n ")
        val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.pairing_error_not_pairing)
        assertThat(notice.isHint).isTrue()
    }

    @Test
    fun `w13 a second submit before the first returns is ignored`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<IncomingOutcome>()
        var calls = 0
        val driver = FakePairingDriver(handleIncoming = { _, _ -> calls++; gate.await() })
        val wizard = vm(WizardEntry.Redeemer, driver)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒x"); advanceUntilIdle()
        wizard.submitWire("🔒x"); advanceUntilIdle()
        assertThat(calls).isEqualTo(1)
        gate.complete(completed()); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)
    }

    @Test
    fun `w14 already-paired rejection carries the contact fingerprint`() = runTest {
        val driver = FakePairingDriver(handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired("fp")) })
        val wizard = vm(WizardEntry.Redeemer, driver)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒x"); advanceUntilIdle()
        val stage = wizard.ui.value.stage as WizardStage.Receive
        assertThat(stage.notice!!.textRes).isEqualTo(R.string.pairing_error_already_paired)
        assertThat(stage.notice!!.isHint).isTrue()
        assertThat(stage.notice!!.contactFingerprintHex).isEqualTo("fp")
        assertThat(wizard.ui.value.stepIndex).isEqualTo(0)
    }

    // ---- 修复轮 1 ----

    @Test
    fun `resuming a record that no longer exists fails without a retry loop`() = runTest {
        val inv = vm(WizardEntry.ResumeInvite("gone"), FakePairingDriver(pendingInvite = { null }))
        inv.start(); advanceUntilIdle()
        assertThat((inv.ui.value.stage as WizardStage.Failed).retryable).isFalse()

        val resp = vm(WizardEntry.ResumeResponse("fp-gone"), FakePairingDriver(pendingResponse = { null }))
        resp.start(); advanceUntilIdle()
        assertThat((resp.ui.value.stage as WizardStage.Failed).retryable).isFalse()

        // 临时性的启动失败仍可重试。
        val mint = vm(WizardEntry.Initiator, FakePairingDriver(startInvite = { throw IllegalStateException("x") }))
        mint.start(); advanceUntilIdle()
        assertThat((mint.ui.value.stage as WizardStage.Failed).retryable).isTrue()
    }

    @Test
    fun `a rebuilt initiator wizard resumes its invite instead of minting another`() = runTest {
        // startInvite 只复用「没分享、没备注」的邀请：填过备注后重建会多出一份孤儿邀请，除非向导记住 id。
        val saved = androidx.lifecycle.SavedStateHandle()
        var mints = 0
        val first = vm(
            WizardEntry.Initiator,
            FakePairingDriver(startInvite = { mints++; inviteRecord("🔒mine", "p-1") }, updateNote = { _, _ -> }),
            saved = saved,
        )
        first.start(); advanceUntilIdle()
        first.setNote("老周"); advanceUntilIdle()

        val rebuilt = vm(
            WizardEntry.Initiator,
            FakePairingDriver(
                startInvite = { mints++; inviteRecord("🔒orphan", "p-2") },
                pendingInvite = { id -> assertThat(id).isEqualTo("p-1"); inviteRecord("🔒mine", "p-1", note = "老周") },
            ),
            saved = saved,
        )
        rebuilt.start(); advanceUntilIdle()

        assertThat(mints).isEqualTo(1)
        assertThat(rebuilt.inviteId.value).isEqualTo("p-1")
        assertThat(rebuilt.note.value).isEqualTo("老周")
        assertThat((rebuilt.ui.value.stage as WizardStage.Show).wire).isEqualTo("🔒mine")
    }

    @Test
    fun `a remembered invite that is gone falls back to a fresh one`() = runTest {
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("invite_id" to "p-gone"))
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(pendingInvite = { null }, startInvite = { inviteRecord("🔒fresh", "p-new") }),
            saved = saved,
        )
        wizard.start(); advanceUntilIdle()
        assertThat(wizard.inviteId.value).isEqualTo("p-new")
    }

    @Test
    fun `a failed delete tells the user and keeps the wizard open`() = runTest {
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine", "p-1") },
            deleteInvite = { throw IllegalStateException("disk") },
        )
        val wizard = vm(WizardEntry.Initiator, driver)
        wizard.start(); advanceUntilIdle()
        wizard.errors.test {
            wizard.deleteInvite("p-1"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.common_delete_failed)
        }
        assertThat(wizard.inviteId.value).isEqualTo("p-1")
    }

    @Test
    fun `rejectMismatch ignores a second tap while the first is running`() = runTest {
        val contact = Contact(fingerprintHex = "fp-m", username = "user-m", displayName = "Contact m", pairedAt = 0L)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var forgets = 0
        val driver = FakePairingDriver(
            handleIncoming = { _, _ -> IncomingOutcome.Completed(PairingCoordinator.CompleteOutcome(EMO, contact)) },
            forgetPeer = { forgets++; gate.await() },
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(contact)
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()

        wizard.rejectMismatch(); advanceUntilIdle()
        wizard.rejectMismatch(); advanceUntilIdle()
        assertThat(forgets).isEqualTo(1)

        gate.complete(Unit)
        awaitStage<WizardStage.Failed>(wizard)
        assertThat(forgets).isEqualTo(1)
    }

    @Test
    fun `rejectMismatch failing midway resets the guard so the next tap tries again`() = runTest {
        val contact = Contact(fingerprintHex = "fp-m", username = "user-m", displayName = "Contact m", pairedAt = 0L)
        var removes = 0
        val flakyDao = object : SessionStateDao {
            override suspend fun all(): List<SessionStateEntity> = emptyList()
            override suspend fun upsert(row: SessionStateEntity) = Unit
            override suspend fun delete(label: String) {
                removes++
                throw IllegalStateException("disk")
            }
            override suspend fun clear() = Unit
        }
        val driver = FakePairingDriver(
            handleIncoming = { _, _ -> IncomingOutcome.Completed(PairingCoordinator.CompleteOutcome(EMO, contact)) },
            forgetPeer = { },
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(contact)
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo, sessionStore = RatchetSessionStore(dao = flakyDao))
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()

        wizard.errors.test {
            wizard.rejectMismatch(); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.common_delete_failed)
            wizard.rejectMismatch(); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.common_delete_failed)
        }
        assertThat(removes).isEqualTo(2)
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)
    }

    @Test
    fun `deleteInvite ignores a second tap while the first is running`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var deletes = 0
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine", "p-1") },
            deleteInvite = { deletes++; gate.await() },
        )
        val wizard = vm(WizardEntry.Initiator, driver)
        wizard.start(); advanceUntilIdle()
        wizard.deleteInvite("p-1"); advanceUntilIdle()
        wizard.deleteInvite("p-1"); advanceUntilIdle()
        assertThat(deletes).isEqualTo(1)
        gate.complete(Unit); advanceUntilIdle()
        assertThat(deletes).isEqualTo(1)
    }

    // ---- Task 4: hand-off, paste, intake, verify later, discard ----

    @Test
    fun `share completion for the held invite advances to enter step and does not mark twice`() = runTest {
        val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        // markInviteShared is not wired: the receiver marks, the view model must not.
        val wizard = vm(WizardEntry.Initiator, FakePairingDriver(startInvite = { inviteRecord("🔒mine") }), shareCompleted = events)
        wizard.start(); advanceUntilIdle()
        assertThat(wizard.shareTarget.value).isEqualTo("invite" to "pairing-1")

        events.emit("pairing-1"); advanceUntilIdle()

        assertThat(wizard.ui.value.stage).isEqualTo(WizardStage.Receive(waitingForPeer = true))
        assertThat(wizard.ui.value.stepIndex).isEqualTo(1)
        assertThat(wizard.shareTarget.value).isNull()
    }

    @Test
    fun `share completion for another id is ignored`() = runTest {
        val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val wizard = vm(WizardEntry.Initiator, FakePairingDriver(startInvite = { inviteRecord("🔒mine") }), shareCompleted = events)
        wizard.start(); advanceUntilIdle()

        events.emit("pairing-other"); advanceUntilIdle()

        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
        assertThat(wizard.ui.value.stepIndex).isEqualTo(0)
    }

    @Test
    fun `copy marks the invite shared and advances`() = runTest {
        val marked = mutableListOf<String>()
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(startInvite = { inviteRecord("🔒mine") }, markInviteShared = { marked += it }),
        )
        wizard.start(); advanceUntilIdle()

        wizard.copied(); advanceUntilIdle()

        assertThat(marked).containsExactly("pairing-1")
        assertThat(wizard.ui.value.stage).isEqualTo(WizardStage.Receive(waitingForPeer = true))
        assertThat(wizard.ui.value.stepIndex).isEqualTo(1)
    }

    @Test
    fun `advancing after a qr scan keeps the invite`() = runTest {
        val marked = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(
                startInvite = { inviteRecord("🔒mine") },
                markInviteShared = { marked += it },
                discardUnsharedInvite = { discarded += it },
            ),
        )
        val store = owned(wizard)
        wizard.start(); advanceUntilIdle()

        wizard.advanceFromShow(); advanceUntilIdle()
        store.clear(); advanceUntilIdle()

        assertThat(marked).containsExactly("pairing-1")
        assertThat(discarded).isEmpty()
    }

    @Test
    fun `closing the wizard before sharing discards the invite`() = runTest {
        val discarded = mutableListOf<String>()
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(startInvite = { inviteRecord("🔒mine") }, discardUnsharedInvite = { discarded += it }),
        )
        val store = owned(wizard)
        wizard.start(); advanceUntilIdle()

        store.clear(); advanceUntilIdle()

        assertThat(discarded).containsExactly("pairing-1")
    }

    @Test
    fun `launching the share sheet then closing the wizard does not discard the invite`() = runTest {
        // MIUI-style chooser: the EXTRA_CHOSEN_COMPONENT callback never arrives.
        val discarded = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val presented = mutableListOf<String>()
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(
                startInvite = { inviteRecord("🔒mine") },
                markInviteShared = { marked += it },
                markInvitePresented = { presented += it },
                discardUnsharedInvite = { discarded += it },
            ),
        )
        val store = owned(wizard)
        wizard.start(); advanceUntilIdle()

        wizard.shareSheetLaunched(); advanceUntilIdle()
        // Still on the send step: only the callback (or copy / scanned) moves on and marks it shared.
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
        store.clear(); advanceUntilIdle()

        assertThat(presented).containsExactly("pairing-1")
        assertThat(discarded).isEmpty()
        assertThat(marked).isEmpty()
    }

    @Test
    fun `closing after resuming an invite whose share sheet was opened discards nothing`() = runTest {
        // Sheet opened, no callback (MIUI), then the wizard is reopened / rebuilt and closed untouched.
        val discarded = mutableListOf<String>()
        val wizard = vm(
            WizardEntry.ResumeInvite("pairing-1"),
            FakePairingDriver(
                pendingInvite = { inviteRecord("🔒mine", sheetPresentedAtMillis = 5L) },
                discardUnsharedInvite = { discarded += it },
            ),
        )
        val store = owned(wizard)
        wizard.start(); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)

        store.clear(); advanceUntilIdle()

        assertThat(discarded).isEmpty()
    }

    @Test
    fun `closing after resuming a shared invite discards nothing`() = runTest {
        val discarded = mutableListOf<String>()
        val wizard = vm(
            WizardEntry.ResumeInvite("pairing-1"),
            FakePairingDriver(
                pendingInvite = { inviteRecord("🔒mine", lastSharedAtMillis = 5L) },
                discardUnsharedInvite = { discarded += it },
            ),
        )
        val store = owned(wizard)
        wizard.start(); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isEqualTo(WizardStage.Receive(waitingForPeer = true))

        store.clear(); advanceUntilIdle()

        assertThat(discarded).isEmpty()
    }

    @Test
    fun `response share completion opens the thread without confirm`() = runTest {
        val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(
            WizardEntry.Redeemer,
            FakePairingDriver(handleIncoming = { _, _ -> accepted() }),
            repo = repo,
            shareCompleted = events,
        )
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒their-invite"); advanceUntilIdle()
        assertThat((wizard.ui.value.stage as WizardStage.Show).isResponse).isTrue()
        assertThat(wizard.shareTarget.value).isEqualTo("response" to "fp-x")

        wizard.openThread.test {
            events.emit("fp-x"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo("user-x")
        }
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
    }

    @Test
    fun `response share sheet opened then refocus opens the thread once`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> accepted() }), repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒their-invite"); advanceUntilIdle()

        wizard.wizardRefocused(); advanceUntilIdle() // sheet never opened: nothing
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)

        wizard.openThread.test {
            wizard.shareSheetLaunched(); advanceUntilIdle()
            expectNoEvents()
            wizard.wizardStopped() // left for the chat app
            wizard.wizardRefocused(); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo("user-x")
            wizard.wizardStopped()
            wizard.wizardRefocused(); advanceUntilIdle()
            expectNoEvents()
        }
        assertThat(repo.contacts.first().single { it.fingerprintHex == "fp-x" }.verified).isFalse()
    }

    @Test
    fun `cancelling the reply share sheet (resume without stop) stays on the reply send step`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> accepted() }), repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒their-invite"); advanceUntilIdle()

        wizard.openThread.test {
            wizard.shareSheetLaunched(); advanceUntilIdle()
            wizard.wizardRefocused(); advanceUntilIdle() // chooser cancelled: only paused
            expectNoEvents()
            // The sheet is gone now: a later trip away (lock / app switch) is not a send either.
            wizard.wizardStopped()
            wizard.wizardRefocused(); advanceUntilIdle()
            expectNoEvents()
        }
        assertThat((wizard.ui.value.stage as WizardStage.Show).isResponse).isTrue()
    }

    @Test
    fun `response scan hand-off still goes to confirm`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> accepted() }), repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒their-invite"); advanceUntilIdle()
        wizard.advanceFromShow()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)
        assertThat(wizard.ui.value.stepIndex).isEqualTo(2)
    }

    @Test
    fun `paste of a recognized code submits immediately`() = runTest {
        val seen = mutableListOf<String>()
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { raw, _ -> seen += raw; accepted() }))
        wizard.start(); advanceUntilIdle()

        wizard.pasteFromClipboard("header line\n🔒other-invite"); advanceUntilIdle()

        assertThat(seen).hasSize(1)
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
    }

    @Test
    fun `paste of an empty clipboard shows a hint`() = runTest {
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver())
        wizard.start(); advanceUntilIdle()
        wizard.pasteFromClipboard(null)
        assertThat((wizard.ui.value.stage as WizardStage.Receive).notice!!.textRes)
            .isEqualTo(R.string.intake_error_clipboard_empty)
    }

    @Test
    fun `input change with a recognized code submits`() = runTest {
        var calls = 0
        val wizard = vm(
            WizardEntry.Redeemer,
            FakePairingDriver(handleIncoming = { _, _ -> calls++; IncomingOutcome.Rejected(IncomingRejection.NoMatchingInvite) }),
        )
        wizard.start(); advanceUntilIdle()

        wizard.onInputChanged("hello"); advanceUntilIdle() // not recognized: nothing happens
        assertThat(calls).isEqualTo(0)
        assertThat((wizard.ui.value.stage as WizardStage.Receive).notice).isNull()

        wizard.onInputChanged("🔒code"); advanceUntilIdle()
        assertThat(calls).isEqualTo(1)
        wizard.onInputChanged("🔒code"); advanceUntilIdle() // the same text is not resubmitted
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `encrypted message pasted into the code field opens its thread`() = runTest {
        val intake = FakeIntake(
            classify = { IntakeKind.Message(it) },
            handle = { IntakeOutcome.OpenThread("bob") },
        )
        // handleIncoming not wired: calling it would fail the expectations below.
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(), intake = intake)
        wizard.start(); advanceUntilIdle()

        wizard.openThread.test {
            wizard.pasteFromClipboard("🔒message"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo("bob")
        }
        assertThat(intake.handled).containsExactly("🔒message")
    }

    @Test
    fun `encrypted message that fails shows the intake reason`() = runTest {
        val intake = FakeIntake(
            classify = { IntakeKind.Message(it) },
            handle = { IntakeOutcome.Failed(IntakeFailure.CANNOT_DECRYPT) },
        )
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(), intake = intake)
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("🔒message"); advanceUntilIdle()

        val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.intake_error_cannot_decrypt)
    }

    @Test
    fun `an intake that throws shows the save failure`() = runTest {
        val intake = FakeIntake(classify = { IntakeKind.Message(it) }, handle = { throw IllegalStateException("db") })
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(), intake = intake)
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("🔒message"); advanceUntilIdle()

        assertThat((wizard.ui.value.stage as WizardStage.Receive).notice!!.textRes)
            .isEqualTo(IntakeFailure.SAVE_FAILED.messageRes)
    }

    @Test
    fun `incomplete text shows the incomplete hint without calling the driver`() = runTest {
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(), intake = FakeIntake(classify = { IntakeKind.Incomplete }))
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("🔒 header only"); advanceUntilIdle()

        val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.intake_error_incomplete)
        assertThat(notice.isHint).isTrue()
    }

    @Test
    fun `text that is not ours shows the not-pairing hint without calling the driver`() = runTest {
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver())
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("hello"); advanceUntilIdle()
        assertThat((wizard.ui.value.stage as WizardStage.Receive).notice!!.textRes)
            .isEqualTo(R.string.pairing_error_not_pairing)
    }

    @Test
    fun `confirmMatch with a name renames then verifies then opens the thread`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> completed() }), repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()

        wizard.openThread.test {
            wizard.confirmMatch("  Old Wang  "); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo("user-x")
        }
        val stored = repo.contacts.first().single { it.fingerprintHex == "fp-x" }
        assertThat(stored.displayName).isEqualTo("Old Wang")
        assertThat(stored.verified).isTrue()
    }

    @Test
    fun `verifyLater keeps the contact unverified and opens the thread`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> completed() }), repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()

        wizard.openThread.test {
            wizard.verifyLater("Lao Zhou"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo("user-x")
        }
        val stored = repo.contacts.first().single { it.fingerprintHex == "fp-x" }
        assertThat(stored.displayName).isEqualTo("Lao Zhou")
        assertThat(stored.verified).isFalse()
    }

    @Test
    fun `verifyLater without a name keeps the default name`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(contactX)
        val wizard = vm(WizardEntry.Redeemer, FakePairingDriver(handleIncoming = { _, _ -> completed() }), repo = repo)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()
        wizard.openThread.test {
            wizard.verifyLater("   "); advanceUntilIdle()
            awaitItem()
        }
        assertThat(repo.contacts.first().single().displayName).isEqualTo(contactX.displayName)
    }

    @Test
    fun `an invite submitted on the invite show step becomes a response and the unshared invite is discarded`() = runTest {
        val discarded = mutableListOf<String>()
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine") },
            handleIncoming = { wire, _ -> assertThat(wire).isEqualTo("🔒theirs"); accepted() },
            discardUnsharedInvite = { discarded += it },
        )
        val wizard = vm(WizardEntry.Initiator, driver, intake = FakeIntake())
        wizard.start(); advanceUntilIdle()
        assertThat((wizard.ui.value.stage as WizardStage.Show).isResponse).isFalse()

        wizard.submitWire("🔒theirs"); advanceUntilIdle()

        val show = wizard.ui.value.stage as WizardStage.Show
        assertThat(show.isResponse).isTrue()
        assertThat(show.wire).isEqualTo("🔒response-wire")
        assertThat(discarded).containsExactly("pairing-1")
        assertThat(wizard.inviteId.value).isNull()
    }

    @Test
    fun `an invite submitted on the show step keeps an invite whose share sheet was opened`() = runTest {
        val discarded = mutableListOf<String>()
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine") },
            markInvitePresented = {},
            handleIncoming = { _, _ -> accepted() },
            discardUnsharedInvite = { discarded += it },
        )
        val wizard = vm(WizardEntry.Initiator, driver)
        val store = owned(wizard)
        wizard.start(); advanceUntilIdle()
        wizard.shareSheetLaunched(); advanceUntilIdle()

        wizard.submitWire("🔒theirs"); advanceUntilIdle()
        store.clear(); advanceUntilIdle()

        assertThat((wizard.ui.value.stage as WizardStage.Show).isResponse).isTrue()
        assertThat(discarded).isEmpty()
    }

    @Test
    fun `a response submitted on the invite show step completes to the verify step`() = runTest {
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(
                startInvite = { inviteRecord("🔒mine") },
                handleIncoming = { _, _ -> completed() },
                discardUnsharedInvite = {},
            ),
        )
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("🔒reply"); advanceUntilIdle()

        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)
    }

    @Test
    fun `the response show step ignores submitted wires`() = runTest {
        var calls = 0
        val wizard = vm(
            WizardEntry.Redeemer,
            FakePairingDriver(handleIncoming = { _, _ -> calls++; accepted() }),
        )
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒first"); advanceUntilIdle()
        assertThat(calls).isEqualTo(1)

        wizard.submitWire("🔒second"); advanceUntilIdle()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `a rejected wire on the show step keeps the show step and says why`() = runTest {
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(startInvite = { inviteRecord("🔒mine") }),
            intake = FakeIntake(),
        )
        wizard.start(); advanceUntilIdle()

        wizard.errors.test {
            wizard.submitWire("   "); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(PairingCopy.NOT_PAIRING_WIRE)
        }
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
    }

    private fun TestScope.mutualWizard(acceptedDigest: String?, deleted: MutableList<String> = mutableListOf()): PairingWizardViewModel {
        val peer = Contact(
            fingerprintHex = "fp-m", username = "user-m", displayName = "M", pairedAt = 0L,
            acceptedInviteDigest = acceptedDigest,
        )
        val repo = CcRepository.forTest()
        runBlocking { repo.upsertContact(peer) }
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine", "p-1") },
            handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired("fp-m")) },
            deleteInvite = { deleted += it },
            // Their messages already decrypt here (the response went with the first one): outside the
            // mutual-invite window.
            pendingResponse = { null },
        )
        return vm(WizardEntry.Initiator, driver, repo = repo, intake = FakeIntake())
    }

    @Test
    fun `inside the mutual-invite window the held invite is not offered for deletion`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(Contact(fingerprintHex = "fp-m", username = "user-m", displayName = "M", pairedAt = 0L, acceptedInviteDigest = "d"))
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine", "p-1") },
            handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired("fp-m")) },
            // My response to them is still on file: their reply to my invite may still arrive and pick it.
            pendingResponse = { fp -> PairingResponseRecord(fp, "🔒reply", "d", 0L) },
        )
        val wizard = vm(WizardEntry.Initiator, driver, repo = repo, intake = FakeIntake())
        wizard.start(); advanceUntilIdle()
        wizard.errors.test {
            wizard.submitWire("🔒theirs"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.pairing_error_already_paired)
        }
        assertThat(wizard.inviteId.value).isEqualTo("p-1")
    }

    @Test
    fun `mutual invites resolved for theirs release the retired invite and explain on receive`() = runTest {
        val driver = FakePairingDriver(
            pendingInvite = { inviteRecord("🔒mine", "p-1", lastSharedAtMillis = 5L) },
            handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.MutualInvite("fp-m", "p-1")) },
        )
        val wizard = vm(WizardEntry.ResumeInvite("p-1"), driver, intake = FakeIntake())
        wizard.start(); advanceUntilIdle()
        assertThat((wizard.ui.value.stage as WizardStage.Receive).waitingForPeer).isTrue()

        wizard.submitWire("🔒resp"); advanceUntilIdle()

        val stage = wizard.ui.value.stage as WizardStage.Receive
        assertThat(stage.notice).isEqualTo(
            ReceiveNotice(R.string.pairing_mutual_invite_resolved, isHint = true, contactFingerprintHex = "fp-m"),
        )
        assertThat(stage.waitingForPeer).isFalse()
        assertThat(wizard.inviteId.value).isNull()
    }

    @Test
    fun `mutual invites resolved for theirs on the invite show step move to receive`() = runTest {
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine", "p-1", sheetPresentedAtMillis = 5L) },
            handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.MutualInvite("fp-m", "p-1")) },
        )
        val wizard = vm(WizardEntry.Initiator, driver, intake = FakeIntake())
        wizard.start(); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)

        wizard.submitWire("🔒resp"); advanceUntilIdle()

        val stage = wizard.ui.value.stage as WizardStage.Receive
        assertThat(stage.notice?.textRes).isEqualTo(R.string.pairing_mutual_invite_resolved)
        assertThat(stage.notice?.contactFingerprintHex).isEqualTo("fp-m")
        assertThat(wizard.inviteId.value).isNull()
    }

    @Test
    fun `pasting a reply from someone whose invite I accepted offers to delete my invite`() = runTest {
        val wizard = mutualWizard(acceptedDigest = "d")
        wizard.start(); advanceUntilIdle()
        wizard.actionNotices.test {
            wizard.submitWire("🔒resp"); advanceUntilIdle()
            val n = awaitItem()
            assertThat(n.textRes).isEqualTo(R.string.pairing_mutual_invite)
            assertThat(n.deleteInviteId).isEqualTo("p-1")
        }
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
    }

    @Test
    fun `the mutual-invite action deletes the held invite and closes the wizard`() = runTest {
        val deleted = mutableListOf<String>()
        val wizard = mutualWizard(acceptedDigest = "d", deleted = deleted)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()
        wizard.closed.test {
            wizard.deleteInvite("p-1"); advanceUntilIdle()
            awaitItem()
        }
        assertThat(deleted).containsExactly("p-1")
    }

    @Test
    fun `a mutual reply pasted into a fresh wizard offers to delete the matched invite only`() = runTest {
        val peer = Contact(
            fingerprintHex = "fp-m", username = "user-m", displayName = "M", pairedAt = 0L, acceptedInviteDigest = "d",
        )
        val repo = CcRepository.forTest()
        repo.upsertContact(peer)
        val dao = FakeSessionStateDao()
        val deleted = mutableListOf<String>()
        val driver = FakePairingDriver(
            handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired("fp-m", matchedPairingId = "p-old")) },
            deleteInvite = { deleted += it },
        )
        // The paste bar opens the wizard on its enter step with the wire; the wizard holds no invite.
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo, sessionStore = RatchetSessionStore(dao = dao), intake = FakeIntake())
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()

        val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.pairing_mutual_invite)
        assertThat(notice.deleteInviteId).isEqualTo("p-old")
        assertThat(wizard.inviteId.value).isNull()

        wizard.closed.test {
            wizard.deleteInvite(notice.deleteInviteId!!); advanceUntilIdle()
            awaitItem()
        }
        assertThat(deleted).containsExactly("p-old")
        assertThat(repo.contacts.first()).containsExactly(peer)
        assertThat(dao.deleted).isEmpty()
    }

    @Test
    fun `an already-paired reply without a matched invite in a fresh wizard stays plain`() = runTest {
        val repo = CcRepository.forTest()
        repo.upsertContact(Contact(fingerprintHex = "fp-m", username = "user-m", displayName = "M", pairedAt = 0L, acceptedInviteDigest = "d"))
        val driver = FakePairingDriver(handleIncoming = { _, _ -> IncomingOutcome.Rejected(IncomingRejection.AlreadyPaired("fp-m")) })
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo, intake = FakeIntake())
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()
        val notice = (wizard.ui.value.stage as WizardStage.Receive).notice!!
        assertThat(notice.textRes).isEqualTo(R.string.pairing_error_already_paired)
        assertThat(notice.deleteInviteId).isNull()
        assertThat(notice.contactFingerprintHex).isEqualTo("fp-m")
    }

    @Test
    fun `an already-paired reply from a contact I did not accept keeps the plain message`() = runTest {
        val wizard = mutualWizard(acceptedDigest = null)
        wizard.start(); advanceUntilIdle()
        wizard.errors.test {
            wizard.submitWire("🔒resp"); advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.pairing_error_already_paired)
        }
    }

    @Test
    fun `paste on the invite show step submits the clipboard text`() = runTest {
        var seen: String? = null
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(
                startInvite = { inviteRecord("🔒mine") },
                handleIncoming = { w, _ -> seen = w; accepted() },
                discardUnsharedInvite = {},
            ),
        )
        wizard.start(); advanceUntilIdle()

        wizard.pasteFromClipboard("🔒theirs"); advanceUntilIdle()

        assertThat(seen).isEqualTo("🔒theirs")
    }

    @Test
    fun `otherAwaitingInvites counts waiting invites except the one on screen`() = runTest {
        val now = System.currentTimeMillis()
        fun rec(id: String, shared: Long? = now) =
            PendingPairingRecord(id, "n", now, inviteWire = "w", lastSharedAtMillis = shared)
        val invites = MutableStateFlow(listOf(rec("pairing-1", shared = null), rec("other-a"), rec("other-b")))
        val wizard = vm(
            WizardEntry.Initiator,
            FakePairingDriver(startInvite = { inviteRecord("🔒mine") }),
            pendingInvites = invites,
        )
        wizard.start(); advanceUntilIdle()

        wizard.otherAwaitingInvites.test {
            assertThat(expectMostRecentItem()).isEqualTo(2)
        }
    }

    @Test
    fun `step titles are resource ids`() {
        assertThat(PairingWizardViewModel.SHOW_FIRST)
            .isEqualTo(listOf(R.string.pairing_step_send, R.string.pairing_step_enter, R.string.pairing_step_verify))
        assertThat(PairingWizardViewModel.RECEIVE_FIRST)
            .isEqualTo(listOf(R.string.pairing_step_enter, R.string.pairing_step_send, R.string.pairing_step_verify))
    }

    // ---- 问名字（Task 6）----

    private class NameBox(var name: String = "", var done: Boolean = false) {
        val saved = mutableListOf<String?>()
        fun save(n: String?) { saved += n; done = true; if (n != null) name = n }
    }

    private fun TestScope.askingVm(
        entry: WizardEntry,
        driver: FakePairingDriver,
        box: NameBox,
        intake: FakeIntake = FakeIntake(),
    ) = vm(entry, driver, intake = intake, myName = { box.name }, namePromptDone = { box.done }, saveName = box::save)

    private val responseIntake = FakeIntake(classify = { raw -> IntakeKind.PairingResponse(raw) })

    @Test
    fun `n01 initiator with no name asks before startInvite, then startInvite gets the typed name`() = runTest {
        val box = NameBox()
        var started: String? = null
        val driver = FakePairingDriver(startInvite = { n -> started = n; inviteRecord("🔒invite") })
        val wizard = askingVm(WizardEntry.Initiator, driver, box)

        wizard.start(); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isEqualTo(WizardStage.AskName)
        assertThat(started).isNull()

        wizard.submitName("小明"); advanceUntilIdle()
        assertThat(started).isEqualTo("小明")
        assertThat(box.saved).containsExactly("小明")
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
    }

    @Test
    fun `n02 skipping marks done, saves no name, and still starts the invite`() = runTest {
        val box = NameBox()
        var started: String? = null
        val driver = FakePairingDriver(startInvite = { n -> started = n; inviteRecord("🔒invite") })
        val wizard = askingVm(WizardEntry.Initiator, driver, box)

        wizard.start(); advanceUntilIdle()
        wizard.skipName(); advanceUntilIdle()
        assertThat(started).isEqualTo("")
        assertThat(box.saved).containsExactly(null as String?)
        assertThat(box.done).isTrue()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
    }

    @Test
    fun `n03 initiator never asks when a name exists or the prompt was already done`() = runTest {
        for (box in listOf(NameBox(name = "A"), NameBox(done = true))) {
            val driver = FakePairingDriver(startInvite = { inviteRecord("🔒invite") })
            val wizard = askingVm(WizardEntry.Initiator, driver, box)
            wizard.start(); advanceUntilIdle()
            assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)
        }
    }

    @Test
    fun `n04 redeemer pasting an invite asks first and keeps the held wire through the prompt`() = runTest {
        val box = NameBox()
        val contactB = Contact(fingerprintHex = "fp-b", username = "user-b", displayName = "B", pairedAt = 0L)
        var seen: Pair<String, String>? = null
        val driver = FakePairingDriver(handleIncoming = { w, n ->
            seen = w to n
            IncomingOutcome.Accepted(PairingCoordinator.AcceptOutcome("🔒response", EMO, contactB))
        })
        val repo = CcRepository.forTest().also { it.upsertContact(contactB) }
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo, myName = { box.name }, namePromptDone = { box.done }, saveName = box::save)
        wizard.start(); advanceUntilIdle()

        wizard.submitWire("🔒invite-from-a"); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isEqualTo(WizardStage.AskName)
        assertThat(seen).isNull()

        wizard.submitName("小红"); advanceUntilIdle()
        assertThat(seen).isEqualTo("🔒invite-from-a" to "小红")
        assertThat((wizard.ui.value.stage as WizardStage.Show).isResponse).isTrue()
    }

    @Test
    fun `n05 redeemer skipping still processes the held invite with a blank name`() = runTest {
        val box = NameBox()
        val contactB = Contact(fingerprintHex = "fp-b", username = "user-b", displayName = "B", pairedAt = 0L)
        var seen: Pair<String, String>? = null
        val driver = FakePairingDriver(handleIncoming = { w, n ->
            seen = w to n
            IncomingOutcome.Accepted(PairingCoordinator.AcceptOutcome("🔒response", EMO, contactB))
        })
        val repo = CcRepository.forTest().also { it.upsertContact(contactB) }
        val wizard = vm(WizardEntry.Redeemer, driver, repo = repo, myName = { box.name }, namePromptDone = { box.done }, saveName = box::save)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒invite-from-a"); advanceUntilIdle()
        wizard.skipName(); advanceUntilIdle()
        assertThat(seen).isEqualTo("🔒invite-from-a" to "")
        assertThat(box.done).isTrue()
    }

    @Test
    fun `n06 a reply never asks for a name`() = runTest {
        val box = NameBox()
        val driver = FakePairingDriver(handleIncoming = { _, _ -> completed() })
        val wizard = askingVm(WizardEntry.Redeemer, driver, box, intake = responseIntake)
        wizard.start(); advanceUntilIdle()
        wizard.submitWire("🔒resp"); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Confirm::class.java)
        assertThat(box.saved).isEmpty()
    }

    @Test
    fun `n07 an invite arriving on the show stage asks, then is processed and the held invite discarded`() = runTest {
        val box = NameBox()
        val contactB = Contact(fingerprintHex = "fp-b", username = "user-b", displayName = "B", pairedAt = 0L)
        var seen: Pair<String, String>? = null
        val discarded = mutableListOf<String>()
        val driver = FakePairingDriver(
            startInvite = { inviteRecord("🔒mine") },
            handleIncoming = { w, n ->
                seen = w to n
                IncomingOutcome.Accepted(PairingCoordinator.AcceptOutcome("🔒response", EMO, contactB))
            },
            discardUnsharedInvite = { discarded += it },
        )
        val repo = CcRepository.forTest().also { it.upsertContact(contactB) }
        val wizard = vm(WizardEntry.Initiator, driver, repo = repo, myName = { box.name }, namePromptDone = { box.done }, saveName = box::save)
        wizard.start(); advanceUntilIdle()
        wizard.skipName(); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isInstanceOf(WizardStage.Show::class.java)

        box.done = false // user cleared it elsewhere; the gate re-applies on the Show stage
        wizard.submitWire("🔒theirs"); advanceUntilIdle()
        assertThat(wizard.ui.value.stage).isEqualTo(WizardStage.AskName)
        assertThat(seen).isNull()

        wizard.submitName("阿明"); advanceUntilIdle()
        assertThat(seen).isEqualTo("🔒theirs" to "阿明")
        assertThat(discarded).containsExactly("pairing-1")
        assertThat((wizard.ui.value.stage as WizardStage.Show).isResponse).isTrue()
    }

    @Test
    fun `n08 a blank or control-character name counts as skip`() = runTest {
        val box = NameBox()
        var started: String? = null
        val driver = FakePairingDriver(startInvite = { n -> started = n; inviteRecord("🔒invite") })
        val wizard = askingVm(WizardEntry.Initiator, driver, box)
        wizard.start(); advanceUntilIdle()
        wizard.submitName("   "); advanceUntilIdle()
        assertThat(started).isEqualTo("")
        assertThat(box.saved).containsExactly(null as String?)
    }
}
