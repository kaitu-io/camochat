package app.chencang.shared.crypto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted snapshot of one Double Ratchet [uniffi.chencang.Session]'s state,
 * keyed by the same opaque local label [RatchetSessionStore] uses in memory
 * (`peerLabel`). [stateBlob] is the output of `Session.serializeState()`; it is
 * rehydrated via `Session.fromSerializedState(...)` on process restart.
 *
 * Stored alongside the peer alias so the receive pipeline still sees the real
 * contact username after a restart (see [RatchetSessionStore.peerAliases]).
 *
 * At-rest protection relies on Android FBE under `/data/data`. SQLCipher is
 * out of scope.
 */
@Entity(tableName = "session_state")
data class SessionStateEntity(
    @PrimaryKey @ColumnInfo(name = "peer_label") val peerLabel: String,
    @ColumnInfo(name = "peer_alias") val peerAlias: String,
    @ColumnInfo(name = "state_blob") val stateBlob: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    // Room requires content-based equals/hashCode for ByteArray columns.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionStateEntity) return false
        return peerLabel == other.peerLabel &&
            peerAlias == other.peerAlias &&
            stateBlob.contentEquals(other.stateBlob) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = peerLabel.hashCode()
        result = 31 * result + peerAlias.hashCode()
        result = 31 * result + stateBlob.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
