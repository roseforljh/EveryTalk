package com.android.everytalk.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        stopKoin()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
        stopKoin()
    }

    @Test
    fun `migration 6 to 7 preserves common api config data`() {
        val createHelper = openHelper(
            version = 6,
            onCreate = { db ->
                createVersion6ApiConfigsTable(db)
            }
        )
        createHelper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO api_configs (
                    id, address, key, model, provider, name, channel, isValid,
                    modalityType, temperature, topP, maxTokens, defaultUseWebSearch,
                    imageSize, numInferenceSteps, guidanceScale, toolsJson,
                    enableCodeExecution, isImageGenConfig
                ) VALUES (
                    'api-1', 'https://example.test', 'secret', 'model-1', 'anthropic',
                    'Anthropic', 'Anthropic', 1, 'TEXT', 0.7, NULL, 8192, 0,
                    NULL, NULL, NULL, '[]', 0, 0
                )
                """.trimIndent()
            )
            close()
        }
        createHelper.close()

        val migrateHelper = openHelper(
            version = 7,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(6, oldVersion)
                assertEquals(7, newVersion)
                AppDatabase.MIGRATION_6_7.migrate(db)
            }
        )
        val db = migrateHelper.writableDatabase

        val cursor = db.query(
            "SELECT provider, channel, maxTokens, toolsJson FROM api_configs WHERE id = 'api-1'"
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("anthropic", it.getString(0))
            assertEquals("Anthropic", it.getString(1))
            assertEquals(8192, it.getInt(2))
            assertEquals("[]", it.getString(3))
        }
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(api_configs)").use { tableInfo ->
            while (tableInfo.moveToNext()) columns += tableInfo.getString(1)
        }
        assertFalse(columns.contains("legacyFeatureState"))
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 7 to 8 adds model parameters and removes conversation parameters`() {
        val createHelper = openHelper(
            version = 7,
            onCreate = { db ->
                createVersion7ApiConfigsTable(db)
                db.execSQL(
                    "CREATE TABLE conversation_params (conversationId TEXT NOT NULL PRIMARY KEY, config TEXT NOT NULL)"
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 8,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(7, oldVersion)
                assertEquals(8, newVersion)
                AppDatabase.MIGRATION_7_8.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(api_configs)").use { tableInfo ->
            while (tableInfo.moveToNext()) columns += tableInfo.getString(1)
        }
        assertTrue(columns.contains("modelParameters"))
        db.query("SELECT modelParameters FROM api_configs").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='conversation_params'").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 8 to 9 preserves messages and adds enabled tools`() {
        val createHelper = openHelper(
            version = 8,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 9,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(8, oldVersion)
                assertEquals(9, newVersion)
                AppDatabase.MIGRATION_8_9.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT enabledToolIds FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 9 to 10 preserves messages and leaves old usage empty`() {
        val createHelper = openHelper(
            version = 9,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 10,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(9, oldVersion)
                assertEquals(10, newVersion)
                AppDatabase.MIGRATION_9_10.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT tokenUsage, contextUsageSnapshot FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 10 to 11 preserves messages and adds execution steps`() {
        val createHelper = openHelper(
            version = 10,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 11,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(10, oldVersion)
                assertEquals(11, newVersion)
                AppDatabase.MIGRATION_10_11.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT executionSteps FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 11 to 12 preserves messages and leaves compression state empty`() {
        val createHelper = openHelper(
            version = 11,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 12,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(11, oldVersion)
                assertEquals(12, newVersion)
                AppDatabase.MIGRATION_11_12.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT contextCompressionState FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 12 to 13 preserves messages and creates local computer tables`() {
        val createHelper = openHelper(
            version = 12,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 13,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(12, oldVersion)
                assertEquals(13, newVersion)
                AppDatabase.MIGRATION_12_13.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase

        db.query("SELECT computerIdSnapshot, workspaceIdSnapshot FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }

        val expectedTables = setOf(
            "computers",
            "computer_workspaces",
            "conversation_computer_selections",
            "computer_executions",
            "computer_previews",
            "workspace_secret_metadata",
            "computer_audit_events",
        )
        val actualTables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) actualTables += cursor.getString(0)
        }
        assertTrue(actualTables.containsAll(expectedTables))

        db.close()
        migrateHelper.close()
    }

    private fun openHelper(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteOpenHelper {
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            onCreate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) {
                            onUpgrade(db, oldVersion, newVersion)
                        }
                    }
                )
                .build()
        )
    }

    private fun createVersion6ApiConfigsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE api_configs (
                id TEXT NOT NULL PRIMARY KEY,
                address TEXT NOT NULL,
                key TEXT NOT NULL,
                model TEXT NOT NULL,
                provider TEXT NOT NULL,
                name TEXT NOT NULL,
                channel TEXT NOT NULL,
                isValid INTEGER NOT NULL,
                modalityType TEXT NOT NULL,
                temperature REAL NOT NULL,
                topP REAL,
                maxTokens INTEGER,
                defaultUseWebSearch INTEGER,
                imageSize TEXT,
                numInferenceSteps INTEGER,
                guidanceScale REAL,
                toolsJson TEXT,
                enableCodeExecution INTEGER,
                legacyFeatureState TEXT,
                isImageGenConfig INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createVersion7ApiConfigsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE api_configs (
                id TEXT NOT NULL PRIMARY KEY,
                address TEXT NOT NULL,
                key TEXT NOT NULL,
                model TEXT NOT NULL,
                provider TEXT NOT NULL,
                name TEXT NOT NULL,
                channel TEXT NOT NULL,
                isValid INTEGER NOT NULL,
                modalityType TEXT NOT NULL,
                temperature REAL NOT NULL,
                topP REAL,
                maxTokens INTEGER,
                defaultUseWebSearch INTEGER,
                imageSize TEXT,
                numInferenceSteps INTEGER,
                guidanceScale REAL,
                toolsJson TEXT,
                enableCodeExecution INTEGER,
                isImageGenConfig INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
