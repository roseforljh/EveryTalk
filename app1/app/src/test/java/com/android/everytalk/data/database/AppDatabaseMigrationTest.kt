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

    @Test
    fun `migration 13 to 14 preserves messages and adds ordered execution trace`() {
        val createHelper = openHelper(
            version = 13,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 14,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(13, oldVersion)
                assertEquals(14, newVersion)
                AppDatabase.MIGRATION_13_14.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT executionTrace FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 14 to 15 records preview execution target`() {
        val createHelper = openHelper(
            version = 14,
            onCreate = { db ->
                db.execSQL("CREATE TABLE computer_workspaces (id TEXT NOT NULL PRIMARY KEY, runMode TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE computer_previews (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL)",
                )
                db.execSQL("INSERT INTO computer_workspaces (id, runMode) VALUES ('direct', 'DIRECT'), ('container', 'CONTAINER')")
                db.execSQL("INSERT INTO computer_previews (id, workspaceId) VALUES ('host-preview', 'direct'), ('container-preview', 'container')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 15,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(14, oldVersion)
                assertEquals(15, newVersion)
                AppDatabase.MIGRATION_14_15.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT id, target FROM computer_previews ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("container-preview", cursor.getString(0))
            assertEquals("CONTAINER", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("host-preview", cursor.getString(0))
            assertEquals("HOST", cursor.getString(1))
        }
        db.query("PRAGMA table_info(computer_previews)").use { cursor ->
            var targetDefault: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "target") targetDefault = cursor.getString(4)
            }
            assertEquals("'CONTAINER'", targetDefault)
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 15 to 16 adds manual approval mode`() {
        val createHelper = openHelper(
            version = 15,
            onCreate = { db ->
                db.execSQL("CREATE TABLE computers (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO computers (id) VALUES ('computer-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 16,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(15, oldVersion)
                assertEquals(16, newVersion)
                AppDatabase.MIGRATION_15_16.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT permissionMode FROM computers WHERE id = 'computer-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("MANUAL", cursor.getString(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 16 to 17 creates agent fact tables without changing chats`() {
        val createHelper = openHelper(
            version = 16,
            onCreate = { db ->
                db.execSQL("CREATE TABLE chat_sessions (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO chat_sessions (id) VALUES ('chat-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 17,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(16, oldVersion)
                assertEquals(17, newVersion)
                AppDatabase.MIGRATION_16_17.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        val expectedTables = setOf(
            "agent_runs",
            "agent_entries",
            "agent_requests",
            "agent_request_usage",
            "agent_context_snapshots",
            "agent_compactions",
            "provider_continuation_states",
        )
        val actualTables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) actualTables += cursor.getString(0)
        }
        assertTrue(actualTables.containsAll(expectedTables))
        db.query("SELECT id FROM chat_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("chat-1", cursor.getString(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 17 to 18 adds recovery snapshot and protocol identity`() {
        val createHelper = openHelper(
            version = 17,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE agent_runs (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        userMessageId TEXT NOT NULL,
                        visibleAssistantMessageId TEXT NOT NULL,
                        configIdSnapshot TEXT,
                        status TEXT NOT NULL,
                        currentRequestOrdinal INTEGER NOT NULL,
                        terminalReason TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE provider_continuation_states (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        configId TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        endpoint TEXT NOT NULL,
                        model TEXT NOT NULL,
                        systemPromptFingerprint TEXT NOT NULL,
                        toolSchemaFingerprint TEXT NOT NULL,
                        summarizedThroughItemId TEXT,
                        opaqueStateJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_provider_continuation_states_sessionId_configId_provider_endpoint_model " +
                        "ON provider_continuation_states(sessionId, configId, provider, endpoint, model)",
                )
                db.execSQL(
                    "INSERT INTO agent_runs VALUES ('run-1', 'chat-1', 'user-1', 'assistant-1', 'config-1', " +
                        "'COMPLETED', 1, NULL, 1, 1)",
                )
                db.execSQL(
                    "INSERT INTO provider_continuation_states VALUES ('state-1', 'chat-1', 'config-1', 'OpenAI', " +
                        "'https://example.test', 'model', 'system', 'tools', NULL, '{}', 1)",
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 18,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(17, oldVersion)
                assertEquals(18, newVersion)
                AppDatabase.MIGRATION_17_18.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT requestSnapshotJson FROM agent_runs WHERE id = 'run-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        db.query("SELECT protocol, opaqueStateJson FROM provider_continuation_states WHERE id = 'state-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            assertEquals("{}", cursor.getString(1))
        }
        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list(provider_continuation_states)").use { cursor ->
            while (cursor.moveToNext()) indices += cursor.getString(1)
        }
        assertFalse(indices.contains("index_provider_continuation_states_sessionId_configId_provider_endpoint_model"))
        assertTrue(indices.contains("index_provider_continuation_states_sessionId_configId_protocol_provider_endpoint_model"))
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 18 to 19 preserves execution and adds nullable remote fields`() {
        val createHelper = openHelper(
            version = 18,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE computer_executions (
                        id TEXT NOT NULL PRIMARY KEY,
                        toolCallId TEXT NOT NULL,
                        computerId TEXT NOT NULL,
                        workspaceId TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        requestHash TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER,
                        finishedAt INTEGER,
                        exitCode INTEGER,
                        errorCode TEXT,
                        safeSummary TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO computer_executions(
                        id, toolCallId, computerId, workspaceId, toolName, requestHash,
                        status, startedAt, finishedAt, exitCode, errorCode, safeSummary
                    ) VALUES ('execution-1', 'tool-1', 'computer-1', 'workspace-1', 'exec', 'hash',
                        'RUNNING', 10, NULL, NULL, NULL, 'old summary')
                    """.trimIndent(),
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 19,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(18, oldVersion)
                assertEquals(19, newVersion)
                AppDatabase.MIGRATION_18_19.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query(
            "SELECT status, safeSummary, target, completionMode, remoteProcessId, remoteStatePath, " +
                "remoteStatus, remoteExitCode, lastObservedAt FROM computer_executions WHERE id = 'execution-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("RUNNING", cursor.getString(0))
            assertEquals("old summary", cursor.getString(1))
            for (column in 2..8) assertTrue(cursor.isNull(column))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 19 to 20 adds runId cursors cancelCompletedAt and resultAttachedAt`() {
        val createHelper = openHelper(
            version = 19,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE computer_executions (
                        id TEXT NOT NULL PRIMARY KEY,
                        toolCallId TEXT NOT NULL,
                        computerId TEXT NOT NULL,
                        workspaceId TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        requestHash TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER,
                        finishedAt INTEGER,
                        exitCode INTEGER,
                        errorCode TEXT,
                        safeSummary TEXT,
                        target TEXT,
                        completionMode TEXT,
                        remoteProcessId TEXT,
                        remoteStatePath TEXT,
                        remoteStatus TEXT,
                        remoteExitCode INTEGER,
                        lastObservedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO computer_executions(
                        id, toolCallId, computerId, workspaceId, toolName, requestHash,
                        status, startedAt, finishedAt, exitCode, errorCode, safeSummary
                    ) VALUES ('execution-1', 'tool-1', 'computer-1', 'workspace-1', 'exec', 'hash',
                        'RUNNING', 10, NULL, NULL, NULL, 'old summary')
                    """.trimIndent(),
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 20,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(19, oldVersion)
                assertEquals(20, newVersion)
                AppDatabase.MIGRATION_19_20.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query(
            "SELECT runId, stdoutCursor, stderrCursor, lastEventAt, cancelRequestedAt, cancelCompletedAt, resultAttachedAt " +
                "FROM computer_executions WHERE id = 'execution-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0)) // runId
            assertEquals(0L, cursor.getLong(1)) // stdoutCursor
            assertEquals(0L, cursor.getLong(2)) // stderrCursor
            assertTrue(cursor.isNull(3)) // lastEventAt
            assertTrue(cursor.isNull(4)) // cancelRequestedAt
            assertTrue(cursor.isNull(5)) // cancelCompletedAt
            assertTrue(cursor.isNull(6)) // resultAttachedAt
        }
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
