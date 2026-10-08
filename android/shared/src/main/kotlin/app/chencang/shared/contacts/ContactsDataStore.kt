package app.chencang.shared.contacts

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.chencang.shared.model.ContactListSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore [Serializer] for [ContactListSnapshot]. Uses CBOR (not protobuf) so we avoid
 * pulling in the protobuf-gradle-plugin and its codegen step. Field-tag stability is
 * provided by @SerialName on Contact's fields.
 *
 * V2 may migrate to proto if cross-platform schema sharing becomes worthwhile.
 */
@OptIn(ExperimentalSerializationApi::class)
object ContactListSerializer : Serializer<ContactListSnapshot> {
    // ignoreUnknownKeys: a snapshot written by a keyboard-era build still carries
    // the retired `sticky_recipient` field (2026-09-24 teardown). Decoding it
    // strictly would drop the whole blob via the catch below; tolerating the
    // stray field keeps the user's contacts intact across the upgrade.
    private val cbor = Cbor { ignoreUnknownKeys = true }

    override val defaultValue: ContactListSnapshot = ContactListSnapshot()

    override suspend fun readFrom(input: InputStream): ContactListSnapshot {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        // V1: a schema-incompatible blob (e.g. old username-keyed contacts from
        // before the fingerprint re-key) is dropped cleanly, not migrated.
        return try {
            cbor.decodeFromByteArray(ContactListSnapshot.serializer(), bytes)
        } catch (e: SerializationException) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: ContactListSnapshot, output: OutputStream) {
        output.write(cbor.encodeToByteArray(ContactListSnapshot.serializer(), t))
    }
}

/**
 * Minimal abstraction over [DataStore] so tests can plug an in-memory implementation
 * without standing up an Android Context.
 */
interface ContactsStore {
    val data: Flow<ContactListSnapshot>
    suspend fun updateData(transform: suspend (ContactListSnapshot) -> ContactListSnapshot): ContactListSnapshot
}

class DataStoreContactsStore(private val backing: DataStore<ContactListSnapshot>) : ContactsStore {
    override val data: Flow<ContactListSnapshot> = backing.data
    override suspend fun updateData(
        transform: suspend (ContactListSnapshot) -> ContactListSnapshot,
    ): ContactListSnapshot = backing.updateData(transform)
}

/** Pure-Kotlin in-memory store for unit tests. Thread-safe via [MutableStateFlow]. */
class InMemoryContactsStore(initial: ContactListSnapshot = ContactListSnapshot()) : ContactsStore {
    private val state = MutableStateFlow(initial)
    override val data: Flow<ContactListSnapshot> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (ContactListSnapshot) -> ContactListSnapshot,
    ): ContactListSnapshot {
        val next = transform(state.value)
        state.value = next
        return next
    }
}
