package app.chencang.shared.pairing.inband

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** In-memory test double for [PendingInviteStore] (reused by the coordinator tests). */
class InMemoryPendingInviteStore(initial: List<PendingPairingRecord> = emptyList()) : PendingInviteStore {
    private val state = MutableStateFlow(initial)
    override val records: Flow<List<PendingPairingRecord>> = state
    override suspend fun all(): List<PendingPairingRecord> = state.value
    override suspend fun append(record: PendingPairingRecord) = state.update { it + record }
    override suspend fun update(id: String, transform: (PendingPairingRecord) -> PendingPairingRecord) =
        state.update { list -> list.map { if (it.pairingId == id) transform(it) else it } }

    override suspend fun remove(id: String) = state.update { list -> list.filterNot { it.pairingId == id } }
    override suspend fun clear() = state.update { emptyList() }
}

/**
 * Opens DataStores over temp files. DataStore allows one live instance per file, so a "rebuild"
 * must first [close] the previous generation (cancels its scope and waits for it).
 */
class TestDataStores(private val dir: File) {
    private var job = Job()

    fun <T> open(name: String, serializer: Serializer<T>): DataStore<T> = DataStoreFactory.create(
        serializer = serializer,
        scope = CoroutineScope(Dispatchers.IO + job),
        produceFile = { File(dir, name) },
    )

    fun file(name: String) = File(dir, name)

    fun close() = runBlocking<Unit> {
        job.cancelAndJoin()
        job = Job()
    }
}

/** The pre-upgrade on-disk shape of [PendingPairingRecord]: exactly three fields. */
@Serializable
private data class LegacyThreeFieldRecord(
    val pairingId: String,
    val pairingNonceB64: String,
    val createdAtMillis: Long,
)

@OptIn(ExperimentalSerializationApi::class)
class PendingInviteStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }

    @After
    fun tearDown() = stores.close()

    private fun newStore(): DataStorePendingInviteStore = DataStorePendingInviteStore(
        dataStore = stores.open(LIST_FILE, PendingInviteListSerializer),
        legacy = stores.open(LEGACY_FILE, PendingPairingRecordSerializer),
    )

    private fun invite(id: String, createdAt: Long = 1_000L) = PendingPairingRecord(
        pairingId = id,
        pairingNonceB64 = "nonce-$id",
        createdAtMillis = createdAt,
        inviteWire = "🔒wire-$id",
    )

    @Test
    fun `append two keeps both`() = runBlocking<Unit> {
        val store = newStore()
        store.append(invite("p1"))
        store.append(invite("p2"))
        assertThat(store.all().map { it.pairingId }).containsExactly("p1", "p2").inOrder()
        assertThat(store.records.first()).isEqualTo(store.all())
    }

    @Test
    fun `update and remove by id`() = runBlocking<Unit> {
        val store = newStore()
        store.append(invite("p1"))
        store.append(invite("p2"))

        store.update("p2") { it.copy(note = "发给老周的", lastSharedAtMillis = 5_000L) }
        assertThat(store.all()).containsExactly(
            invite("p1"),
            invite("p2").copy(note = "发给老周的", lastSharedAtMillis = 5_000L),
        ).inOrder()

        store.update("missing") { it.copy(note = "x") }   // unknown id: no-op
        store.remove("p1")
        assertThat(store.all().map { it.pairingId }).containsExactly("p2")

        store.clear()
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun `survives rebuild on the same file`() = runBlocking<Unit> {
        val first = newStore()
        first.append(invite("p1"))
        first.append(invite("p2").copy(note = "备注", lastSharedAtMillis = 9L))
        val before = first.all()

        stores.close()
        assertThat(newStore().all()).isEqualTo(before)
    }

    @Test
    fun `legacy slot migrates once and is cleared`() = runBlocking<Unit> {
        stores.open(LEGACY_FILE, PendingPairingRecordSerializer)
            .updateData { PendingPairingRecord("old", "b2xkLW5vbmNl", 7_000L) }
        stores.close()

        val store = newStore()
        store.append(invite("p1"))
        val migrated = store.all().single { it.pairingId == "old" }
        assertThat(migrated).isEqualTo(
            PendingPairingRecord(
                pairingId = "old",
                pairingNonceB64 = "b2xkLW5vbmNl",
                createdAtMillis = 7_000L,
                inviteWire = "",
                note = null,
                lastSharedAtMillis = 7_000L,
                keyHandle = null,
            ),
        )
        assertThat(store.all().map { it.pairingId }).containsExactly("old", "p1").inOrder()

        // Legacy slot is emptied, so a rebuilt store does not migrate it a second time.
        stores.close()
        assertThat(stores.open(LEGACY_FILE, PendingPairingRecordSerializer).data.first()).isNull()
        stores.close()
        val rebuilt = newStore()
        rebuilt.remove("old")
        stores.close()
        assertThat(newStore().all().map { it.pairingId }).containsExactly("p1")
    }

    @Test
    fun `migration is idempotent when legacy slot was not cleared`() = runBlocking<Unit> {
        // An interrupted migration: the record already landed in the list (and was since edited),
        // but the legacy slot still holds it.
        val alreadyMigrated = PendingPairingRecord(
            pairingId = "old", pairingNonceB64 = "b2xkLW5vbmNl", createdAtMillis = 7_000L,
            lastSharedAtMillis = 7_000L, note = "已改过备注",
        )
        stores.open(LIST_FILE, PendingInviteListSerializer).updateData { listOf(alreadyMigrated) }
        stores.open(LEGACY_FILE, PendingPairingRecordSerializer)
            .updateData { PendingPairingRecord("old", "b2xkLW5vbmNl", 7_000L) }
        stores.close()

        val store = newStore()
        assertThat(store.records.first()).containsExactly(alreadyMigrated)
        assertThat(store.all()).containsExactly(alreadyMigrated)

        stores.close()
        assertThat(stores.open(LEGACY_FILE, PendingPairingRecordSerializer).data.first()).isNull()
    }

    @Test
    fun `corrupt bytes give empty list not a crash`() = runBlocking<Unit> {
        val garbage = listOf(
            byteArrayOf(0x13, 0x37, 0x00, 0xFF.toByte(), 0x42),
            byteArrayOf(0x9F.toByte(), 0xBF.toByte(), 0x68),                 // truncated mid-record
            Cbor.encodeToByteArray(ListSerializer(PendingPairingRecord.serializer()), listOf(invite("p1")))
                .let { it.copyOf(it.size / 2) },
            "not cbor at all".toByteArray(),
        )
        for (bytes in garbage) {
            assertThat(PendingInviteListSerializer.readFrom(ByteArrayInputStream(bytes))).isEmpty()
        }

        // Through the store: a corrupt file reads as empty and stays writable.
        stores.file(LIST_FILE).writeBytes(garbage[0])
        val store = newStore()
        assertThat(store.all()).isEmpty()
        assertThat(store.records.first()).isEmpty()
        store.append(invite("p1"))
        assertThat(store.all()).containsExactly(invite("p1"))
    }

    @Test
    fun `serializer round-trips a record with all new fields`() = runBlocking<Unit> {
        val full = PendingPairingRecord(
            pairingId = "pid-xyz",
            pairingNonceB64 = "bm9uY2U=",
            createdAtMillis = 1_718_000_000_000L,
            inviteWire = "🔒邀请暗号全文",
            note = "发给老周的",
            lastSharedAtMillis = 1_718_000_060_000L,
            keyHandle = "handle-1",
        )
        val out = ByteArrayOutputStream()
        PendingInviteListSerializer.writeTo(listOf(full, invite("p2")), out)
        assertThat(PendingInviteListSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
            .containsExactly(full, invite("p2")).inOrder()

        assertThat(PendingInviteListSerializer.defaultValue).isEmpty()
        assertThat(PendingInviteListSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))).isEmpty()
    }

    @Test
    fun `old three-field CBOR still decodes`() = runBlocking<Unit> {
        val old = LegacyThreeFieldRecord("old", "b2xkLW5vbmNl", 7_000L)
        val expected = PendingPairingRecord("old", "b2xkLW5vbmNl", 7_000L)
        assertThat(expected.inviteWire).isEmpty()
        assertThat(expected.note).isNull()
        assertThat(expected.lastSharedAtMillis).isNull()
        assertThat(expected.keyHandle).isNull()

        // Single-slot file written by the previous version.
        val slotBytes = Cbor.encodeToByteArray(LegacyThreeFieldRecord.serializer(), old)
        assertThat(PendingPairingRecordSerializer.readFrom(ByteArrayInputStream(slotBytes))).isEqualTo(expected)

        // Same three-field shape inside a list.
        val listBytes = Cbor.encodeToByteArray(ListSerializer(LegacyThreeFieldRecord.serializer()), listOf(old))
        assertThat(PendingInviteListSerializer.readFrom(ByteArrayInputStream(listBytes))).containsExactly(expected)
    }

    private companion object {
        const val LIST_FILE = "chencang_pending_pairings.cbor"
        const val LEGACY_FILE = "chencang_pending_pairing.cbor"
    }
}
