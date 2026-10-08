package app.chencang.shared.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query(
        "SELECT * FROM chat_message WHERE peer_username = :peerUsername " +
            "ORDER BY timestamp ASC",
    )
    fun observeThread(peerUsername: String): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessage)

    @Query("DELETE FROM chat_message")
    suspend fun clearAll()

    @Query("DELETE FROM chat_message WHERE peer_username = :peerUsername")
    suspend fun clearPeer(peerUsername: String)

    @Query("UPDATE chat_message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Atomic `sealed` -> `copied` for an own outgoing message; returns rows changed (0 or 1). */
    @Query(
        "UPDATE chat_message SET status = 'copied' " +
            "WHERE id = :id AND direction = 'out' AND peer_username = :peer AND status != 'copied' AND status != 'sent'",
    )
    suspend fun markCopiedGuarded(id: String, peer: String): Int

    /** Atomic anything-but-`sent` -> `sent` for an own outgoing message; returns rows changed (0 or 1). */
    @Query(
        "UPDATE chat_message SET status = 'sent' " +
            "WHERE id = :id AND direction = 'out' AND peer_username = :peer AND status != 'sent'",
    )
    suspend fun markSentGuarded(id: String, peer: String): Int

    @Query(
        "SELECT * FROM chat_message m " +
            "WHERE timestamp = (SELECT MAX(timestamp) FROM chat_message WHERE peer_username = m.peer_username)",
    )
    fun observeLatestPerPeer(): Flow<List<ChatMessage>>

    @Query("SELECT id FROM chat_message WHERE peer_username = :peerUsername")
    suspend fun idsForPeer(peerUsername: String): List<String>

    @Query("SELECT * FROM chat_message WHERE id = :id")
    suspend fun getById(id: String): ChatMessage?

    @Query("DELETE FROM chat_message WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE chat_message SET share_text = :shareText WHERE id = :id")
    suspend fun updateShareText(id: String, shareText: String)

    /**
     * The stored row whose share text is exactly [wire], or ends with a newline plus [wire]
     * (outgoing media keeps a two-line share text). A mere suffix without the newline never matches.
     */
    @Query(
        "SELECT * FROM chat_message WHERE share_text = :wire OR " +
            "(length(share_text) > length(:wire) AND " +
            "substr(share_text, -length(:wire) - 1) = char(10) || :wire) LIMIT 1",
    )
    suspend fun findByWire(wire: String): ChatMessage?
}
