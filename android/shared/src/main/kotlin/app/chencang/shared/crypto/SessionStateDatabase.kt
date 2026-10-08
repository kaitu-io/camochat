package app.chencang.shared.crypto

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Double Ratchet session-state database. Single-table for V1. Lives in app
 * private storage (`<dataDir>/databases/cc-sessions.db`), so Android FBE
 * encrypts it whenever the device is locked. SQLCipher is out of scope.
 *
 * Deliberately a SEPARATE database file from the chat-history database
 * ([app.chencang.shared.chat.ChatDatabase]): session state and chat history
 * are independent concerns with independent lifecycles (wiping one must not
 * touch the other).
 *
 * Process-shared: the Companion app and the IME service both call into this via
 * [app.chencang.shared.CcServiceLocator]; Room handles cross-process
 * synchronization through the SQLite WAL.
 */
@Database(
    entities = [SessionStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SessionStateDatabase : RoomDatabase() {
    abstract fun sessionStateDao(): SessionStateDao

    companion object {
        private const val DB_NAME = "cc-sessions.db"

        @Volatile
        private var instance: SessionStateDatabase? = null

        fun get(context: Context): SessionStateDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionStateDatabase::class.java,
                    DB_NAME,
                )
                    // V1: clean wipe on schema change. No production data yet,
                    // V2 will switch to proper migrations.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
