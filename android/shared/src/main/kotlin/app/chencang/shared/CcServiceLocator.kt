package app.chencang.shared

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import app.chencang.shared.chat.ChatDatabase
import app.chencang.shared.config.ConfigFetcher
import app.chencang.shared.config.ConfigRepository
import app.chencang.shared.config.RelaySelector
import app.chencang.shared.config.ConfigStore
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.chat.inTransactionRunner
import app.chencang.shared.contacts.ContactListSerializer
import app.chencang.shared.contacts.DataStoreContactsStore
import app.chencang.shared.crypto.PrekeyProvisioner
import app.chencang.shared.crypto.RatchetSessionCrypto
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.crypto.SessionManager
import app.chencang.shared.crypto.SessionStateDatabase
import app.chencang.shared.crypto.SignedPreKeyStore
import app.chencang.shared.identity.IdentityFileEnvelope
import app.chencang.shared.identity.IdentityStore
import app.chencang.shared.identity.KeystoreHelper
import app.chencang.shared.intake.IncomingIntake
import app.chencang.shared.media.MediaDownloader
import app.chencang.shared.media.MediaFiles
import app.chencang.shared.media.MediaSender
import app.chencang.shared.media.MediaTransport
import app.chencang.shared.media.ResourceShareHeaders
import app.chencang.shared.media.UniffiMediaCrypto
import app.chencang.shared.media.UploadEngine
import app.chencang.shared.model.ContactListSnapshot
import app.chencang.shared.pairing.inband.DataStorePairingResponseStore
import app.chencang.shared.pairing.inband.InbandPairing
import app.chencang.shared.pairing.inband.DataStorePendingInviteStore
import app.chencang.shared.pairing.inband.PairingCoordinator
import app.chencang.shared.pairing.inband.PairingResponseStore
import app.chencang.shared.pairing.inband.PendingInviteStore
import app.chencang.shared.pairing.inband.PairingResponseListSerializer
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingInviteListSerializer
import app.chencang.shared.pairing.inband.PendingPairingRecord
import app.chencang.shared.pairing.inband.PendingPairingRecordSerializer
import app.chencang.shared.pairing.inband.awaitingInvites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide service locator. Lazily initializes singletons against the [Application]
 * context so `:app` can fetch them via `CcServiceLocator.from(application)`.
 *
 * Pure-Kotlin equivalents are exposed for unit tests via [CcServiceLocator.forTest].
 *
 * Zero-network design: all registration, key exchange, and blob transfer is removed.
 * Identity and SPK are provisioned locally; pairing is fully in-band (no server).
 */
class CcServiceLocator private constructor(
    val repository: CcRepository,
    val identityStore: IdentityStore,
    val prekeyProvisioner: PrekeyProvisioner,
    /**
     * Headless seam for zero-server in-band classical pairing. Drives
     * [PairingCoordinator.startInvite] / [PairingCoordinator.acceptIncoming] /
     * [PairingCoordinator.completeIncoming] against the on-disk identity/SPK,
     * the [sessionStore], the [repository], and the on-disk lists of pending
     * invites and stored responses, which survive process death.
     */
    val pairingCoordinator: PairingCoordinator,
    /** 配对中的邀请与接受方回应记录的可观察列表：联系人 tab 与会话空状态据此推导条目与角标。 */
    val pendingInviteStore: PendingInviteStore,
    val pairingResponseStore: PairingResponseStore,
    /**
     * Process-wide Double Ratchet session cache. Populated by pairing
     * (or UAT seeding) and consumed by the chat send/receive pipeline.
     * In-memory only for V1.
     */
    val sessionStore: RatchetSessionStore,
    /**
     * Text-chat send/receive orchestration for 密信 threads (Phase 1).
     * Shares the same [sessionStore]-backed ratchet crypto as pairing.
     */
    val chatRepository: ChatRepository,
    /**
     * Aggregated best-effort account wipe, run by the Settings "delete
     * account" confirmation. See [AccountWiper].
     */
    val accountWiper: AccountWiper,
    /** Small app-wide switches (e.g. 隐藏消息摘要). See [AppPrefs]. */
    val appPrefs: AppPrefs,
    /** 富媒体发送（spec 2026-09-25 §3.5）。唯一联网点在 [MediaTransport]。 */
    val mediaSender: MediaSender,
    /** 富媒体下载（spec 2026-09-25 §3.6）。 */
    val mediaDownloader: MediaDownloader,
    /** The one router for incoming text (PROCESS_TEXT, share, paste, wizard input). */
    val incomingIntake: IncomingIntake,
    /** Signed distribution config (factory asset + cached). The Activity triggers refresh; nothing auto-fetches here. */
    val configRepository: ConfigRepository,
    private val chatDb: ChatDatabase,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    fun close() {
        chatDb.close()
        scope.cancel()
    }

    /** 本机指纹的小写十六进制串（头像「自动」底色的种子）；无身份或读取失败返回 null。 */
    suspend fun myFingerprintHex(): String? = withContext(Dispatchers.IO) {
        try {
            identityStore.load().use { InbandPairing.localFingerprintHex(it) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        @Volatile private var instance: CcServiceLocator? = null
        @Volatile private var uploadEngine: UploadEngine? = null

        /**
         * 装上后台上传引擎（生产 = `:app` 的 WorkManager 实现）。必须在第一次 [from] 之前：
         * `CcApp.onCreate` 第一行就装——WorkManager 在进程被杀后拉起 Worker 时，Application.onCreate
         * 也一定先跑完（WorkManager 走按需初始化，见 `CcApp`）。
         */
        fun installUploadEngine(engine: UploadEngine) {
            uploadEngine = engine
        }

        fun from(context: Context): CcServiceLocator {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }

                val appContext = context.applicationContext
                val contactsDataStore: DataStore<ContactListSnapshot> = DataStoreFactory.create(
                    serializer = ContactListSerializer,
                    produceFile = { appContext.dataStoreFile("chencang_contacts.cbor") },
                )
                val repository = CcRepository(DataStoreContactsStore(contactsDataStore))

                val identityStore = IdentityStore(
                    File(appContext.filesDir, "identity.enc"),
                    IdentityFileEnvelope.keystoreBacked(KeystoreHelper()),
                )

                val devicePrefs =
                    appContext.getSharedPreferences("chencang_device", Context.MODE_PRIVATE)
                val spkStore = SignedPreKeyStore(
                    envelope = IdentityFileEnvelope.keystoreBacked(KeystoreHelper()),
                    file = File(appContext.filesDir, "spk.enc"),
                )
                val prekeyProvisioner = PrekeyProvisioner(prefs = devicePrefs, spkStore = spkStore)

                val sessionStore = RatchetSessionStore(
                    dao = SessionStateDatabase.get(appContext).sessionStateDao(),
                )

                // 「配对中」: my unanswered invites (a list) and the responses I produced as an
                // invitee — public content only, on disk so a pairing survives a process kill
                // between rounds. The single-slot file older builds wrote is opened ONCE here
                // (DataStore allows one instance per file) and handed to the invite store, which
                // migrates its record into the list and empties it.
                val legacyPendingPairingDataStore: DataStore<PendingPairingRecord?> =
                    DataStoreFactory.create(
                        serializer = PendingPairingRecordSerializer,
                        produceFile = { appContext.dataStoreFile("chencang_pending_pairing.cbor") },
                    )
                val pendingInvitesDataStore: DataStore<List<PendingPairingRecord>> =
                    DataStoreFactory.create(
                        serializer = PendingInviteListSerializer,
                        produceFile = { appContext.dataStoreFile("chencang_pending_pairings.cbor") },
                    )
                val pairingResponsesDataStore: DataStore<List<PairingResponseRecord>> =
                    DataStoreFactory.create(
                        serializer = PairingResponseListSerializer,
                        produceFile = { appContext.dataStoreFile("chencang_pairing_responses.cbor") },
                    )
                val pendingInviteStore = DataStorePendingInviteStore(pendingInvitesDataStore, legacyPendingPairingDataStore)
                val pairingResponseStore = DataStorePairingResponseStore(pairingResponsesDataStore)
                val pairingCoordinator = PairingCoordinator(
                    identityStore = identityStore,
                    prekeyProvisioner = prekeyProvisioner,
                    sessionStore = sessionStore,
                    repository = repository,
                    pending = pendingInviteStore,
                    responses = pairingResponseStore,
                    defaultContactName = { id -> appContext.getString(R.string.contacts_default_name, id) },
                )

                val engine = checkNotNull(uploadEngine) {
                    "UploadEngine not installed: CcApp.onCreate must call installUploadEngine before from()"
                }
                val chatDb = ChatDatabase.create(appContext)
                val mediaFiles = MediaFiles(
                    root = File(appContext.filesDir, "media"),
                    scratchDirs = MediaFiles.scratchDirsUnder(appContext.cacheDir),
                )
                // One SessionManager for the whole process: its per-peer mutexes are
                // what serialise ratchet use between text seal, media frame seal and receive.
                val sessions = SessionManager(RatchetSessionCrypto(sessionStore))
                val configRepository = ConfigRepository(
                    factoryEnvelope = appContext.assets.open("chencang-config.json").use { it.readBytes().toString(Charsets.UTF_8) },
                    store = ConfigStore(appContext.getSharedPreferences(ConfigStore.PREFS_NAME, Context.MODE_PRIVATE)),
                    source = ConfigFetcher(),
                    verify = { payload, sig -> uniffi.chencang.verifySignedConfig(payload, sig) },
                    now = System::currentTimeMillis,
                    io = Dispatchers.IO,
                )
                val relaySelector = RelaySelector(
                    relays = { configRepository.config.value.relays },
                    prefs = appContext.getSharedPreferences(RelaySelector.PREFS_NAME, Context.MODE_PRIVATE),
                )
                // Share-text first lines are read from resources on each call: they follow the system language.
                val shareHeaders = ResourceShareHeaders(appContext) { configRepository.config.value.shareSite }
                val chatRepository = ChatRepository(
                    dao = chatDb.dao(),
                    sessions = sessions,
                    mediaDao = chatDb.mediaDao(),
                    mediaFiles = mediaFiles,
                    inTransaction = chatDb.inTransactionRunner(),
                    cancelUpload = engine::cancel,
                    // In-band pairing sets a contact's username to its fingerprint, which is
                    // also the session's sender key.
                    onIncomingStored = { peer -> pairingCoordinator.forgetPeer(peer) },
                    shareHeaders = shareHeaders,
                )

                val appPrefs = AppPrefs(appContext)

                val mediaTransport = MediaTransport(relaySelector)
                // 封好即交给后台上传引擎（WorkManager，spec 2026-09-30 §1.1）：不跟 UI 生命周期走，
                // 离开会话、进后台、进程被杀都续传。
                val mediaSender = MediaSender(
                    dao = chatDb.dao(),
                    mediaDao = chatDb.mediaDao(),
                    files = mediaFiles,
                    crypto = UniffiMediaCrypto,
                    transport = mediaTransport,
                    sealFrame = { peer, refs -> chatRepository.sealMediaFrame(peer, refs) },
                    inTransaction = chatDb.inTransactionRunner(),
                    uploadScheduler = engine::enqueue,
                    uploadNow = engine::enqueueNow,
                    shareHeaders = shareHeaders,
                )
                val mediaDownloader = MediaDownloader(
                    dao = chatDb.dao(),
                    mediaDao = chatDb.mediaDao(),
                    files = mediaFiles,
                    crypto = UniffiMediaCrypto,
                    transport = mediaTransport,
                )

                val accountWiper = AccountWiper(
                    clearChat = {
                        // 先撤掉所有后台上传，再清库；撤不掉、或调用方此刻被取消，也照清
                        // （任务之后读到的是「消息已删」）。
                        try {
                            engine.cancelAll()
                        } finally {
                            withContext(NonCancellable) {
                                chatDb.mediaDao().clearAll()
                                chatDb.dao().clearAll()
                            }
                        }
                    },
                    clearMedia = { mediaFiles.deleteAll() },
                    clearSessions = { sessionStore.clear() },
                    clearContacts = { repository.clearAll() },
                    clearPendingPairing = { pairingCoordinator.clearAllPending() },
                    clearDevicePrefs = {
                        @Suppress("ApplySharedPref")
                        devicePrefs.edit().clear().commit()
                    },
                    clearMyProfile = { appPrefs.clearMyProfile() },
                    wipeSpk = { spkStore.wipe() },
                    wipeIdentity = { identityStore.wipe() },
                    deleteKeystoreKey = { KeystoreHelper().deleteKey() },
                )
                val locator = CcServiceLocator(
                    repository = repository,
                    identityStore = identityStore,
                    prekeyProvisioner = prekeyProvisioner,
                    pairingCoordinator = pairingCoordinator,
                    pendingInviteStore = pendingInviteStore,
                    pairingResponseStore = pairingResponseStore,
                    sessionStore = sessionStore,
                    chatRepository = chatRepository,
                    accountWiper = accountWiper,
                    appPrefs = appPrefs,
                    mediaSender = mediaSender,
                    mediaDownloader = mediaDownloader,
                    incomingIntake = IncomingIntake(
                        receiver = chatRepository,
                        findByWire = chatRepository::findByWire,
                        hasContacts = { repository.contacts.first().isNotEmpty() },
                        hasAwaitingInvites = {
                            awaitingInvites(pendingInviteStore.all(), System.currentTimeMillis()).isNotEmpty()
                        },
                    ),
                    configRepository = configRepository,
                    chatDb = chatDb,
                )
                // 昵称与头像的初值在后台读出后再发布，不在主线程构造时同步读（spec three-tab-shell §7.3）。
                locator.scope.launch(Dispatchers.IO) { appPrefs.loadProfile() }
                // No identity bootstrap here: whether an identity exists is what tells a new user
                // (onboarding creates it) from a returning one, so assembling the locator must
                // never mint one. The SPK is provisioned lazily when the first invite is built.
                return locator.also { instance = it }
            }
        }

        /** Test-only: install a hand-built locator. Use a fresh repository per test. */
        fun installForTest(locator: CcServiceLocator) {
            instance = locator
        }

        /**
         * Production reset after an account wipe: drop the singleton (and its
         * in-memory session/contact caches) so the next [from] reassembles
         * against the now-empty disk.
         */
        fun reset() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** Test-only alias of [reset]. */
        fun resetForTest() = reset()
    }
}
