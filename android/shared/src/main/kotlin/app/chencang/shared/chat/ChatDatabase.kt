package app.chencang.shared.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** 在一个数据库事务里跑 [block]；[ChatRepository] 靠它保证「消息 + 媒体条目」要么都在、要么都不在。 */
typealias InTransaction = suspend (block: suspend () -> Unit) -> Unit

/**
 * 生产与 Room 测试都用这个；纯内存假 DAO 的测试传 `{ it() }`。
 *
 * 命名为 `inTransactionRunner` 而非 `inTransaction`：`RoomDatabase` 自带一个同名
 * 无参成员 `inTransaction(): Boolean`（「当前线程是否已在事务里」），成员总是盖过
 * 同名扩展函数——若叫 `inTransaction()`，`chatDb.inTransaction()` 会静默解析到那个
 * 成员，返回 `Boolean` 而不是 [InTransaction]，在赋值处炸类型不匹配。
 */
fun ChatDatabase.inTransactionRunner(): InTransaction = { block -> withTransaction { block() } }

/**
 * 密信线程数据库。v3（2026-09-25 富媒体）：`chat_message` 加 `kind` / `share_text`，
 * 新表 `media_item`（每条媒体消息 1..9 个条目）。v4（2026-09-30）：`media_item` 加
 * `upload_failure` / `upload_since`。
 */
@Database(
    entities = [ChatMessage::class, MediaItem::class],
    version = 4,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatMessageDao
    abstract fun mediaDao(): MediaItemDao

    /**
     * Also clears the companion-held singleton reference so a later [create]
     * rebuilds a fresh (open) database instead of handing back this
     * now-closed one. Matters for the account-wipe path: `wipeAll()` →
     * `CcServiceLocator.reset()` → `close()` → `from(context)` again, all in
     * the same process (this app has no App-Group-style process split — see
     * CLAUDE.md) — without this, every chat operation after a wipe would
     * throw "Cannot access database on a closed instance of RoomDatabase".
     */
    override fun close() {
        super.close()
        synchronized(Companion) {
            if (instance === this) instance = null
        }
    }

    companion object {
        private const val DB_NAME = "cc-chat.db"

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN status TEXT NOT NULL DEFAULT 'sealed'")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN share_text TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_item` (" +
                        "`message_id` TEXT NOT NULL, `idx` INTEGER NOT NULL, `kind` INTEGER NOT NULL, " +
                        "`dur_ms` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
                        "`byte_len` INTEGER NOT NULL, `blob_secret` BLOB NOT NULL, `blob_id` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `local_path` TEXT, `played` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`message_id`, `idx`))",
                )
            }
        }

        /** v4（2026-09-30 先分享、后上传）：每项的永久上传失败原因 + 等待上传的起点，两列都可空。 */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_item ADD COLUMN upload_failure TEXT")
                db.execSQL("ALTER TABLE media_item ADD COLUMN upload_since INTEGER")
            }
        }

        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile
        private var instance: ChatDatabase? = null

        fun create(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
