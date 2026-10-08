package app.chencang.android.ui.chat

import app.chencang.android.media.relaysOf

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.chencang.android.ui.pairing.FakeIntake
import app.chencang.shared.AppPrefs
import app.chencang.shared.R
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.WireLocator
import app.chencang.shared.chat.inTransactionRunner
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.crypto.SessionCrypto
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.i18n.UiText
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.intake.IntakeOutcome
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.media.MediaDownloader
import app.chencang.shared.media.MediaFailure
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.media.MediaLimits
import app.chencang.shared.media.MediaRejected
import app.chencang.shared.media.MediaSender
import app.chencang.shared.media.MediaTransport
import app.chencang.shared.media.OutgoingFailureReason
import app.chencang.shared.media.OutgoingMediaStatus
import app.chencang.shared.media.PreparedMedia
import app.chencang.shared.media.UniffiMediaCrypto
import app.chencang.shared.model.Contact
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private fun contact(u: String, at: Long = 0L) = Contact(
    fingerprintHex = "fp-$u", username = u, displayName = u, pairedAt = at,
)

/** 恒等「加密」:密文 = 明文帧,`decryptFromBytesAny` 恒定说发件人是 alice。 */
private class IdentityCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray) = plaintext
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) =
        SessionCrypto.DecryptedAnyBytes(senderKey = "alice", plaintext = ciphertext)
}

/** 会话丢失:NoSessionForPeer 回归夹具(见原测试说明)。 */
private class NoSessionCrypto : SessionCrypto {
    override suspend fun encryptToBytes(peerUsername: String, plaintext: ByteArray): ByteArray =
        throw RatchetSessionStore.NoSessionForPeer(peerUsername)
    override suspend fun decryptFromBytes(peerUsername: String, ciphertext: ByteArray) = ciphertext
    override suspend fun decryptFromBytesAny(ciphertext: ByteArray) = null
}

/**
 * 不碰 ContentResolver：图片直接吐一个小文件；`content://test/video/long` 按「太长」拒掉，
 * 其它视频 Uri 模拟解码器崩溃（非 MediaRejected 异常）。
 */
private class FakePreparer(private val dir: File) : MediaPreparer {
    var discarded = 0
    private var counter = 0

    override suspend fun image(uri: Uri): PreparedMedia = if ("broken" in uri.toString()) {
        throw IllegalStateException("decoder exploded")
    } else PreparedMedia(
        kind = MediaConstants.KIND_IMAGE,
        file = File(dir, "img-${System.nanoTime()}.jpg").apply {
            val seed = counter++ // 每张内容不同，转发测试才能分辨发的是哪一张
            writeBytes(ByteArray(2_048) { (it + seed * 7).toByte() })
        },
        durMs = 0,
        width = 640,
        height = 480,
    )

    override suspend fun video(uri: Uri): PreparedMedia =
        if ("long" in uri.toString()) throw MediaRejected(MediaLimits.VIDEO_TOO_LONG) else throw java.io.IOException("codec")

    override fun mimeOf(uri: Uri): String = if ("video" in uri.toString()) "video/mp4" else "image/jpeg"

    override fun discardCaptures() {
        discarded++
    }
}

private val COPIED_TOAST = UiText.Res(R.string.status_copied_toast)
private val SHARED_TOAST = UiText.Res(R.string.status_shared_toast)

/** Typed text in these tests is never ours; the paste tests inject their own classification. */
private fun plainIntake(
    classify: (String) -> IntakeKind = { if (it.isBlank()) IntakeKind.Incomplete else IntakeKind.NotOurs },
    handle: suspend (CharSequence?) -> IntakeOutcome = { error("unexpected intake.handle") },
) = FakeIntake(classify, handle)

/** Anything with a lock and more than the lock is a message; a lone lock is incomplete. */
private val messageOnLock: (String) -> IntakeKind = { raw ->
    when {
        raw.trim() == "🔒" -> IntakeKind.Incomplete
        raw.contains("🔒") -> IntakeKind.Message("🔒W")
        raw.isBlank() -> IntakeKind.Incomplete
        else -> IntakeKind.NotOurs
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Stock Application — skips CcApp's eager keystore-touching init (no
// AndroidKeyStore provider on the host JVM); this test only needs a Context.
@Config(application = Application::class)
class ConversationViewModelTest {
    // A single dispatcher shared between Dispatchers.Main and runTest — see the
    // original note: viewModelScope work must run on a scheduler runTest drives.
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository
    private lateinit var files: MediaFiles
    private lateinit var server: MockWebServer
    private lateinit var workDir: File
    private lateinit var prefs: AppPrefs

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // JNA one-time bootstrap scans the whole (large) :app classpath — pay it outside runTest.
        uniffi.chencang.encodeWire(byteArrayOf())
        val context = ApplicationProvider.getApplicationContext<Context>()
        workDir = File(context.cacheDir, "vm-test-${System.nanoTime()}").apply { mkdirs() }
        files = MediaFiles(File(workDir, "media"))
        context.getSharedPreferences("chencang_app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPrefs(context)
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(IdentityCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = db.inTransactionRunner(),
            shareHeaders = FakeShareHeaders,
        )
        server = MockWebServer().apply { start() }
        // Room's first-write cold start can exceed turbine's window — pay it here.
        runBlocking {
            db.dao().apply {
                insert(ChatMessage(id = "warmup", peerUsername = "warmup", direction = ChatMessage.DIRECTION_OUT, body = "warmup", timestamp = 0L))
                clearAll()
            }
        }
    }

    @After
    fun tearDown() {
        // 先清 ViewModel（取消 viewModelScope、停掉还在排队的 Room 事务），再关库——
        // 反过来会让挂着的事务撞上已关闭的库，异常漏到下一个 runTest（终审 I1 的 flake 根因）。
        vmStore.clear()
        server.shutdown()
        db.close()
        workDir.deleteRecursively()
        Dispatchers.resetMain()
        ShadowMediaPlayer.resetStaticState()
    }

    private val vmStore = ViewModelStore()
    private var vmCount = 0

    /** 经 [ViewModelStore] 创建，tearDown 的 `vmStore.clear()` 才能真正 `onCleared` + 取消 viewModelScope。 */
    private fun newVm(
        r: ChatRepository = repo,
        contacts: Flow<List<Contact>> = flowOf(emptyList()),
        clock: () -> Long = System::currentTimeMillis,
        preparer: FakePreparer = FakePreparer(workDir),
        pollClock: () -> Long = System::currentTimeMillis,
        intake: FakeIntake = plainIntake(),
    ): ConversationViewModel {
        val vm = buildVm(r, contacts, clock, preparer, pollClock, intake)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        return ViewModelProvider(vmStore, factory)["vm-${vmCount++}", ConversationViewModel::class.java]
    }

    /** 交给上传引擎的 messageId（VM 测试不跑上传：seal 即可分享，上传是另一段）。 */
    private val scheduledUploads = mutableListOf<String>()

    /** 手动重试交给上传引擎「现在就试」（enqueueNow）的 messageId。 */
    private val uploadedNow = mutableListOf<String>()

    private fun buildVm(
        r: ChatRepository,
        contacts: Flow<List<Contact>>,
        clock: () -> Long,
        preparer: FakePreparer,
        pollClock: () -> Long,
        intake: FakeIntake,
    ): ConversationViewModel {
        val transport = MediaTransport(
            relays = relaysOf(server),
        )
        val sender = MediaSender(
            dao = db.dao(),
            mediaDao = db.mediaDao(),
            files = files,
            crypto = UniffiMediaCrypto,
            transport = transport,
            sealFrame = { peer, refs -> r.sealMediaFrame(peer, refs) },
            inTransaction = db.inTransactionRunner(),
            uploadScheduler = { scheduledUploads += it },
            uploadNow = { uploadedNow += it },
            shareHeaders = FakeShareHeaders,
        )
        val downloader = MediaDownloader(db.dao(), db.mediaDao(), files, UniffiMediaCrypto, transport, now = clock)
        return ConversationViewModel(
            r, "alice", contacts, sender, downloader, preparer, prefs, intake, pollClock,
            intakeDispatcher = dispatcher,
        )
    }

    @Test
    fun `seal shows a ready text card whose share text is the fixed first line plus the wire`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hello\nsecond line")
        assertThat(card.phase).isEqualTo(ConversationViewModel.SealCard.Phase.READY)
        assertThat(card.peer).isEqualTo("alice")
        val lines = card.shareText.split("\n")
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo("TH")
        assertThat(lines[1]).isEqualTo(db.dao().getById(card.messageId)!!.shareText)
        assertThat(WireLocator.extract(card.shareText)).isEqualTo(lines[1])
        assertThat(card.summary).isEqualTo(UiText.Raw("hello"))
        assertThat(vm.draft.value).isEmpty()
    }

    @Test
    fun `seal on missing session sets sendError, keeps draft, does not crash`() = runTest(dispatcher) {
        val brokenRepo = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(NoSessionCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = db.inTransactionRunner(),
            shareHeaders = FakeShareHeaders,
        )
        val vm = newVm(r = brokenRepo)
        vm.onDraftChanged("hello")
        vm.seal()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.card.value).isNull()
        assertThat(vm.draft.value).isEqualTo("hello")
        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.media_failure_session_lost))
    }

    @Test
    fun `a long first line is truncated in the card summary`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "   \n" + "字".repeat(30))
        assertThat(card.summary).isEqualTo(UiText.Raw("字".repeat(24) + "…"))
    }

    @Test
    fun `contact 按 username 命中`() = runTest(dispatcher) {
        val vm = newVm(contacts = flowOf(listOf(contact("bob"), contact("alice"))))
        assertEquals("alice", vm.contact.filterNotNull().first().username)
    }

    @Test
    fun `otherContacts excludes the current peer`() = runTest(dispatcher) {
        val vm = newVm(contacts = flowOf(listOf(contact("bob"), contact("alice"))))
        vm.otherContacts.test(timeout = 10.seconds) {
            var list = awaitItem()
            while (list.isEmpty()) list = awaitItem()
            assertThat(list.map { it.username }).containsExactly("bob")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendPicked image uploads, seals and requests a share of the R1 text`() = runTest(dispatcher) {
        server.enqueue(MockResponse().setBody("""{"url":"${server.url("/put/1")}","expires_in":300}"""))
        server.enqueue(MockResponse().setResponseCode(200))
        val vm = newVm()
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendPicked(listOf(Uri.parse("content://test/image/1")))
            val req = awaitItem()
            assertThat(req.peer).isEqualTo("alice")
            val lines = req.text.split("\n")
            assertThat(lines).hasSize(2)
            assertThat(lines[0]).startsWith("MH:2:1:")
            assertThat(lines[1]).startsWith("🔒")
            cancelAndIgnoreRemainingEvents()
        }
        val card = vm.card.value!!
        assertThat(card.shareText).contains("\n")
        assertThat(card.summary).isEqualTo(UiText.Res(R.string.media_preview_image))
        assertThat(card.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        // 自动弹面板不等于已送出：气泡保留「封缄」印，直到面板报告选了目标 App 或用户复制
        assertThat(db.dao().getById(card.messageId)!!.status).isEqualTo(ChatMessage.STATUS_SEALED)
    }

    @Test
    fun `a video over 60 s is stopped at selection with the spec message and nothing is sent`() = runTest(dispatcher) {
        val vm = newVm()
        vm.sendError.test(timeout = 10.seconds) {
            assertThat(awaitItem()).isNull()
            vm.sendPicked(listOf(Uri.parse("content://test/video/long")))
            assertThat(awaitItem()).isEqualTo(UiText.Res(R.string.media_video_too_long))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `sendPicked requests the share before any network request and hands the message to the upload engine`() = runTest(dispatcher) {
        // 先分享、后上传：中转就算会回 429，也挡不住分享面板——上传是另一段，不经过发送路径。
        server.enqueue(MockResponse().setResponseCode(429))
        val vm = newVm()
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendPicked(listOf(Uri.parse("content://test/image/1")))
            val req = awaitItem()
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(scheduledUploads).containsExactly(req.messageId)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.sendError.value).isNull()
    }

    // ---- 先分享、后上传（Task 3）----

    private fun voice() = PreparedMedia(
        kind = MediaConstants.KIND_VOICE,
        file = File(workDir, "v-${System.nanoTime()}.ogg").apply { writeBytes(ByteArray(64) { it.toByte() }) },
        durMs = 2_000,
        width = 0,
        height = 0,
    )

    @Test
    fun `a sealed voice shows its card and asks for the share sheet with zero relay requests`() = runTest(dispatcher) {
        val vm = newVm()
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendVoice(voice())
            val req = awaitItem()
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(vm.card.value!!.messageId).isEqualTo(req.messageId)
            assertThat(scheduledUploads).containsExactly(req.messageId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 发一张图、交出分享面板，再把它的项打成「上传失败」（= 上传引擎放弃后的样子）。 */
    private suspend fun sharedImageThatFailedToUpload(vm: ConversationViewModel): ConversationViewModel.ShareRequest {
        lateinit var req: ConversationViewModel.ShareRequest
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendPicked(listOf(Uri.parse("content://test/image/1")))
            req = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // 面板已经回来（这里借「没有 App 可接」收尾，不走宽限计时）：之后同一条消息再请求分享就会真的弹出，
        // 下面「不再弹面板」的断言才有意义（面板还在台上时重复请求本来就会被去重吞掉）。
        vm.shareUnavailable(req)
        vm.consumeSendError()
        db.mediaDao().failUnsealed(req.messageId)
        return req
    }

    @Test
    fun `retrying a message the peer cannot see yet re-uploads without a new share sheet or card change`() = runTest(dispatcher) {
        val vm = newVm()
        val req = sharedImageThatFailedToUpload(vm)
        val cardBefore = vm.card.value
        val row = vm.rows.first { rows -> rows.any { it.outStatus == OutgoingMediaStatus.NOT_VISIBLE_TO_PEER } }
            .single { it.message.id == req.messageId }

        vm.shareRequests.test(timeout = 3.seconds) {
            vm.onFailureTapped(row).join()
            expectNoEvents()
        }

        assertThat(uploadedNow).containsExactly(req.messageId)
        assertThat(vm.card.value).isEqualTo(cardBefore)
        assertThat(db.dao().getById(req.messageId)!!.shareText).isEqualTo(req.text)
        assertThat(db.mediaDao().forMessage(req.messageId).single().state).isEqualTo(MediaItem.STATE_UPLOADING)
        assertThat(vm.sendError.value).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `tapping a lost file only explains it and never re-uploads`() = runTest(dispatcher) {
        val vm = newVm()
        val req = sharedImageThatFailedToUpload(vm)
        assertThat(files.cca(req.messageId, 0).delete()).isTrue()
        val lost = OutgoingMediaStatus.PermanentlyFailed(OutgoingFailureReason.FILE_MISSING)
        val row = vm.rows.first { rows -> rows.any { it.outStatus == lost } }
            .single { it.message.id == req.messageId }

        vm.onFailureTapped(row).join()

        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.media_failure_file_missing))
        assertThat(uploadedNow).isEmpty()
        assertThat(db.mediaDao().forMessage(req.messageId).single().state).isEqualTo(MediaItem.STATE_FAILED)
    }

    @Test
    fun `tapping a too-large upload only explains it and never re-uploads`() = runTest(dispatcher) {
        // 终审 F2：.cca 还在，但 413 已记为永久失败——终态，点击只提示同一句话。
        val vm = newVm()
        val req = sharedImageThatFailedToUpload(vm)
        db.mediaDao().failPermanently(req.messageId, 0, MediaFailure.TOO_LARGE.name)
        val tooLarge = OutgoingMediaStatus.PermanentlyFailed(OutgoingFailureReason.TOO_LARGE)
        val row = vm.rows.first { rows -> rows.any { it.outStatus == tooLarge } }
            .single { it.message.id == req.messageId }

        vm.onFailureTapped(row).join()

        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.media_failure_too_large))
        assertThat(uploadedNow).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
        val item = db.mediaDao().forMessage(req.messageId).single()
        assertThat(item.state).isEqualTo(MediaItem.STATE_FAILED)
        assertThat(item.uploadFailure).isEqualTo(MediaFailure.TOO_LARGE.name)
    }

    @Test
    fun `only the last row of an outgoing album carries the message status`() = runTest(dispatcher) {
        val vm = newVm()
        vm.sendPicked(listOf(Uri.parse("content://test/image/1"), Uri.parse("content://test/image/2")))
        val rows = vm.rows.first { it.size == 2 && it.last().outStatus == OutgoingMediaStatus.UPLOADING }
        assertThat(rows.first().outStatus).isNull()
    }

    @Test
    fun `keepMediaFresh auto-downloads a pending incoming image while the thread is visible`() = runTest(dispatcher) {
        val blob = UniffiMediaCrypto.encrypt(ByteArray(1_000) { 3 }, MediaConstants.KIND_IMAGE)
        db.mediaDao().insertAll(
            listOf(
                MediaItem(
                    messageId = "in1", index = 0, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 10, height = 10,
                    byteLen = blob.blob.size.toLong(), blobSecret = blob.blobSecret, blobId = blob.blobId,
                    state = MediaItem.STATE_PENDING,
                ),
            ),
        )
        db.dao().insert(
            ChatMessage("in1", "alice", ChatMessage.DIRECTION_IN, "", System.currentTimeMillis(), kind = ChatMessage.KIND_IMAGE),
        )
        server.enqueue(MockResponse().setBody(Buffer().write(blob.blob)))
        val vm = newVm()
        backgroundScope.launch { vm.keepMediaFresh() }
        vm.rows.test(timeout = 10.seconds) {
            var rows = awaitItem()
            while (rows.firstOrNull()?.item?.state != MediaItem.STATE_READY) rows = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(server.takeRequest().path).isEqualTo("/b/${blob.blobId}")
    }

    // ---- 先分享、后上传 spec §2：接收端「等待对方上传」轮询 ----

    /**
     * 这组测试会阻塞测试线程（`takeRequest` / [awaitPollerParked]）：库操作一律 `runBlocking`，不在测试协程里挂起——
     * 否则 Unconfined 调度会让测试体在 Room 的事务线程上恢复，阻塞它就把之后的查询全卡死。
     */
    private fun incomingImage(id: String, state: String) = runBlocking {
        val blob = UniffiMediaCrypto.encrypt(ByteArray(1_000) { 3 }, MediaConstants.KIND_IMAGE)
        db.mediaDao().insertAll(
            listOf(
                MediaItem(
                    messageId = id, index = 0, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 10, height = 10,
                    byteLen = blob.blob.size.toLong(), blobSecret = blob.blobSecret, blobId = blob.blobId, state = state,
                ),
            ),
        )
        db.dao().insert(
            ChatMessage(id, "alice", ChatMessage.DIRECTION_IN, "", System.currentTimeMillis(), kind = ChatMessage.KIND_IMAGE),
        )
    }

    private fun stateOf(id: String) = runBlocking { db.mediaDao().get(id, 0)!!.state }

    /**
     * pollClock 的读数。[AwaitingPoller] 开窗时读一次、每次 `delay` 前后各读一次，而请求与落库都在两次读之间——
     * 所以读数为正偶数时，循环正停在下一次 `delay` 上（真 IO 线程上的那次请求已收完尾）。
     */
    private val pollReads = AtomicInteger()

    private fun TestScope.countingPollClock(): () -> Long = {
        pollReads.incrementAndGet()
        currentTime
    }

    /**
     * 等轮询收完尾、停到下一次 `delay` 上（终审 F5：取代原来的 400 ms 真睡眠）。有界：5 s 等不到就失败。
     * 只在刚确认过一次请求已发出（读数必为奇数）之后调用。
     */
    private fun awaitPollerParked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val n = pollReads.get()
            if (n > 0 && n % 2 == 0) return
            check(System.nanoTime() < deadline) { "轮询 5 s 内没停到下一次 delay（pollClock 读数 $n）" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `a visible thread retries an awaiting item right away and then on schedule, and stops when hidden`() = runTest(dispatcher) {
        incomingImage("in1", MediaItem.STATE_AWAITING)
        repeat(5) { server.enqueue(MockResponse().setResponseCode(403)) }
        val vm = newVm(pollClock = countingPollClock())

        vm.onThreadVisible()
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        awaitPollerParked()
        assertThat(stateOf("in1")).isEqualTo(MediaItem.STATE_AWAITING)
        advanceTimeBy(9_000)
        assertThat(server.requestCount).isEqualTo(1) // 循环停在 10 s 的 delay 上：9 s 时不会有请求
        advanceTimeBy(1_001)
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        awaitPollerParked()

        vm.onThreadHidden()
        advanceTimeBy(600_000)
        assertThat(server.requestCount).isEqualTo(2) // 隐藏时循环停在 delay 上、被取消：之后不会再有请求
    }

    @Test
    fun `an item that turns awaiting while visible polls from 10 s without an immediate refetch`() = runTest(dispatcher) {
        incomingImage("in1", MediaItem.STATE_PENDING)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(403)) }
        val vm = newVm(pollClock = countingPollClock())
        vm.onThreadVisible()
        backgroundScope.launch { vm.keepMediaFresh() } // 自动下载 → 403 → 等待对方上传
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        // 自动下载那次不经轮询：等它落库 awaiting、track 开窗（读 1 次）并停到首个 delay（读 2 次）。
        awaitPollerParked()
        assertThat(stateOf("in1")).isEqualTo(MediaItem.STATE_AWAITING)
        assertThat(server.requestCount).isEqualTo(1)
        advanceTimeBy(10_001)
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
    }

    @Test
    fun `awaiting items are not polled while the thread is not visible, and a tap retries at once`() = runTest(dispatcher) {
        incomingImage("in1", MediaItem.STATE_AWAITING)
        server.enqueue(MockResponse().setResponseCode(403))
        val vm = newVm(pollClock = countingPollClock())
        advanceTimeBy(600_000)
        assertThat(pollReads.get()).isEqualTo(0) // 从没开过轮询窗口
        assertThat(server.requestCount).isEqualTo(0)

        vm.retryAwaiting("in1", 0) // 点「还没收到文件 · 点击重试」
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        awaitPollerParked()
        vm.onThreadHidden()
    }

    @Test
    fun `a crashing decoder shows the unreadable message instead of crashing, and captures are cleaned`() = runTest(dispatcher) {
        val prep = FakePreparer(workDir)
        val vm = newVm(preparer = prep)
        vm.sendError.test(timeout = 10.seconds) {
            assertThat(awaitItem()).isNull()
            vm.sendPicked(listOf(Uri.parse("content://test/image/broken")))
            assertThat(awaitItem()).isEqualTo(UiText.Res(R.string.media_image_unreadable))
            vm.consumeSendError()
            assertThat(awaitItem()).isNull()
            vm.sendCapturedVideo(Uri.parse("content://test/video/1"))
            assertThat(awaitItem()).isEqualTo(UiText.Res(R.string.media_video_unreadable))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(prep.discarded).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `keepMediaFresh marks an untapped video older than 24h expired without any request`() = runTest(dispatcher) {
        db.mediaDao().insertAll(
            listOf(
                MediaItem(
                    messageId = "v1", index = 0, kind = MediaConstants.KIND_VIDEO, durMs = 9_000, width = 10, height = 10,
                    byteLen = 5_000L, blobSecret = ByteArray(32) { 1 }, blobId = "V".repeat(22),
                    state = MediaItem.STATE_PENDING,
                ),
            ),
        )
        db.dao().insert(ChatMessage("v1", "alice", ChatMessage.DIRECTION_IN, "", 1_000L, kind = ChatMessage.KIND_VIDEO))
        val vm = newVm(clock = { 1_000L + 86_400_000L })
        backgroundScope.launch { vm.keepMediaFresh() }
        vm.rows.test(timeout = 10.seconds) {
            var rows = awaitItem()
            while (rows.firstOrNull()?.item?.state != MediaItem.STATE_EXPIRED) rows = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `rows expose one row per image of a sent album`() = runTest(dispatcher) {
        repeat(2) {
            server.enqueue(MockResponse().setBody("""{"url":"${server.url("/put/$it")}","expires_in":300}"""))
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val vm = newVm()
        vm.sendPicked(listOf(Uri.parse("content://test/image/1"), Uri.parse("content://test/image/2")))
        vm.rows.test(timeout = 10.seconds) {
            var rows = awaitItem()
            while (rows.size < 2) rows = awaitItem()
            assertThat(rows.map { it.item!!.index }).containsExactly(0, 1).inOrder()
            assertThat(rows.map { it.message.id }.toSet()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // `ShadowMediaPlayer` drives the real `VoicePlayer` under `vm.voicePlayer` end to end
    // (register a fake `MediaInfo` for the path, then call `toggle` for real) — no fake/
    // interface needed, matching the technique in `VoicePlayerTest`.
    @Test
    fun `delete stops playback of a voice message that belongs to it`() = runTest(dispatcher) {
        val path = File(workDir, "voice.bin").apply { writeBytes(ByteArray(10)) }.absolutePath
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(1_000, 0))
        val vm = newVm()
        vm.voicePlayer.toggle("v1:0", path)
        assertThat(vm.voicePlayer.playing.value).isEqualTo("v1:0")

        vm.delete("v1").join()

        assertThat(vm.voicePlayer.playing.value).isNull()
    }

    @Test
    fun `deleting a different message leaves another message's playback alone`() = runTest(dispatcher) {
        val path = File(workDir, "voice2.bin").apply { writeBytes(ByteArray(10)) }.absolutePath
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), ShadowMediaPlayer.MediaInfo(1_000, 0))
        val vm = newVm()
        vm.voicePlayer.toggle("v1:0", path)
        assertThat(vm.voicePlayer.playing.value).isEqualTo("v1:0")

        vm.delete("other-message").join()

        assertThat(vm.voicePlayer.playing.value).isEqualTo("v1:0")
        vm.voicePlayer.stop()
    }

    // ---- 先分享、后上传 Task 2：上传归 WorkManager，进线程/回前台不再抢它的项 ----

    @Test
    fun `a cold-start refreshMediaStates leaves a shared message's uploading items alone`() = runTest(dispatcher) {
        // 进程被杀后冷启动进线程：已分享的消息项停在 uploading（WorkManager 会接着传），busy 是空的。
        db.mediaDao().insertAll(
            listOf(0, 1).map { i ->
                MediaItem(
                    messageId = "s1", index = i, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 10, height = 10,
                    byteLen = 60L, blobSecret = ByteArray(32) { 1 }, blobId = "S".repeat(21) + i,
                    state = if (i == 0) MediaItem.STATE_SEALED else MediaItem.STATE_UPLOADING,
                )
            },
        )
        db.dao().insert(
            ChatMessage(
                "s1", "alice", ChatMessage.DIRECTION_OUT, "", 1_000L,
                kind = ChatMessage.KIND_IMAGE, shareText = "MH:2:2:X\n🔒wire",
            ),
        )
        val vm = newVm()

        vm.refreshMediaStates()

        assertThat(db.mediaDao().forMessage("s1").map { it.state })
            .containsExactly(MediaItem.STATE_SEALED, MediaItem.STATE_UPLOADING).inOrder()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a cold-start refreshMediaStates still marks an interrupted seal failed`() = runTest(dispatcher) {
        db.mediaDao().insertAll(
            listOf(
                MediaItem(
                    messageId = "u1", index = 0, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 10, height = 10,
                    byteLen = 0L, blobSecret = ByteArray(0), blobId = "", state = MediaItem.STATE_ENCRYPTING,
                ),
            ),
        )
        db.dao().insert(ChatMessage("u1", "alice", ChatMessage.DIRECTION_OUT, "", 1_000L, kind = ChatMessage.KIND_IMAGE))
        val vm = newVm()

        vm.refreshMediaStates()

        assertThat(db.mediaDao().forMessage("u1").single().state).isEqualTo(MediaItem.STATE_FAILED)
    }

    @Test
    fun `delete cancels the message's upload before its files are deleted`() = runTest(dispatcher) {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_OUT, "", 1_000L, kind = ChatMessage.KIND_IMAGE))
        files.write(files.cca("m1", 0), ByteArray(8))
        val cancelled = mutableListOf<Pair<String, Boolean>>()
        val cancelling = ChatRepository(
            dao = db.dao(),
            sessions = SessionManager(IdentityCrypto()),
            mediaDao = db.mediaDao(),
            mediaFiles = files,
            inTransaction = db.inTransactionRunner(),
            cancelUpload = { id -> cancelled += id to files.cca(id, 0).exists() },
            shareHeaders = FakeShareHeaders,
        )
        val vm = newVm(r = cancelling)

        vm.delete("m1").join()

        assertThat(cancelled).containsExactly("m1" to true)
        assertThat(files.dir("m1").exists()).isFalse()
    }

    // ---- 终审 I1：viewModelScope 里的意外异常不能带崩进程，要变成一句提示 ----

    @Test
    fun `a DAO failure while sending voice surfaces an error instead of crashing`() = runTest(dispatcher) {
        // 媒体表不在了：MediaSender 的第一次 DAO 写就会抛 SQLiteException（MediaSender 刻意不吞这种异常）。
        db.openHelper.writableDatabase.execSQL("DROP TABLE media_item")
        val vm = newVm()
        val voice = PreparedMedia(
            kind = MediaConstants.KIND_VOICE,
            file = File(workDir, "v.ogg").apply { writeBytes(ByteArray(64)) },
            durMs = 2_000,
            width = 0,
            height = 0,
        )
        vm.sendError.test(timeout = 10.seconds) {
            assertThat(awaitItem()).isNull()
            vm.sendVoice(voice).join()
            assertThat(awaitItem()).isEqualTo(UiText.Res(R.string.thread_send_failed))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a media folder that cannot be deleted surfaces 删除失败 instead of crashing`() = runTest(dispatcher) {
        db.dao().insert(ChatMessage("m1", "alice", ChatMessage.DIRECTION_IN, "", System.currentTimeMillis(), kind = ChatMessage.KIND_IMAGE))
        val dir = files.dir("m1").apply { mkdirs() }
        File(dir, "0.bin").writeBytes(ByteArray(8))
        dir.setWritable(false) // 里面的文件删不掉 → MediaFiles.deleteMessage 抛 IOException
        try {
            val vm = newVm()
            vm.sendError.test(timeout = 10.seconds) {
                assertThat(awaitItem()).isNull()
                vm.delete("m1").join()
                assertThat(awaitItem()).isEqualTo(UiText.Res(R.string.common_delete_failed))
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `forwarding shares the new message but leaves this thread's seal card alone`() = runTest(dispatcher) {
        repeat(2) {
            server.enqueue(MockResponse().setBody("""{"url":"${server.url("/put/$it")}","expires_in":300}"""))
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val vm = newVm()
        val shares = mutableListOf<ConversationViewModel.ShareRequest>()
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendPicked(listOf(Uri.parse("content://test/image/1")))
            shares += awaitItem()
            val original = vm.card.value!!.messageId
            vm.dismissSealed()
            vm.onPaused(); vm.onResumed() // 第一个面板回来了，下一个才弹
            vm.forward(original, null, "bob", "bob").join()
            shares += awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // 转发出去的是 bob 线程的消息：本线程（alice）不能冒出一张属于 bob 的密文卡。
        assertThat(vm.card.value).isNull()
        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.media_forwarded_to, listOf("bob")))
        val bobMsg = db.dao().observeThread("bob").first().single()
        assertThat(shares[1].text).startsWith("MH:2:")
        // 完成回调标的是 bob 线程里的那条
        assertThat(shares[1].messageId).isEqualTo(bobMsg.id)
        assertThat(shares[1].peer).isEqualTo("bob")
    }

    @Test
    fun `forwarding one album image passes the index through and sends only that item`() = runTest(dispatcher) {
        repeat(3) {
            server.enqueue(MockResponse().setBody("""{"url":"${server.url("/put/$it")}","expires_in":300}"""))
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val vm = newVm()
        var originalId = ""
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendPicked(listOf(Uri.parse("content://test/image/1"), Uri.parse("content://test/image/2")))
            awaitItem()
            originalId = vm.card.value!!.messageId
            vm.dismissSealed()
            vm.onPaused(); vm.onResumed()
            vm.forward(originalId, listOf(1), "bob", "bob").join()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        val forwarded = db.dao().observeThread("bob").first().single()
        val sent = db.mediaDao().forMessage(forwarded.id).single()
        val originals = db.mediaDao().forMessage(originalId).sortedBy { it.index }
        assertThat(File(sent.localPath!!).readBytes()).isEqualTo(File(originals[1].localPath!!).readBytes())
        assertThat(File(sent.localPath!!).readBytes()).isNotEqualTo(File(originals[0].localPath!!).readBytes())
    }

    // `deliver` is `internal` (not `private`) exactly so this test can hand it an `InProgress`
    // directly — the real race that produces one (two `send`/`retry` calls for the same message
    // overlapping on `MediaSender`'s IO dispatcher) isn't reliably reproducible on a JVM unit
    // test's virtual-time scheduler.
    @Test
    fun `deliver ignores InProgress - no toast, no card change, no share request`() = runTest(dispatcher) {
        val vm = newVm()
        vm.shareRequests.test(timeout = 3.seconds) {
            vm.deliver(MediaSender.Result.InProgress("m1"))
            expectNoEvents()
        }
        assertThat(vm.sendError.value).isNull()
        assertThat(vm.card.value).isNull()
    }

    // ---- 分享热桥（Task 8）：面板报告选了目标 App 或复制才标已送出；一次一个面板 ----

    /**
     * Seals [text]. Unless [autoShare], the remembered action is set to copy first so sealing only
     * shows the card: the hand-off mechanics below are tested without the share sheet that sealing
     * opens on its own under the share preference (covered by the dedicated tests).
     */
    private suspend fun sealText(vm: ConversationViewModel, text: String, autoShare: Boolean = false): ConversationViewModel.SealCard {
        val before = vm.card.value?.messageId
        if (!autoShare) prefs.setSealAction(AppPrefs.SealAction.COPY)
        vm.onDraftChanged(text)
        vm.seal()
        return vm.card.filter { it != null && it.messageId != before }.first()!!
    }

    private suspend fun awaitStatus(id: String, want: String) {
        db.dao().observeThread("alice").filter { list -> list.any { it.id == id && it.status == want } }.first()
    }

    private suspend fun ownRow(id: String): ChatMessage = db.dao().getById(id)!!

    /*
     * 虚拟时间用例（宽限 / 看门狗）里的 Room 读写一律走 runBlocking：在 runTest 体里直接挂起等 Room，
     * 测试体会在 Room 的 IO 线程上被 Unconfined 恢复，之后 VM 里新 launch 的协程会被那个 Unconfined
     * 事件循环推迟，delay 的起点就不再是调用那一刻。runBlocking 让测试体始终留在测试线程上。
     */
    private fun sealTextNow(vm: ConversationViewModel, text: String) = runBlocking { sealText(vm, text) }
    private fun rowNow(id: String) = runBlocking { db.dao().getById(id)!! }

    @Test
    fun `tapping share only opens the sheet - nothing is marked sent`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            val req = awaitItem()
            assertThat(req.messageId).isEqualTo(card.messageId)
            assertThat(req.peer).isEqualTo("alice")
            assertThat(req.text).isEqualTo(card.shareText)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        assertThat(ownRow(card.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
        assertThat(prefs.sealAction.value).isEqualTo(AppPrefs.SealAction.SHARE)
    }

    @Test
    fun `coming back without a chosen target leaves the card as not sent`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealTextNow(vm, "hi")
        vm.shareCard()
        vm.onPaused()
        vm.onResumed()
        // 宽限期内不急着说「未发出」——系统回调可能还在路上
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        advanceTimeBy(ConversationViewModel.NOT_SENT_GRACE_MS - 1)
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        advanceTimeBy(2)
        vm.card.filter { it?.phase == ConversationViewModel.SealCard.Phase.NOT_SENT }.first()!!
        assertThat(ownRow(card.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
        // 可以再分享
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            assertThat(awaitItem().messageId).isEqualTo(card.messageId)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
    }

    @Test
    fun `a chosen-target completion closes the card and says shared`() = runTest(dispatcher) {
        val vm = newVm()
        backgroundScope.launch { vm.watchHandOffs() }
        val card = sealText(vm, "hi")
        vm.shareCard()
        // 等价于 ShareCompletionReceiver 收到回调
        assertThat(repo.markSentIfOwned(card.messageId, "alice")).isEqualTo(ChatRepository.MarkResult.MARKED)
        vm.card.filter { it == null }.first()
        vm.sendError.filter { it == SHARED_TOAST }.first()
        // 面板回来时卡片已经收了，不会变成「未发出」
        vm.onPaused()
        vm.onResumed()
        assertThat(vm.card.value).isNull()
    }

    @Test
    fun `a completion for another message of this thread still says shared and keeps the current card`() = runTest(dispatcher) {
        val vm = newVm()
        backgroundScope.launch { vm.watchHandOffs() }
        val first = sealText(vm, "one")
        val second = sealText(vm, "two")
        repo.markSentIfOwned(first.messageId, "alice")
        vm.sendError.filter { it == SHARED_TOAST }.first()
        assertThat(vm.card.value!!.messageId).isEqualTo(second.messageId)
    }

    @Test
    fun `copy on the card returns the two-line text, marks copied, closes the card and remembers copy`() = runTest(dispatcher) {
        val vm = newVm()
        backgroundScope.launch { vm.watchHandOffs() }
        val card = sealText(vm, "hi", autoShare = true)
        assertThat(prefs.sealAction.value).isEqualTo(AppPrefs.SealAction.SHARE)
        val text = vm.copyCard()
        assertThat(text).isEqualTo(card.shareText)
        assertThat(text).startsWith("TH\n")
        assertThat(vm.card.value).isNull()
        awaitStatus(card.messageId, ChatMessage.STATUS_COPIED)
        assertThat(vm.sendError.value).isEqualTo(COPIED_TOAST)
        assertThat(prefs.sealAction.value).isEqualTo(AppPrefs.SealAction.COPY)
        assertThat(AppPrefs(ApplicationProvider.getApplicationContext()).sealAction.value).isEqualTo(AppPrefs.SealAction.COPY)
    }

    @Test
    fun `a failed clipboard write marks nothing and says so`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        assertThat(vm.copyCard { false }).isNull()
        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.common_copy_failed))
        assertThat(vm.card.value!!.messageId).isEqualTo(card.messageId)
        assertThat(ownRow(card.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
    }

    @Test
    fun `returning to the thread without having left the app does not drop the pending sheet`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            awaitItem()
            vm.onResumed() // 观察者重新挂上时补发的 ON_RESUME（导航回来，没离开前台）
            assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
            vm.shareMessage(ownRow(card.messageId)) // 同一条，不重复
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting the message on the seal card closes the card, drops its queued sheet and leaves copy inert`() = runTest(dispatcher) {
        val vm = newVm()
        val first = sealText(vm, "one")
        val second = sealText(vm, "two")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareMessage(ownRow(first.messageId))
            awaitItem() // first 的面板在台上
            vm.shareCard() // second（卡片上的那条）排队
            vm.delete(second.messageId).join()
            assertThat(vm.card.value).isNull()
            assertThat(vm.copyCard()).isNull() // 复制不会把已删消息的分享文本写进剪贴板
            vm.onPaused()
            vm.onResumed()
            expectNoEvents() // 它排队的面板也撤掉了
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.message(second.messageId)).isNull()
    }

    @Test
    fun `deleting another message leaves the seal card alone`() = runTest(dispatcher) {
        val vm = newVm()
        val first = sealText(vm, "one")
        val second = sealText(vm, "two")
        vm.delete(first.messageId).join()
        assertThat(vm.card.value!!.messageId).isEqualTo(second.messageId)
        assertThat(vm.copyCard()).isEqualTo(second.shareText)
    }

    @Test
    fun `dismissing the card does not mark it sent`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.dismissSealed()
        assertThat(vm.card.value).isNull()
        assertThat(ownRow(card.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
    }

    @Test
    fun `long-press on own text bubble shares with the fixed first line and copy marks it copied`() = runTest(dispatcher) {
        val vm = newVm()
        backgroundScope.launch { vm.watchHandOffs() }
        val card = sealText(vm, "hi")
        vm.dismissSealed() // 卡片没了，气泡上照样能交出
        val row = ownRow(card.messageId)
        assertThat(vm.handOffText(row)).isEqualTo("TH\n" + row.shareText!!)
        vm.shareRequests.test(timeout = 10.seconds) {
            assertThat(vm.shareMessage(row)).isTrue()
            val req = awaitItem()
            assertThat(req.messageId).isEqualTo(row.id)
            assertThat(req.text).isEqualTo("TH\n" + row.shareText!!)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(ownRow(row.id).status).isEqualTo(ChatMessage.STATUS_SEALED)

        assertThat(vm.copyMessage(row)).isEqualTo("TH\n" + row.shareText!!)
        awaitStatus(row.id, ChatMessage.STATUS_COPIED)
        assertThat(vm.sendError.value).isEqualTo(COPIED_TOAST)
        vm.consumeSendError()
        // Copying a shared message again never downgrades it to copied.
        repo.markSentIfOwned(row.id, "alice")
        vm.sendError.filter { it == SHARED_TOAST }.first() // the share completion is announced
        vm.consumeSendError()
        vm.copyMessage(ownRow(row.id))
        assertThat(vm.sendError.value).isEqualTo(COPIED_TOAST)
        assertThat(rowNow(row.id).status).isEqualTo(ChatMessage.STATUS_SENT)
    }

    @Test
    fun `bubble hand-off is only for own text rows that kept their wire`() = runTest(dispatcher) {
        val vm = newVm()
        val incoming = ChatMessage("in-1", "alice", ChatMessage.DIRECTION_IN, "hey", 1L, shareText = "🔒W")
        val legacy = ChatMessage("old-1", "alice", ChatMessage.DIRECTION_OUT, "old", 1L, shareText = null)
        assertThat(vm.handOffText(incoming)).isNull()
        assertThat(vm.handOffText(legacy)).isNull()
        assertThat(ConversationViewModel.canReshare(incoming)).isFalse()
        assertThat(ConversationViewModel.canReshare(legacy)).isFalse()
        assertThat(ConversationViewModel.canReshare(ChatMessage("o", "alice", ChatMessage.DIRECTION_OUT, "x", 1L, shareText = "🔒W")))
            .isTrue()
        assertThat(vm.shareMessage(incoming)).isFalse()
        assertThat(vm.shareMessage(legacy)).isFalse()
        // 收到的消息「复制密文」照旧给定位出的 wire，不标任何东西
        db.dao().insert(incoming)
        assertThat(vm.copyMessage(incoming)).isEqualTo("🔒W")
        assertThat(vm.sendError.value).isEqualTo(COPIED_TOAST)
        assertThat(ownRow("in-1").status).isEqualTo(ChatMessage.STATUS_SEALED)
    }

    @Test
    fun `shares queue one at a time and the next opens only after returning to the app`() = runTest(dispatcher) {
        val vm = newVm()
        val first = sealText(vm, "one")
        val second = sealText(vm, "two")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareMessage(ownRow(first.messageId))
            assertThat(awaitItem().messageId).isEqualTo(first.messageId)
            vm.shareCard() // second 排队
            vm.shareCard() // 重复点不重复入队
            expectNoEvents()
            vm.onPaused()
            vm.onResumed()
            assertThat(awaitItem().messageId).isEqualTo(second.messageId)
            vm.onPaused()
            vm.onResumed()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nothing opens while the app is in the background - it waits for resume`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.onPaused()
            vm.shareCard()
            expectNoEvents()
            vm.onResumed()
            assertThat(awaitItem().messageId).isEqualTo(card.messageId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a request the screen could not launch goes back to the front of the queue`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            val req = awaitItem()
            vm.shareDeferred(req)
            expectNoEvents()
            vm.onResumed()
            assertThat(awaitItem()).isEqualTo(req)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.card.value!!.messageId).isEqualTo(card.messageId)
    }

    @Test
    fun `copy or dismiss drops that message's queued share`() = runTest(dispatcher) {
        val vm = newVm()
        val first = sealText(vm, "one")
        val second = sealText(vm, "two")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareMessage(ownRow(first.messageId))
            awaitItem()
            vm.shareCard() // second 排队
            vm.copyCard() // 复制 second → 撤掉它排队的面板
            vm.onPaused()
            vm.onResumed()
            expectNoEvents()

            val third = sealText(vm, "three")
            vm.shareMessage(ownRow(first.messageId))
            awaitItem()
            vm.shareCard() // third 排队
            vm.dismissSealed()
            vm.onPaused()
            vm.onResumed()
            expectNoEvents()
            assertThat(ownRow(third.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(ownRow(second.messageId).status).isEqualTo(ChatMessage.STATUS_COPIED)
    }

    @Test
    fun `no app to share with shows the message and leaves the card retryable`() = runTest(dispatcher) {
        val vm = newVm()
        sealText(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            vm.shareUnavailable(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.thread_no_share_target))
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.NOT_SENT)
    }

    @Test
    fun `forwarding does not replace a pending text card`() = runTest(dispatcher) {
        repeat(2) {
            server.enqueue(MockResponse().setBody("""{"url":"${server.url("/put/$it")}","expires_in":300}"""))
            server.enqueue(MockResponse().setResponseCode(200))
        }
        val vm = newVm()
        var imageId = ""
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.sendPicked(listOf(Uri.parse("content://test/image/1")))
            imageId = awaitItem().messageId
            vm.onPaused()
            vm.onResumed()
            val text = sealText(vm, "别顶掉我")
            vm.forward(imageId, null, "bob", "bob").join()
            val fwd = awaitItem()
            assertThat(fwd.peer).isEqualTo("bob")
            assertThat(vm.card.value!!.messageId).isEqualTo(text.messageId)
            assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.READY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- fix round 1 ----

    @Test
    fun `a completion that lands during the grace collapses the card instead of saying not sent`() = runTest(dispatcher) {
        val vm = newVm() // watchHandOffs 故意不跑：宽限核对自己也要收卡
        val card = sealTextNow(vm, "hi")
        vm.shareCard()
        vm.onPaused()
        vm.onResumed()
        // 回调在宽限快结束时才落库：宽限期内绝不能先判「未发出」
        advanceTimeBy(ConversationViewModel.NOT_SENT_GRACE_MS - 1)
        runBlocking { repo.markSentIfOwned(card.messageId, "alice") }
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        advanceTimeBy(2)
        vm.card.test(timeout = 5.seconds) {
            var c = awaitItem()
            while (c != null) {
                assertThat(c.phase).isNotEqualTo(ConversationViewModel.SealCard.Phase.NOT_SENT)
                c = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the next queued share opens immediately on return, without waiting for the grace`() = runTest(dispatcher) {
        val vm = newVm()
        val first = sealTextNow(vm, "one")
        val second = sealTextNow(vm, "two")
        val firstRow = rowNow(first.messageId)
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareMessage(firstRow)
            awaitItem()
            vm.shareCard()
            vm.onPaused()
            val before = currentTime
            vm.onResumed()
            assertThat(awaitItem().messageId).isEqualTo(second.messageId)
            assertThat(currentTime).isEqualTo(before)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a launch never followed by ON_PAUSE is released after the watchdog so share works again`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealTextNow(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            val req = awaitItem()
            vm.shareLaunched(req)
            advanceTimeBy(ConversationViewModel.PAUSE_WATCHDOG_MS - 1)
            vm.shareCard()
            expectNoEvents() // 还在等 ON_PAUSE：不重复弹
            advanceTimeBy(2)
            vm.shareCard()
            assertThat(awaitItem().messageId).isEqualTo(card.messageId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a real ON_PAUSE cancels the watchdog`() = runTest(dispatcher) {
        val vm = newVm()
        sealTextNow(vm, "hi")
        vm.shareRequests.test(timeout = 10.seconds) {
            vm.shareCard()
            vm.shareLaunched(awaitItem())
            vm.onPaused() // 面板盖上来了
            advanceTimeBy(ConversationViewModel.PAUSE_WATCHDOG_MS * 3)
            vm.shareCard()
            expectNoEvents() // 面板仍算在台上
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `copying own media records copy as the last action and marks it copied`() = runTest(dispatcher) {
        val vm = newVm()
        val media = ChatMessage(
            "img-1", "alice", ChatMessage.DIRECTION_OUT, "", 1L,
            kind = ChatMessage.KIND_IMAGE, shareText = "MH:2:1:X\n🔒W",
        )
        db.dao().insert(media)
        assertThat(vm.copyMessage(media)).isEqualTo(media.shareText)
        assertThat(prefs.sealAction.value).isEqualTo(AppPrefs.SealAction.COPY)
        awaitStatus("img-1", ChatMessage.STATUS_COPIED)
    }

    @Test
    fun `copying an incoming message says copied right away and leaves the last action alone`() = runTest(dispatcher) {
        val vm = newVm()
        val incoming = ChatMessage("in-9", "alice", ChatMessage.DIRECTION_IN, "hey", 1L, shareText = "🔒W")
        assertThat(vm.copyMessage(incoming)).isEqualTo("🔒W")
        // 同步给出——没有为此去查库
        assertThat(vm.sendError.value).isEqualTo(COPIED_TOAST)
        assertThat(prefs.sealAction.value).isEqualTo(AppPrefs.SealAction.SHARE)
    }

    // ---- Task 5: text hand-off ----

    @Test
    fun `seal with share preference queues the share sheet at once`() = runTest(dispatcher) {
        val vm = newVm()
        assertThat(prefs.sealAction.value).isEqualTo(AppPrefs.SealAction.SHARE)
        vm.shareRequests.test(timeout = 10.seconds) {
            val card = sealText(vm, "hi", autoShare = true)
            val req = awaitItem()
            assertThat(req.messageId).isEqualTo(card.messageId)
            assertThat(req.text).isEqualTo(card.shareText)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
    }

    @Test
    fun `seal with copy preference only shows the card`() = runTest(dispatcher) {
        prefs.setSealAction(AppPrefs.SealAction.COPY)
        val vm = newVm()
        vm.shareRequests.test(timeout = 3.seconds) {
            val card = sealText(vm, "hi", autoShare = true)
            assertThat(card.phase).isEqualTo(ConversationViewModel.SealCard.Phase.READY)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.card.value).isNotNull()
    }

    @Test
    fun `copy marks the message copied not shared`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.copyCard()
        awaitStatus(card.messageId, ChatMessage.STATUS_COPIED)
        assertThat(vm.sendError.value).isEqualTo(COPIED_TOAST)
    }

    @Test
    fun `share completion after copy announces shared`() = runTest(dispatcher) {
        val vm = newVm()
        backgroundScope.launch { vm.watchHandOffs() }
        val card = sealText(vm, "hi")
        vm.copyCard()
        awaitStatus(card.messageId, ChatMessage.STATUS_COPIED)
        vm.consumeSendError()
        assertThat(repo.markSentIfOwned(card.messageId, "alice")).isEqualTo(ChatRepository.MarkResult.MARKED)
        vm.sendError.filter { it == SHARED_TOAST }.first()
    }

    @Test
    fun `cancelled share leaves the card not sent and status sealed`() = runTest(dispatcher) {
        val vm = newVm()
        val card = runBlocking { sealText(vm, "hi", autoShare = true) }
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        vm.onPaused()
        vm.onResumed()
        advanceTimeBy(ConversationViewModel.NOT_SENT_GRACE_MS - 1)
        assertThat(vm.card.value!!.phase).isEqualTo(ConversationViewModel.SealCard.Phase.SHARING)
        advanceTimeBy(2)
        vm.card.test(timeout = 10.seconds) {
            var c = awaitItem()
            while (c?.phase != ConversationViewModel.SealCard.Phase.NOT_SENT) c = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(rowNow(card.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
    }

    @Test
    fun `a cancelled share of a copied message closes the card`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealTextNow(vm, "hi")
        vm.copyCard()
        runBlocking { awaitStatus(card.messageId, ChatMessage.STATUS_COPIED) }
        runBlocking { vm.reopenCard(card.messageId).join() }
        assertThat(vm.card.value!!.messageId).isEqualTo(card.messageId)
        vm.shareCard()
        vm.onPaused()
        vm.onResumed()
        advanceTimeBy(ConversationViewModel.NOT_SENT_GRACE_MS + 1)
        vm.card.test(timeout = 10.seconds) {
            while (awaitItem() != null) Unit
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(rowNow(card.messageId).status).isEqualTo(ChatMessage.STATUS_COPIED)
    }

    @Test
    fun `reopenCard rebuilds the card for a copied message`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi there")
        vm.copyCard()
        awaitStatus(card.messageId, ChatMessage.STATUS_COPIED)
        vm.shareRequests.test(timeout = 3.seconds) {
            vm.reopenCard(card.messageId).join()
            expectNoEvents() // does not open the share sheet by itself
            cancelAndIgnoreRemainingEvents()
        }
        val reopened = vm.card.value!!
        assertThat(reopened.messageId).isEqualTo(card.messageId)
        assertThat(reopened.peer).isEqualTo("alice")
        assertThat(reopened.shareText).isEqualTo(card.shareText)
        assertThat(reopened.summary).isEqualTo(UiText.Raw("hi there"))
        assertThat(reopened.phase).isEqualTo(ConversationViewModel.SealCard.Phase.READY)
    }

    @Test
    fun `reopenCard rebuilds a media card from the stored share text`() = runTest(dispatcher) {
        val vm = newVm()
        val media = ChatMessage(
            "img-2", "alice", ChatMessage.DIRECTION_OUT, "", 1L,
            kind = ChatMessage.KIND_IMAGE, shareText = "MH:2:1:X\n🔒W",
        )
        db.dao().insert(media)
        vm.reopenCard("img-2").join()
        val reopened = vm.card.value!!
        assertThat(reopened.shareText).isEqualTo(media.shareText)
        assertThat(reopened.summary).isEqualTo(UiText.Res(R.string.media_preview_image))
    }

    @Test
    fun `reopenCard ignores a shared message`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "hi")
        vm.dismissSealed()
        repo.markSentIfOwned(card.messageId, "alice")
        vm.reopenCard(card.messageId).join()
        assertThat(vm.card.value).isNull()
    }

    @Test
    fun `reopenCard ignores an incoming message`() = runTest(dispatcher) {
        val vm = newVm()
        db.dao().insert(ChatMessage("in-3", "alice", ChatMessage.DIRECTION_IN, "hey", 1L, shareText = "🔒W"))
        vm.reopenCard("in-3").join()
        assertThat(vm.card.value).isNull()
    }

    @Test
    fun `copyOriginal copies the plaintext and does not change status`() = runTest(dispatcher) {
        val vm = newVm()
        val card = sealText(vm, "secret words")
        var written: String? = null
        assertThat(vm.copyOriginal(ownRow(card.messageId)) { written = it; true }).isEqualTo("secret words")
        assertThat(written).isEqualTo("secret words")
        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.common_copied))
        assertThat(ownRow(card.messageId).status).isEqualTo(ChatMessage.STATUS_SEALED)
        assertThat(vm.card.value!!.messageId).isEqualTo(card.messageId)
    }

    @Test
    fun `copyOriginal reports a failed clipboard write`() = runTest(dispatcher) {
        val vm = newVm()
        val incoming = ChatMessage("in-4", "alice", ChatMessage.DIRECTION_IN, "hey", 1L, shareText = "🔒W")
        assertThat(vm.copyOriginal(incoming) { false }).isNull()
        assertThat(vm.sendError.value).isEqualTo(UiText.Res(R.string.common_copy_failed))
    }

    @Test
    fun `pasting an encrypted message from another peer navigates there and keeps the draft`() = runTest(dispatcher) {
        val intake = plainIntake(classify = messageOnLock, handle = { IntakeOutcome.OpenThread("bob") })
        val vm = newVm(intake = intake)
        vm.onDraftChanged("hi")
        vm.navigation.test(timeout = 10.seconds) {
            vm.onDraftChanged("hi🔒abc")
            assertThat(awaitItem()).isEqualTo(ConversationViewModel.ThreadNav.OpenThread("bob"))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(intake.handled).containsExactly("hi🔒abc")
        assertThat(vm.draft.value).isEqualTo("hi")
    }

    @Test
    fun `pasting a message that belongs to this thread stays here quietly`() = runTest(dispatcher) {
        val vm = newVm(intake = plainIntake(classify = messageOnLock, handle = { IntakeOutcome.AlreadyInThread("alice") }))
        vm.navigation.test(timeout = 3.seconds) {
            vm.onDraftChanged("🔒abc")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.draft.value).isEmpty()
        assertThat(vm.sendError.value).isNull()
    }

    @Test
    fun `pasting a message that cannot be decrypted says why`() = runTest(dispatcher) {
        val vm = newVm(
            intake = plainIntake(classify = messageOnLock, handle = { IntakeOutcome.Failed(IntakeFailure.CANNOT_DECRYPT) }),
        )
        vm.onDraftChanged("🔒abc")
        vm.sendError.filter { it == UiText.Res(IntakeFailure.CANNOT_DECRYPT.messageRes) }.first()
        assertThat(vm.draft.value).isEmpty()
    }

    @Test
    fun `a storage error while handling a pasted message becomes the save-failed message`() = runTest(dispatcher) {
        val vm = newVm(intake = plainIntake(classify = messageOnLock, handle = { throw IllegalStateException("db") }))
        vm.onDraftChanged("🔒abc")
        vm.sendError.filter { it == UiText.Res(IntakeFailure.SAVE_FAILED.messageRes) }.first()
    }

    @Test
    fun `pasting a pairing code opens the wizard`() = runTest(dispatcher) {
        val intake = plainIntake(classify = { if ("🔒" in it) IntakeKind.PairingInvite("🔒P") else IntakeKind.NotOurs })
        val vm = newVm(intake = intake)
        vm.navigation.test(timeout = 10.seconds) {
            vm.onDraftChanged("my code 🔒P")
            assertThat(awaitItem()).isEqualTo(ConversationViewModel.ThreadNav.OpenWizard("🔒P"))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.draft.value).isEmpty()
    }

    @Test
    fun `typing a lone lock emoji stays in the draft`() = runTest(dispatcher) {
        val vm = newVm(intake = plainIntake(classify = messageOnLock))
        vm.navigation.test(timeout = 3.seconds) {
            vm.onDraftChanged("🔒")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.draft.value).isEqualTo("🔒")
    }

    /** Banner is null until contact + history have loaded; the first non-null value is the one that matters. */
    private suspend fun app.cash.turbine.ReceiveTurbine<ThreadBanner?>.awaitFirstBanner(): ThreadBanner {
        while (true) awaitItem()?.let { return it }
    }

    private fun bannerContact(accepted: Boolean, verified: Boolean = false) = Contact(
        fingerprintHex = "fp-alice", username = "alice", displayName = "Alice", pairedAt = 0L, verified = verified,
        acceptedInviteDigest = if (accepted) "digest" else null,
    )

    private suspend fun insertMsg(direction: String) = db.dao().insert(
        ChatMessage(id = "m-$direction", peerUsername = "alice", direction = direction, body = "x", timestamp = 1L),
    )

    @Test
    fun `acceptor banner waits for the peer, and dismissal persists without falling through to verify`() = runTest(dispatcher) {
        val acceptor = newVm(contacts = flowOf(listOf(bannerContact(accepted = true))))
        acceptor.banner.test {
            assertThat(awaitFirstBanner()).isEqualTo(ThreadBanner.WAITING_PEER)
            acceptor.dismissBanner(ThreadBanner.WAITING_PEER)
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
        // persisted: a fresh VM over the same prefs stays dismissed
        val again = newVm(contacts = flowOf(listOf(bannerContact(accepted = true))))
        again.banner.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initiator banner is say-hi until a message is sent, then verify-later until dismissed`() = runTest(dispatcher) {
        val vm = newVm(contacts = flowOf(listOf(bannerContact(accepted = false))))
        vm.banner.test {
            assertThat(awaitFirstBanner()).isEqualTo(ThreadBanner.SAY_HI)
            insertMsg(ChatMessage.DIRECTION_OUT)
            assertThat(awaitItem()).isEqualTo(ThreadBanner.VERIFY_LATER)
            vm.dismissBanner(ThreadBanner.VERIFY_LATER)
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `existing thread never flashes a wrong banner while messages load`() = runTest(dispatcher) {
        insertMsg(ChatMessage.DIRECTION_IN)
        insertMsg(ChatMessage.DIRECTION_OUT)
        val vm = newVm(contacts = flowOf(listOf(bannerContact(accepted = true, verified = true))))
        vm.banner.test {
            // contact is known immediately; with history present neither waiting nor say-hi may ever show
            assertThat(awaitItem()).isNull()
            cancelAndConsumeRemainingEvents().forEach { assertThat(it).isEqualTo(app.cash.turbine.Event.Item(null)) }
        }
        val unverified = newVm(contacts = flowOf(listOf(bannerContact(accepted = true))))
        unverified.banner.test {
            assertThat(awaitFirstBanner()).isEqualTo(ThreadBanner.VERIFY_LATER) // first non-null is the correct one
            cancelAndIgnoreRemainingEvents()
        }
    }
}
