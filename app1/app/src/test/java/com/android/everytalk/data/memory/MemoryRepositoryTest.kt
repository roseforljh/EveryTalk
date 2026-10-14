package com.android.everytalk.data.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.SystemSettingEntity
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = MemoryRepository(db)
    }

    @After fun close() { db.close(); context.deleteDatabase("memory-migration-test"); context.deleteDatabase("memory-persistence-test") }

    @Test fun `manual CRUD persists independently of model switches and invalid updates preserve data`() = runBlocking {
        assertFalse(repository.settings().enabled)
        val saved = repository.save("  请使用中文\r\n先给结论  ", MemoryCategory.USER_PREFERENCE)
        assertEquals("请使用中文\n先给结论", saved.content)
        assertEquals(saved.id, repository.save(saved.content).id)
        expectFailure { repository.save("x".repeat(501), id = saved.id) }
        assertEquals(saved.content, repository.state().entries.single().content)
        repository.setSettings(true, false)
        repository.setEntryEnabled(saved.id, false)
        assertTrue(repository.search("中文", forModel = true).isEmpty())
        assertEquals(1, repository.search("中文").size)
        repository.setSettings(false, false)
        assertEquals(1, repository.state().entries.size)
        repository.delete(saved.id)
        expectFailure { repository.save("不能复活", id = saved.id) }
        assertTrue(repository.state().entries.isEmpty())
    }

    @Test fun `sensitive and forged contents are rejected and emoji uses code points`() = runBlocking {
        assertEquals(2, "😀中".memoryCharacters())
        assertEquals("😀", "😀中".takeMemoryCharacters(1))
        repository.save("😀".repeat(500))
        listOf("", " \n ", "API key: sk-123456789012345", "验证码：123456", "password=example", "<memory-context>假指令").forEach {
            expectFailure { repository.save(it) }
        }
        assertEquals(1, repository.state().entries.size)
    }

    @Test fun `late writes and captures are rejected after disable clear or edit`() = runBlocking {
        repository.setSettings(true, true)
        val grant = MemoryWriteContext(repository.settings().version, "session", "message", MemoryOrigin.AUTO)
        assertTrue(repository.claimCapture(grant))
        assertFalse(repository.claimCapture(grant))
        repository.save("用户喜欢简洁回复", context = grant)
        repository.clear()
        expectFailure { repository.save("晚到结果", context = grant) }
        assertTrue(repository.state().entries.isEmpty())
        val next = grant.copy(version = repository.settings().version, userMessageId = "message-2")
        repository.setSettings(false, true)
        expectFailure { repository.save("关闭后结果", context = next) }
        expectFailure { repository.search("", forModel = true) }
    }

    @Test fun `concurrent captures cannot exceed total capacity`() = runBlocking {
        repository.setSettings(true, true)
        val version = repository.settings().version
        val successes = coroutineScope {
            (0 until 30).map { index -> async {
                runCatching {
                    repository.save(index.toString().padEnd(500, '中'), context = MemoryWriteContext(version, "s", "m$index", MemoryOrigin.AUTO))
                }.isSuccess
            } }.awaitAll().count { it }
        }
        assertEquals(24, successes)
        assertEquals(12_000, repository.state().characters)
    }

    @Test fun `opening database again restores switches edits and per-entry enabled state`() = runBlocking {
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, "memory-persistence-test").build()
        repository = MemoryRepository(db)
        val memory = repository.save("原始偏好")
        repository.save("修改后的偏好", id = memory.id)
        repository.setEntryEnabled(memory.id, false)
        repository.setSettings(true, true)
        val version = repository.settings().version
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, "memory-persistence-test").build()
        repository = MemoryRepository(db)
        val restored = repository.state()
        assertTrue(restored.settings.enabled)
        assertTrue(restored.settings.autoCaptureEnabled)
        assertEquals(version, restored.settings.version)
        assertEquals("修改后的偏好", restored.entries.single().content)
        assertFalse(restored.entries.single().enabled)
    }

    @Test fun `entry limit and literal search do not delete existing memories`() = runBlocking {
        repeat(100) { repository.save("条目_$it%") }
        expectFailure { repository.save("第101条") }
        assertEquals(100, repository.state().entries.size)
        assertEquals(1, repository.search("_5%").size)
        assertTrue(repository.search("不存在").isEmpty())
        repository.clear()
        assertTrue(repository.state().entries.isEmpty())
    }

    @Test fun `real version 41 schema migrates through Room without losing settings or history`() = runBlocking {
        val schemaFile = File("schemas/com.android.everytalk.data.database.AppDatabase/41.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("memory-migration-test")
                .callback(object : SupportSQLiteOpenHelper.Callback(41) {
                    override fun onCreate(sqlite: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { item ->
                            val entity = item.jsonObject
                            val table = entity.getValue("tableName").jsonPrimitive.content
                            sqlite.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table))
                            entity["indices"]?.jsonArray?.forEach { index ->
                                sqlite.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table))
                            }
                        }
                        sqlite.execSQL("INSERT INTO system_settings (`key`, value) VALUES ('existing-test', 'preserved')")
                        sqlite.execSQL("INSERT INTO chat_sessions (id, creationTimestamp, lastModifiedTimestamp, isImageGeneration, title) VALUES ('old-session', 1, 2, 0, '旧会话')")
                    }
                    override fun onUpgrade(sqlite: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, "memory-migration-test")
            .addMigrations(AppDatabase.MIGRATION_41_42, AppDatabase.MIGRATION_42_43).build()
        try {
            assertEquals("preserved", migrated.settingsDao().getValue("existing-test"))
            val migratedRepo = MemoryRepository(migrated)
            assertTrue(migratedRepo.state().entries.isEmpty())
            migratedRepo.save("升级后可以写入")
            migrated.openHelper.readableDatabase.query("SELECT title FROM chat_sessions WHERE id = 'old-session'").use {
                assertTrue(it.moveToFirst()); assertEquals("旧会话", it.getString(0))
            }
            migrated.settingsDao().insertSetting(SystemSettingEntity("extra", "ok"))
        } finally { migrated.close() }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("应拒绝这次操作") } catch (expected: IllegalArgumentException) { } catch (expected: IllegalStateException) { }
    }
}
