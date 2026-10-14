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
 * 使用 42.json 创建真实旧库，再执行 42→43 迁移。
 * 这条测试故意使用一万条消息，防止迁移中逐条 Kotlin/Room 写入造成启动级卡顿。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private companion object {
        const val DATABASE_NAME = "migration-42-to-43-large-test"
        const val MESSAGE_COUNT = 10_000
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate42To43_preservesTenThousandMessagesAndCreatesUniqueUuids() {
        val oldDatabase = helper.createDatabase(DATABASE_NAME, 42)
        oldDatabase.insertFixture()
        oldDatabase.close()

        val startedAt = System.nanoTime()
        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            43,
            true,
            AppDatabase.MIGRATION_42_43,
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

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
