package com.android.everytalk.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用 42.json 创建真实旧库，再执行 42→43→44 迁移。
 * 这条测试故意使用一万条消息，防止迁移中逐条 Kotlin/Room 写入造成启动级卡顿。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private companion object {
        const val DATABASE_NAME = "migration-42-to-44-large-test"
        const val MESSAGE_COUNT = 10_000
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate42To44_preservesChatsMessagesMemoriesGroupsAndPinnedItems() {
        val oldDatabase = helper.createDatabase(DATABASE_NAME, 42)
        oldDatabase.insertFixture()
        oldDatabase.close()

        val startedAt = System.nanoTime()
        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            44,
            true,
            AppDatabase.MIGRATION_42_43,
            AppDatabase.MIGRATION_43_44,
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        migrated.query("SELECT COUNT(*) FROM chat_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        migrated.query("SELECT title, creationTimestamp, lastModifiedTimestamp FROM chat_sessions WHERE id = 'session-text'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("文字会话", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(20000L, cursor.getLong(2))
        }
        migrated.query("SELECT COUNT(*), COUNT(DISTINCT uuid) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(MESSAGE_COUNT, cursor.getInt(0))
            assertEquals(MESSAGE_COUNT, cursor.getInt(1))
        }
        migrated.query("SELECT text, timestamp, sessionId FROM messages WHERE id = ?", arrayOf("message-9999")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("正文-9999", cursor.getString(0))
            assertEquals(9999L, cursor.getLong(1))
            assertEquals("session-text", cursor.getString(2))
        }
        migrated.query("SELECT COUNT(*) FROM messages WHERE sessionId = 'session-text'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(MESSAGE_COUNT, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM chat_sessions WHERE uuid <> '' AND updated_at = lastModifiedTimestamp AND sync_status = 'PENDING'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT category, content, origin, sourceConversationId, sourceMessageId, updatedAt, uuid, sync_status FROM memories WHERE id = 'memory-text'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("OTHER", cursor.getString(0))
            assertEquals("长期记忆", cursor.getString(1))
            assertEquals("MANUAL", cursor.getString(2))
            assertEquals("session-text", cursor.getString(3))
            assertEquals("message-9999", cursor.getString(4))
            assertEquals(40000L, cursor.getLong(5))
            assertNotEquals("", cursor.getString(6))
            assertEquals("PENDING", cursor.getString(7))
        }
        migrated.query("SELECT COUNT(*) FROM conversation_groups WHERE groupName = '工作'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT conversationIds FROM conversation_groups WHERE groupName = '工作'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[\"session-text\"]", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM pinned_items WHERE id = 'session-text' AND isImageGeneration = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT kind, `key`, value, sync_status FROM user_settings ORDER BY kind, `key`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("conversation_groups", cursor.getString(0))
            assertEquals("工作", cursor.getString(1))
            assertTrue(cursor.getString(2).contains("session-text".let { com.android.everytalk.data.database.entities.localSyncUuid("chat_sessions", it) }))
            assertEquals("PENDING", cursor.getString(3))
            assertTrue(cursor.moveToNext())
            assertEquals("pinned_items", cursor.getString(0))
            assertEquals("text", cursor.getString(1))
            assertTrue(cursor.getString(2).contains("session-text".let { com.android.everytalk.data.database.entities.localSyncUuid("chat_sessions", it) }))
            assertEquals("PENDING", cursor.getString(3))
            assertTrue(!cursor.moveToNext())
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sync_attachments'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sync_attachments", cursor.getString(0))
        }

        // 迁移是本地数据库操作，耗时记录在测试输出，阈值只用于避免明显的启动级退化。
        println("42->43 迁移 ${MESSAGE_COUNT} 条消息耗时 ${elapsedMs}ms")
        assertTrue("迁移耗时异常：${elapsedMs}ms", elapsedMs < 5_000)
        migrated.close()
    }

    private fun SupportSQLiteDatabase.insertFixture() {
        beginTransaction()
        try {
            execSQL("INSERT INTO chat_sessions(id, creationTimestamp, lastModifiedTimestamp, isImageGeneration, title) VALUES('session-text', 1, 20000, 0, '文字会话')")
            execSQL("INSERT INTO chat_sessions(id, creationTimestamp, lastModifiedTimestamp, isImageGeneration, title) VALUES('session-image', 2, 20001, 1, '生图会话')")
            execSQL("INSERT INTO memories(id, category, content, origin, sourceConversationId, sourceMessageId, createdAt, updatedAt, enabled) VALUES('memory-text', 'OTHER', '长期记忆', 'MANUAL', 'session-text', 'message-9999', 30000, 40000, 1)")
            execSQL("INSERT INTO conversation_groups(groupName, conversationIds) VALUES('工作', '[\"session-text\"]')")
            execSQL("INSERT INTO pinned_items(id, isImageGeneration) VALUES('session-text', 0)")
            repeat(MESSAGE_COUNT) { index ->
                execSQL(
                    """
                    INSERT INTO messages(
                        id, sessionId, text, contentParts, sender, reasoning, contentStarted, isError, name,
                        timestamp, isPlaceholderName, webSearchResults, currentWebSearchStage, imageUrls, attachments,
                        outputType, parts, executionStatus, executionSteps, executionTrace, executionFinishedAt,
                        enabledToolIds, computerIdSnapshot, workspaceIdSnapshot, modelName, providerName, tokenUsage,
                        contextUsageSnapshot, contextCompressionState, storageFingerprint
                    ) VALUES(?, ?, ?, '[]', 'User', NULL, 1, 0, NULL, ?, 0, NULL, NULL, NULL, '[]',
                        'text', '[]', NULL, '[]', '[]', NULL, '[]', NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?)
                    """.trimIndent(),
                    arrayOf<Any?>("message-$index", "session-text", "正文-$index", index.toLong(), "指纹-$index"),
                )
            }
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
}
