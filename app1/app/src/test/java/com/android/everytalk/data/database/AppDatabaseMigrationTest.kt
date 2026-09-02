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
    fun `migration 31 to 32 adds structured steering payload without changing legacy rows`() {
        val createHelper = openHelper(
            version = 31,
            onCreate = { db ->
                db.execSQL(
                    "CREATE TABLE agent_steering_messages (" +
                        "id TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, content TEXT NOT NULL, " +
                        "status TEXT NOT NULL, createdAt INTEGER NOT NULL, consumedAt INTEGER)",
                )
                db.execSQL(
                    "INSERT INTO agent_steering_messages VALUES ('steer-1', 'run-1', '继续', 'PENDING', 1, NULL)",
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 32,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(31, oldVersion)
                assertEquals(32, newVersion)
                AppDatabase.MIGRATION_31_32.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        assertTrue(columns(db, "agent_steering_messages").contains("payloadJson"))
        db.query("SELECT content, payloadJson FROM agent_steering_messages WHERE id = 'steer-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("继续", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 30 to 31 creates intervention ledger without secret columns`() {
        val createHelper = openHelper(
            version = 30,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE agent_runs (
                        id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, userMessageId TEXT NOT NULL,
                        visibleAssistantMessageId TEXT NOT NULL, configIdSnapshot TEXT, requestSnapshotJson TEXT,
                        status TEXT NOT NULL, currentRequestOrdinal INTEGER NOT NULL, terminalReason TEXT,
                        createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 31,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(30, oldVersion)
                assertEquals(31, newVersion)
                AppDatabase.MIGRATION_30_31.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        val runColumns = columns(db, "agent_runs")
        val suspensionColumns = columns(db, "agent_suspensions")
        val grantColumns = columns(db, "agent_capability_grants")
        val slotColumns = columns(db, "agent_execution_slots")

        assertTrue(runColumns.contains("runGeneration"))
        assertTrue(runColumns.contains("loopState"))
        assertTrue(suspensionColumns.contains("resolutionNonceHash"))
        assertTrue(suspensionColumns.contains("targetBindingRef"))
        assertTrue(grantColumns.containsAll(listOf("usageCount", "grantUseAttemptId", "rowVersion")))
        assertTrue(slotColumns.containsAll(listOf("executionGeneration", "state", "suspensionId")))
        assertFalse(suspensionColumns.any { it in setOf("password", "token", "otp", "secret", "codeVerifier") })
        db.close()
        migrateHelper.close()
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
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

    @Test
    fun `migration 20 to 21 preserves messages and adds execution finish time`() {
        val createHelper = openHelper(
            version = 20,
            onCreate = { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO messages (id) VALUES ('message-1')")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 21,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(20, oldVersion)
                assertEquals(21, newVersion)
                AppDatabase.MIGRATION_20_21.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT executionFinishedAt FROM messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 22 to 23 removes skill audit fields and preserves installed data`() {
        val createHelper = openHelper(
            version = 22,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE skill_installations (
                        skillId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
                        sourceType TEXT NOT NULL, sourceRepository TEXT, sourcePath TEXT,
                        currentHash TEXT NOT NULL, enabled INTEGER NOT NULL, invocationMode TEXT NOT NULL,
                        auditStatus TEXT NOT NULL, updateHash TEXT, createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL, lastUsedAt INTEGER, useCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE skill_versions (
                        skillId TEXT NOT NULL, contentHash TEXT NOT NULL, versionLabel TEXT,
                        rootPath TEXT NOT NULL, manifestJson TEXT NOT NULL, frontmatterJson TEXT NOT NULL,
                        auditJson TEXT, installedAt INTEGER NOT NULL,
                        PRIMARY KEY(skillId, contentHash),
                        FOREIGN KEY(skillId) REFERENCES skill_installations(skillId) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO skill_installations VALUES (
                        'skill-1', '测试 Skill', '说明', 'REMOTE', 'https://github.com/a/b', 'skills/test',
                        'hash-1', 0, 'AUTO', 'FAIL', 'hash-2', 10, 20, 15, 3
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO skill_versions VALUES (
                        'skill-1', 'hash-1', 'v1', '/skills/hash-1', '[]', '{}', '{"status":"FAIL"}', 10
                    )
                    """.trimIndent(),
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 23,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(22, oldVersion)
                assertEquals(23, newVersion)
                AppDatabase.MIGRATION_22_23.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT currentHash, enabled, updateHash, useCount FROM skill_installations WHERE skillId = 'skill-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("hash-1", it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals("hash-2", it.getString(2))
            assertEquals(3L, it.getLong(3))
        }
        db.query("SELECT versionLabel, rootPath, installedAt FROM skill_versions WHERE skillId = 'skill-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("v1", it.getString(0))
            assertEquals("/skills/hash-1", it.getString(1))
            assertEquals(10L, it.getLong(2))
        }
        val installationColumns = mutableListOf<String>()
        db.query("PRAGMA table_info(skill_installations)").use { while (it.moveToNext()) installationColumns += it.getString(1) }
        val versionColumns = mutableListOf<String>()
        db.query("PRAGMA table_info(skill_versions)").use { while (it.moveToNext()) versionColumns += it.getString(1) }
        assertFalse(installationColumns.contains("auditStatus"))
        assertFalse(versionColumns.contains("auditJson"))
        db.query("PRAGMA foreign_key_list(skill_versions)").use {
            assertTrue(it.moveToFirst())
            assertEquals("skill_installations", it.getString(2))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 23 to 24 groups remote skills by repository`() {
        val createHelper = openHelper(
            version = 23,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE skill_installations (
                        skillId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
                        sourceType TEXT NOT NULL, sourceRepository TEXT, sourcePath TEXT,
                        currentHash TEXT NOT NULL, enabled INTEGER NOT NULL, invocationMode TEXT NOT NULL,
                        updateHash TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        lastUsedAt INTEGER, useCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO skill_installations VALUES
                    ('remote-1', 'ponytail', '说明', 'REMOTE', 'https://github.com/dietrichgebert/ponytail', 'skills/ponytail', 'h1', 1, 'AUTO', NULL, 1, 1, NULL, 0),
                    ('remote-2', 'ponytail-review', '说明', 'REMOTE', 'https://github.com/dietrichgebert/ponytail', 'skills/ponytail-review', 'h2', 0, 'AUTO', NULL, 1, 1, NULL, 0),
                    ('local-1', '我的 Skill', '说明', 'USER_CREATED', NULL, NULL, 'h3', 0, 'AUTO', NULL, 1, 1, NULL, 0)
                    """.trimIndent(),
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 24,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(23, oldVersion)
                assertEquals(24, newVersion)
                AppDatabase.MIGRATION_23_24.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        db.query("SELECT packageId, packageName, enabled FROM skill_installations WHERE skillId = 'remote-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("remote:dietrichgebert/ponytail", it.getString(0))
            assertEquals("dietrichgebert/ponytail", it.getString(1))
            assertEquals(0, it.getInt(2))
        }
        db.query("SELECT COUNT(DISTINCT packageId) FROM skill_installations").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
        db.query("SELECT packageId, enabled FROM skill_installations WHERE skillId = 'local-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("local-1", it.getString(0))
            assertEquals(0, it.getInt(1))
        }
        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 24 to 25 splits oversized agent snapshots without data loss`() {
        val largeSnapshot = "{\"payload\":\"${"大".repeat(900_000)}\"}"
        val createHelper = openHelper(
            version = 24,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE agent_runs (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        userMessageId TEXT NOT NULL,
                        visibleAssistantMessageId TEXT NOT NULL,
                        configIdSnapshot TEXT,
                        requestSnapshotJson TEXT,
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
                    INSERT INTO agent_runs (
                        id, sessionId, userMessageId, visibleAssistantMessageId, configIdSnapshot,
                        requestSnapshotJson, status, currentRequestOrdinal, terminalReason, createdAt, updatedAt
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "run-large",
                        "session-1",
                        "user-1",
                        "assistant-1",
                        "config-1",
                        largeSnapshot,
                        "COMPLETED",
                        42,
                        null,
                        1L,
                        2L,
                    ),
                )
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 25,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(24, oldVersion)
                assertEquals(25, newVersion)
                AppDatabase.MIGRATION_24_25.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase

        db.query("SELECT status, currentRequestOrdinal, requestSnapshotJson FROM agent_runs WHERE id = 'run-large'").use {
            assertTrue(it.moveToFirst())
            assertEquals("COMPLETED", it.getString(0))
            assertEquals(42, it.getInt(1))
            assertTrue(it.isNull(2))
        }
        db.query(
            "SELECT COUNT(*), MAX(length(payload)) FROM agent_run_snapshot_chunks WHERE runId = 'run-large'",
        ).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getInt(0) > 1)
            assertTrue(it.getInt(1) <= 65_536)
        }
        val restored = StringBuilder(largeSnapshot.length)
        db.query(
            "SELECT payload FROM agent_run_snapshot_chunks WHERE runId = 'run-large' ORDER BY chunkIndex ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) restored.append(cursor.getString(0))
        }
        assertEquals(largeSnapshot, restored.toString())

        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 25 to 26 adds incremental message fingerprint and chat indexes`() {
        val createHelper = openHelper(
            version = 25,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        creationTimestamp INTEGER NOT NULL,
                        lastModifiedTimestamp INTEGER NOT NULL,
                        isImageGeneration INTEGER NOT NULL,
                        title TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_messages_sessionId ON messages(sessionId)")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 26,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(25, oldVersion)
                assertEquals(26, newVersion)
                AppDatabase.MIGRATION_25_26.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase

        db.query("PRAGMA table_info(messages)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "storageFingerprint") {
                    found = true
                    assertEquals("''", cursor.getString(defaultIndex))
                }
            }
            assertTrue(found)
        }
        val indexes = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            while (cursor.moveToNext()) indexes += cursor.getString(0)
        }
        assertTrue("index_messages_sessionId_timestamp" in indexes)
        assertTrue("index_chat_sessions_isImageGeneration_lastModifiedTimestamp" in indexes)

        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 26 to 27 adds recovery indexes without changing data`() {
        val createHelper = openHelper(
            version = 26,
            onCreate = { db ->
                db.execSQL(
                    "CREATE TABLE agent_runs (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, " +
                        "status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX index_agent_runs_sessionId ON agent_runs(sessionId)")
                db.execSQL("CREATE INDEX index_agent_runs_status ON agent_runs(status)")
                db.execSQL(
                    "CREATE TABLE computer_workspaces (id TEXT NOT NULL PRIMARY KEY, computerId TEXT NOT NULL, " +
                        "conversationId TEXT NOT NULL, lastUsedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX index_computer_workspaces_computerId ON computer_workspaces(computerId)")
                db.execSQL(
                    "CREATE TABLE computer_executions (id TEXT NOT NULL PRIMARY KEY, toolName TEXT NOT NULL, " +
                        "remoteStatus TEXT, status TEXT NOT NULL, finishedAt INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE computer_previews (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, " +
                        "visibility TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER)",
                )
                db.execSQL("CREATE INDEX index_computer_previews_workspaceId ON computer_previews(workspaceId)")
                db.execSQL("INSERT INTO agent_runs VALUES ('run-1', 'chat-1', 'WAITING_APPROVAL', 1, 2)")
            },
        )
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 27,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(26, oldVersion)
                assertEquals(27, newVersion)
                AppDatabase.MIGRATION_26_27.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        val indexes = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            while (cursor.moveToNext()) indexes += cursor.getString(0)
        }

        assertFalse("index_agent_runs_sessionId" in indexes)
        assertFalse("index_agent_runs_status" in indexes)
        assertFalse("index_computer_workspaces_computerId" in indexes)
        assertFalse("index_computer_previews_workspaceId" in indexes)
        listOf(
            "index_agent_runs_sessionId_createdAt",
            "index_agent_runs_status_updatedAt",
            "index_computer_workspaces_conversationId",
            "index_computer_workspaces_computerId_lastUsedAt",
            "index_computer_executions_toolName_remoteStatus_status",
            "index_computer_executions_status_finishedAt",
            "index_computer_previews_visibility_status_expiresAt",
            "index_computer_previews_workspaceId_createdAt",
        ).forEach { index -> assertTrue("缺少索引：$index", index in indexes) }
        val queryPlans = listOf(
            "SELECT * FROM agent_runs WHERE status = 'WAITING_APPROVAL' ORDER BY updatedAt DESC" to
                "index_agent_runs_status_updatedAt",
            "SELECT * FROM computer_workspaces WHERE conversationId = 'chat-1'" to
                "index_computer_workspaces_conversationId",
            "SELECT * FROM computer_workspaces WHERE computerId = 'computer-1' ORDER BY lastUsedAt DESC" to
                "index_computer_workspaces_computerId_lastUsedAt",
            "SELECT * FROM computer_executions WHERE status = 'UNKNOWN' ORDER BY finishedAt ASC" to
                "index_computer_executions_status_finishedAt",
            "SELECT * FROM computer_previews WHERE visibility = 'PUBLIC' AND status = 'ACTIVE' " +
                "AND expiresAt <= 3" to "index_computer_previews_visibility_status_expiresAt",
            "SELECT * FROM computer_previews WHERE workspaceId = 'workspace-1' ORDER BY createdAt DESC" to
                "index_computer_previews_workspaceId_createdAt",
        )
        queryPlans.forEach { (query, expectedIndex) ->
            val plan = db.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                buildString {
                    while (cursor.moveToNext()) append(cursor.getString(3))
                }
            }
            assertTrue("查询没有使用索引 $expectedIndex：$plan", expectedIndex in plan)
        }
        db.query("SELECT COUNT(*) FROM agent_runs WHERE id = 'run-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 27 to 28 removes obsolete recovery snapshots and compacts database`() {
        val largeSnapshot = "x".repeat(4_000_000)
        val createHelper = openHelper(
            version = 27,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE agent_runs (
                        id TEXT NOT NULL PRIMARY KEY,
                        visibleAssistantMessageId TEXT NOT NULL,
                        requestSnapshotJson TEXT,
                        status TEXT NOT NULL,
                        terminalReason TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, isError INTEGER NOT NULL, executionFinishedAt INTEGER)",
                )
                db.execSQL(
                    """
                    CREATE TABLE agent_run_snapshot_chunks (
                        runId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        PRIMARY KEY(runId, chunkIndex)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE TABLE system_settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO messages VALUES ('message-terminal', 0, 2)")
                db.execSQL("INSERT INTO messages VALUES ('message-active', 0, NULL)")
                db.execSQL(
                    "INSERT INTO agent_runs VALUES ('run-terminal', 'message-terminal', NULL, 'COMPLETED', NULL)",
                )
                db.execSQL(
                    "INSERT INTO agent_runs VALUES ('run-active', 'message-active', NULL, 'STREAMING_MODEL', NULL)",
                )
                db.execSQL(
                    "INSERT INTO agent_run_snapshot_chunks VALUES ('run-terminal', 0, ?)",
                    arrayOf<Any>(largeSnapshot),
                )
                db.execSQL(
                    "INSERT INTO agent_run_snapshot_chunks VALUES ('run-active', 0, ?)",
                    arrayOf<Any>(largeSnapshot),
                )
            },
        )
        createHelper.writableDatabase.apply {
            // 改变 auto_vacuum 必须配合 VACUUM，模拟真机旧数据库没有自动回收的状态。
            execSQL("PRAGMA auto_vacuum = NONE")
            execSQL("VACUUM")
            close()
        }
        createHelper.close()

        val migrateHelper = openHelper(
            version = 28,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(27, oldVersion)
                assertEquals(28, newVersion)
                AppDatabase.MIGRATION_27_28.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        val beforeCompaction = context.getDatabasePath(TEST_DB).length()

        db.query("SELECT runId FROM agent_run_snapshot_chunks ORDER BY runId").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("run-active", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        AppDatabase.compactIfPending(db)
        val afterCompaction = context.getDatabasePath(TEST_DB).length()

        assertTrue("压缩前=$beforeCompaction 压缩后=$afterCompaction", afterCompaction < beforeCompaction)
        db.query("SELECT COUNT(*) FROM system_settings WHERE `key` = 'database_compaction_v28_pending'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        db.close()
        migrateHelper.close()
    }

    @Test
    fun `migration 28 to 29 creates persistent pending queue`() {
        val createHelper = openHelper(version = 28)
        createHelper.writableDatabase.close()
        createHelper.close()

        val migrateHelper = openHelper(
            version = 29,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(28, oldVersion)
                assertEquals(29, newVersion)
                AppDatabase.MIGRATION_28_29.migrate(db)
            },
        )
        val db = migrateHelper.writableDatabase
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(pending_messages)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        assertEquals(
            listOf(
                "id", "conversationId", "content", "composerText", "contentParts", "attachments",
                "createdAt", "updatedAt", "status", "queuePosition",
            ),
            columns,
        )
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' " +
                "AND name='index_pending_messages_conversationId_status_queuePosition'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }

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
