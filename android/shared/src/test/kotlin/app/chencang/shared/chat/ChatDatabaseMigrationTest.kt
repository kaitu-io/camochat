package app.chencang.shared.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 没有 room-testing 的 MigrationTestHelper：用原生 SQLite 按 v2 的 Room 建表语句
 * 造一个老库（version=2），再让 Room 以 v3 打开。Room 在 onUpgrade 里跑完迁移后会
 * 按实体校验整张表（列、类型、NOT NULL、默认值、主键），不一致直接抛
 * "Migration didn't properly handle" —— 这就是本测试要抓的东西。
 */
@RunWith(RobolectricTestRunner::class)
class ChatDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(name)
    }

    private fun createV2WithOneRow() {
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_message` (`id` TEXT NOT NULL, `peer_username` TEXT NOT NULL, " +
                    "`direction` TEXT NOT NULL, `body` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL DEFAULT 'sealed', PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_message_peer_username_timestamp` " +
                    "ON `chat_message` (`peer_username`, `timestamp`)",
            )
            db.execSQL(
                "INSERT INTO chat_message (id, peer_username, direction, body, timestamp, status) " +
                    "VALUES ('old-1', 'alice', 'in', '老消息', 100, 'sent')",
            )
            db.version = 2
        }
    }

    /** v3 = 2026-09-25 富媒体的 Room 建表语句（chat_message 带 kind/share_text + media_item）。 */
    private fun createV3WithOneMediaRow() {
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_message` (`id` TEXT NOT NULL, `peer_username` TEXT NOT NULL, " +
                    "`direction` TEXT NOT NULL, `body` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL DEFAULT 'sealed', `kind` TEXT NOT NULL DEFAULT 'text', " +
                    "`share_text` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_message_peer_username_timestamp` " +
                    "ON `chat_message` (`peer_username`, `timestamp`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `media_item` (" +
                    "`message_id` TEXT NOT NULL, `idx` INTEGER NOT NULL, `kind` INTEGER NOT NULL, " +
                    "`dur_ms` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
                    "`byte_len` INTEGER NOT NULL, `blob_secret` BLOB NOT NULL, `blob_id` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL, `local_path` TEXT, `played` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`message_id`, `idx`))",
            )
            db.execSQL(
                "INSERT INTO chat_message (id, peer_username, direction, body, timestamp, status, kind, share_text) " +
                    "VALUES ('m-1', 'alice', 'out', '[图片]', 100, 'sealed', 'image', 'share')",
            )
            db.execSQL(
                "INSERT INTO media_item (message_id, idx, kind, dur_ms, width, height, byte_len, blob_secret, blob_id, state) " +
                    "VALUES ('m-1', 0, 2, 0, 10, 20, 100, X'01', 'bid', 'failed')",
            )
            db.version = 3
        }
    }

    @Test
    fun `v3 database migrates to v4 with empty upload failure and upload since`() = runTest {
        createV3WithOneMediaRow()
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .addMigrations(*ChatDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val item = db.mediaDao().forMessage("m-1").single()
            assertThat(item.state).isEqualTo(MediaItem.STATE_FAILED)
            assertThat(item.uploadFailure).isNull()
            assertThat(item.uploadSince).isNull()
            // 迁移前就失败的项没有原因 = 可重试：自愈名单里要有它
            assertThat(db.mediaDao().sharedOutgoingWithUnuploadedItems()).containsExactly("m-1")
            db.mediaDao().failPermanently("m-1", 0, "TOO_LARGE")
            assertThat(db.mediaDao().forMessage("m-1").single().uploadFailure).isEqualTo("TOO_LARGE")
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 database migrates to v3 keeping history and defaulting kind to text`() = runTest {
        createV2WithOneRow()
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .addMigrations(*ChatDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val thread = db.dao().observeThread("alice").first()
            assertThat(thread).hasSize(1)
            assertThat(thread[0].body).isEqualTo("老消息")
            assertThat(thread[0].status).isEqualTo(ChatMessage.STATUS_SENT)
            assertThat(thread[0].kind).isEqualTo(ChatMessage.KIND_TEXT)
            assertThat(thread[0].shareText).isNull()

            db.mediaDao().insertAll(
                listOf(
                    MediaItem(
                        messageId = "old-1", index = 0, kind = 2, durMs = 0, width = 10, height = 20,
                        byteLen = 100L, blobSecret = ByteArray(32) { 1 }, blobId = "A".repeat(22),
                        state = MediaItem.STATE_PENDING,
                    ),
                ),
            )
            assertThat(db.mediaDao().forMessage("old-1").single().played).isFalse()
        } finally {
            db.close()
        }
    }
}
