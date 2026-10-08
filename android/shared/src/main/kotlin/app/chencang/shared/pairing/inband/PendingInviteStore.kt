package app.chencang.shared.pairing.inband

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import java.io.InputStream
import java.io.OutputStream

/**
 * The inviter's not-yet-completed invites (「配对中」), as a list: one record = one invite I sent
 * that nobody has answered yet. Replaces the former single-slot store
 * (spec 2026-10-01-three-tab-shell §7.5). Records hold public content only — see
 * [PendingPairingRecord].
 */
interface PendingInviteStore {
    /** Observable list, in insertion order. */
    val records: Flow<List<PendingPairingRecord>>

    suspend fun all(): List<PendingPairingRecord>

    suspend fun append(record: PendingPairingRecord)

    /** Applies [transform] to the record whose `pairingId` is [id]; no-op when there is none. */
    suspend fun update(id: String, transform: (PendingPairingRecord) -> PendingPairingRecord)

    suspend fun remove(id: String)

    suspend fun clear()
}

/**
 * Decodes a CBOR list for a DataStore, mapping every unreadable input to an empty list: empty
 * bytes are the initial/cleared sentinel, and a corrupt or schema-incompatible blob is dropped
 * rather than crashing whoever observes the store (the contacts tab). Decoding is non-suspending,
 * so the broad catch cannot swallow a coroutine cancellation.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> decodeCborListOrEmpty(elementSerializer: KSerializer<T>, input: InputStream): List<T> {
    val bytes = input.readBytes()
    if (bytes.isEmpty()) return emptyList()
    return try {
        Cbor.decodeFromByteArray(ListSerializer(elementSerializer), bytes)
    } catch (e: RuntimeException) {
        emptyList()
    }
}

/** DataStore [Serializer] for the pending-invite list (CBOR, same idiom as [PendingPairingRecordSerializer]). */
@OptIn(ExperimentalSerializationApi::class)
object PendingInviteListSerializer : Serializer<List<PendingPairingRecord>> {
    override val defaultValue: List<PendingPairingRecord> = emptyList()

    override suspend fun readFrom(input: InputStream): List<PendingPairingRecord> =
        decodeCborListOrEmpty(PendingPairingRecord.serializer(), input)

    override suspend fun writeTo(t: List<PendingPairingRecord>, output: OutputStream) {
        output.write(Cbor.encodeToByteArray(ListSerializer(PendingPairingRecord.serializer()), t))
    }
}

/**
 * [PendingInviteStore] over a DataStore file. Before any read or write it moves the record left
 * in the [legacy] single slot by an older build into the list, then empties the slot.
 *
 * The migrated record has no invite text (`inviteWire = ""`) and counts as already shared
 * (`lastSharedAtMillis = createdAtMillis`): the old build always showed the invite right after
 * minting it. The migration is safe to re-run — if it was interrupted after the append but before
 * the slot was emptied, the record already in the list (same `pairingId`) is kept as is.
 */
class DataStorePendingInviteStore(
    private val dataStore: DataStore<List<PendingPairingRecord>>,
    private val legacy: DataStore<PendingPairingRecord?>,
) : PendingInviteStore {
    private val migrationMutex = Mutex()
    private var migrated = false

    override val records: Flow<List<PendingPairingRecord>> = flow {
        migrateLegacySlotOnce()
        emitAll(dataStore.data)
    }

    override suspend fun all(): List<PendingPairingRecord> {
        migrateLegacySlotOnce()
        return dataStore.data.first()
    }

    override suspend fun append(record: PendingPairingRecord) {
        migrateLegacySlotOnce()
        dataStore.updateData { it + record }
    }

    override suspend fun update(id: String, transform: (PendingPairingRecord) -> PendingPairingRecord) {
        migrateLegacySlotOnce()
        dataStore.updateData { list -> list.map { if (it.pairingId == id) transform(it) else it } }
    }

    override suspend fun remove(id: String) {
        migrateLegacySlotOnce()
        dataStore.updateData { list -> list.filterNot { it.pairingId == id } }
    }

    override suspend fun clear() {
        migrateLegacySlotOnce()
        dataStore.updateData { emptyList() }
    }

    private suspend fun migrateLegacySlotOnce() = migrationMutex.withLock {
        if (migrated) return@withLock
        val old = legacy.data.first()
        if (old != null) {
            dataStore.updateData { list ->
                if (list.any { it.pairingId == old.pairingId }) {
                    list
                } else {
                    list + old.copy(inviteWire = "", lastSharedAtMillis = old.createdAtMillis)
                }
            }
            legacy.updateData { null }
        }
        migrated = true
    }
}
