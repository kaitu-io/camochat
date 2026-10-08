package app.chencang.shared.pairing.inband

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import java.io.InputStream
import java.io.OutputStream

/**
 * The response the invitee produced when accepting an invite, kept so it can be sent again
 * (spec 2026-10-01-three-tab-shell §3.5.6 / §7.5). One record per peer, keyed by the peer's
 * fingerprint. Public content only — the response text is what gets pasted into WeChat and the
 * digest is a hash of the (public) invite; no key material.
 */
@Serializable
data class PairingResponseRecord(
    val fingerprintHex: String,
    val responseWire: String,
    /** SHA-256 (lowercase hex) of the invite's payload bytes — see PairingCoordinator. */
    val inviteDigest: String,
    val createdAtMillis: Long,
    /** null = 「回暗号还没发出去」. */
    val lastSharedAtMillis: Long? = null,
)

interface PairingResponseStore {
    /** Observable list, in insertion order. */
    val records: Flow<List<PairingResponseRecord>>

    /** Stores [record], replacing any record already held for the same fingerprint. */
    suspend fun put(record: PairingResponseRecord)

    suspend fun recordFor(fingerprintHex: String): PairingResponseRecord?

    suspend fun findByInviteDigest(digest: String): PairingResponseRecord?

    /** No-op when there is no record for [fingerprintHex]. */
    suspend fun markShared(fingerprintHex: String, atMillis: Long)

    suspend fun remove(fingerprintHex: String)

    suspend fun clear()
}

/** DataStore [Serializer] for the response list (CBOR; empty or undecodable input → empty list). */
@OptIn(ExperimentalSerializationApi::class)
object PairingResponseListSerializer : Serializer<List<PairingResponseRecord>> {
    override val defaultValue: List<PairingResponseRecord> = emptyList()

    override suspend fun readFrom(input: InputStream): List<PairingResponseRecord> =
        decodeCborListOrEmpty(PairingResponseRecord.serializer(), input)

    override suspend fun writeTo(t: List<PairingResponseRecord>, output: OutputStream) {
        output.write(Cbor.encodeToByteArray(ListSerializer(PairingResponseRecord.serializer()), t))
    }
}

class DataStorePairingResponseStore(
    private val dataStore: DataStore<List<PairingResponseRecord>>,
) : PairingResponseStore {
    override val records: Flow<List<PairingResponseRecord>> = dataStore.data

    override suspend fun put(record: PairingResponseRecord) {
        dataStore.updateData { list -> list.filterNot { it.fingerprintHex == record.fingerprintHex } + record }
    }

    override suspend fun recordFor(fingerprintHex: String): PairingResponseRecord? =
        dataStore.data.first().firstOrNull { it.fingerprintHex == fingerprintHex }

    override suspend fun findByInviteDigest(digest: String): PairingResponseRecord? =
        dataStore.data.first().firstOrNull { it.inviteDigest == digest }

    override suspend fun markShared(fingerprintHex: String, atMillis: Long) {
        dataStore.updateData { list ->
            list.map { if (it.fingerprintHex == fingerprintHex) it.copy(lastSharedAtMillis = atMillis) else it }
        }
    }

    override suspend fun remove(fingerprintHex: String) {
        dataStore.updateData { list -> list.filterNot { it.fingerprintHex == fingerprintHex } }
    }

    override suspend fun clear() {
        dataStore.updateData { emptyList() }
    }
}
