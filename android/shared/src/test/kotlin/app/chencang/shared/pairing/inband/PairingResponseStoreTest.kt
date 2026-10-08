package app.chencang.shared.pairing.inband

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/** In-memory test double for [PairingResponseStore] (reused by the coordinator tests). */
class InMemoryPairingResponseStore(initial: List<PairingResponseRecord> = emptyList()) : PairingResponseStore {
    private val state = MutableStateFlow(initial)
    override val records: Flow<List<PairingResponseRecord>> = state
    override suspend fun put(record: PairingResponseRecord) =
        state.update { list -> list.filterNot { it.fingerprintHex == record.fingerprintHex } + record }

    override suspend fun recordFor(fingerprintHex: String): PairingResponseRecord? =
        state.value.firstOrNull { it.fingerprintHex == fingerprintHex }

    override suspend fun findByInviteDigest(digest: String): PairingResponseRecord? =
        state.value.firstOrNull { it.inviteDigest == digest }

    override suspend fun markShared(fingerprintHex: String, atMillis: Long) = state.update { list ->
        list.map { if (it.fingerprintHex == fingerprintHex) it.copy(lastSharedAtMillis = atMillis) else it }
    }

    override suspend fun remove(fingerprintHex: String) =
        state.update { list -> list.filterNot { it.fingerprintHex == fingerprintHex } }

    override suspend fun clear() = state.update { emptyList() }
}

class PairingResponseStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }

    @After
    fun tearDown() = stores.close()

    private fun newStore() = DataStorePairingResponseStore(stores.open(FILE, PairingResponseListSerializer))

    private fun response(fp: String, digest: String = "digest-$fp", createdAt: Long = 1_000L) =
        PairingResponseRecord(
            fingerprintHex = fp,
            responseWire = "🔒response-$fp",
            inviteDigest = digest,
            createdAtMillis = createdAt,
        )

    @Test
    fun `put then recordFor`() = runBlocking<Unit> {
        val store = newStore()
        assertThat(store.recordFor("aa")).isNull()

        store.put(response("aa"))
        store.put(response("bb"))
        assertThat(store.recordFor("aa")).isEqualTo(response("aa"))
        assertThat(store.recordFor("aa")!!.lastSharedAtMillis).isNull()
        assertThat(store.records.first()).containsExactly(response("aa"), response("bb")).inOrder()

        // One record per peer: a second put for the same fingerprint replaces the first.
        store.put(response("aa", digest = "other", createdAt = 2_000L))
        assertThat(store.records.first()).hasSize(2)
        assertThat(store.recordFor("aa")!!.inviteDigest).isEqualTo("other")
    }

    @Test
    fun findByInviteDigest() = runBlocking<Unit> {
        val store = newStore()
        store.put(response("aa", digest = "d1"))
        store.put(response("bb", digest = "d2"))
        assertThat(store.findByInviteDigest("d2")!!.fingerprintHex).isEqualTo("bb")
        assertThat(store.findByInviteDigest("d3")).isNull()
    }

    @Test
    fun `markShared sets timestamp`() = runBlocking<Unit> {
        val store = newStore()
        store.put(response("aa"))
        store.put(response("bb"))

        store.markShared("aa", 4_242L)
        store.markShared("missing", 1L)   // unknown peer: no-op
        assertThat(store.recordFor("aa")).isEqualTo(response("aa").copy(lastSharedAtMillis = 4_242L))
        assertThat(store.recordFor("bb")!!.lastSharedAtMillis).isNull()
        assertThat(store.records.first()).hasSize(2)
    }

    @Test
    fun `remove and clear`() = runBlocking<Unit> {
        val store = newStore()
        store.put(response("aa"))
        store.put(response("bb"))
        store.put(response("cc"))

        store.remove("bb")
        assertThat(store.recordFor("bb")).isNull()
        assertThat(store.records.first().map { it.fingerprintHex }).containsExactly("aa", "cc").inOrder()

        store.clear()
        assertThat(store.records.first()).isEmpty()
    }

    @Test
    fun `survives rebuild`() = runBlocking<Unit> {
        val first = newStore()
        first.put(response("aa"))
        first.put(response("bb"))
        first.markShared("bb", 77L)
        val before = first.records.first()

        stores.close()
        val rebuilt = newStore()
        assertThat(rebuilt.records.first()).isEqualTo(before)
        assertThat(rebuilt.recordFor("bb")!!.lastSharedAtMillis).isEqualTo(77L)
    }

    @Test
    fun `corrupt bytes give empty list not a crash`() = runBlocking<Unit> {
        assertThat(
            PairingResponseListSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0x13, 0x37, 0x00, 0x42))),
        ).isEmpty()
        assertThat(PairingResponseListSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))).isEmpty()

        stores.file(FILE).writeBytes("not cbor at all".toByteArray())
        val store = newStore()
        assertThat(store.records.first()).isEmpty()
        store.put(response("aa"))
        assertThat(store.recordFor("aa")).isEqualTo(response("aa"))
    }

    private companion object {
        const val FILE = "chencang_pairing_responses.cbor"
    }
}
