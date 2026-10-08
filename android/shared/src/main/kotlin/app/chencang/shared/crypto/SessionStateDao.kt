package app.chencang.shared.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionStateDao {

    /** Snapshot of every persisted session, read once on hydrate. */
    @Query("SELECT * FROM session_state")
    suspend fun all(): List<SessionStateEntity>

    /** Insert-or-replace the row for one peer label after every ratchet step. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SessionStateEntity)

    @Query("DELETE FROM session_state WHERE peer_label = :label")
    suspend fun delete(label: String)

    @Query("DELETE FROM session_state")
    suspend fun clear()
}
