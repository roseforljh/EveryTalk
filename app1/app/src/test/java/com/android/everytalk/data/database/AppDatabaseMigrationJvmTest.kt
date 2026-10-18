package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * JVM/Robolectric 迁移回归测试。
 * 旧库表结构直接来自 42.json，便于在没有设备时也验证 Room 的实际升级和耗时。
 */
@Config(sdk = [34], application = android.app.Application::class)
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationJvmTest {
    private lateinit var context: Context
    private val databaseName = "migration-42-to-43-jvm"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (::context.isInitialized) context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `42到43迁移一万条消息保留数据并生成唯一UUID`() {
        val schema = Json.parseToJsonElement(
            File("schemas/com.android.everytalk.data.database.AppDatabase/42.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(42) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { item ->
                            val entity = item.jsonObject
                            val table = entity.getValue("tableName").jsonPrimitive.content
                            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table))
                            entity["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table))
                            }
                        }
                        db.beginTransaction()
                        try {
                            db.execSQL("INSERT INTO chat_sessions(id, creationTimestamp, lastModifiedTimestamp, isImageGeneration, title) VALUES('large-session', 1, 20000, 0, '旧会话')")
                            db.execSQL("INSERT INTO memories(id, category, content, origin, sourceConversationId, sourceMessageId, createdAt, updatedAt, enabled) VALUES('memory-1', 'OTHER', '旧记忆', 'MANUAL', 'large-session', 'message-1', 3, 40000, 1)")
                            db.execSQL("INSERT INTO pinned_items(id, isImageGeneration) VALUES('large-session', 0)")
                            db.execSQL("INSERT INTO conversation_groups(groupName, conversationIds) VALUES('工作', '[\"large-session\"]')")
                            repeat(10_000) { index ->
                                db.execSQL(
                                    "INSERT INTO messages(id, sessionId, text, contentParts, sender, reasoning, contentStarted, isError, name, timestamp, isPlaceholderName, webSearchResults, currentWebSearchStage, imageUrls, attachments, outputType, parts, executionStatus, executionSteps, executionTrace, executionFinishedAt, enabledToolIds, computerIdSnapshot, workspaceIdSnapshot, modelName, providerName, tokenUsage, contextUsageSnapshot, contextCompressionState, storageFingerprint) VALUES(?, 'large-session', ?, '[]', 'User', NULL, 1, 0, NULL, ?, 0, NULL, NULL, NULL, '[]', 'text', '[]', NULL, '[]', '[]', NULL, '[]', NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?)",
                                    arrayOf<Any?>("message-$index", "正文-$index", index.toLong(), "fingerprint-$index"),
                                )
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()

        val startedAt = System.nanoTime()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_42_43, AppDatabase.MIGRATION_43_44)
            .build()
        try {
            val database = migrated.openHelper.readableDatabase
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            database.query("SELECT COUNT(*), COUNT(DISTINCT uuid) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10_000, cursor.getInt(0))
                assertEquals(10_000, cursor.getInt(1))
            }
            database.query("SELECT text, timestamp, sessionId FROM messages WHERE id = 'message-9999'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("正文-9999", cursor.getString(0))
                assertEquals(9999L, cursor.getLong(1))
                assertEquals("large-session", cursor.getString(2))
            }
            database.query("SELECT content, updated_at, sync_status FROM memories WHERE id = 'memory-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧记忆", cursor.getString(0))
                assertEquals(40000L, cursor.getLong(1))
                assertEquals("PENDING", cursor.getString(2))
            }
            database.query("SELECT kind, `key`, value, sync_status FROM user_settings ORDER BY kind").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("conversation_groups", cursor.getString(0))
                assertEquals("工作", cursor.getString(1))
                assertTrue(cursor.getString(2).contains("large-session".let { com.android.everytalk.data.database.entities.localSyncUuid("chat_sessions", it) }))
                assertEquals("PENDING", cursor.getString(3))
                assertTrue(cursor.moveToNext())
                assertEquals("pinned_items", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(3))
            }
            println("42->43 迁移 10000 条消息耗时 ${elapsedMs}ms")
            assertTrue("迁移耗时异常：${elapsedMs}ms", elapsedMs < 5_000)
        } finally {
            migrated.close()
        }
    }
}
