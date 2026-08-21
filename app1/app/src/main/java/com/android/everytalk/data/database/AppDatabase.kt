package com.android.everytalk.data.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.everytalk.data.database.daos.ApiConfigDao
import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.daos.ChatDao
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.daos.McpConfigDao
import com.android.everytalk.data.database.daos.SettingsDao
import com.android.everytalk.data.database.daos.SkillDao
import com.android.everytalk.data.database.daos.VoiceConfigDao
import com.android.everytalk.data.database.entities.ApiConfigEntity
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.AgentEntryEntity
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRequestUsageEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.AgentRunSnapshotChunkEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ComputerAuditEventEntity
import com.android.everytalk.data.database.entities.ComputerEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.ComputerPreviewEntity
import com.android.everytalk.data.database.entities.ComputerWorkspaceEntity
import com.android.everytalk.data.database.entities.ConversationComputerSelectionEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.McpServerConfigEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.database.entities.SkillVersionEntity
import com.android.everytalk.data.database.entities.VoiceBackendConfigEntity
import com.android.everytalk.data.database.entities.WorkspaceSecretMetadataEntity

@Database(
    entities = [
        ApiConfigEntity::class,
        VoiceBackendConfigEntity::class,
        ChatSessionEntity::class,
        MessageEntity::class,
        SystemSettingEntity::class,
        PinnedItemEntity::class,
        ConversationGroupEntity::class,
        ExpandedGroupEntity::class,
        McpServerConfigEntity::class,
        ComputerEntity::class,
        ComputerWorkspaceEntity::class,
        ConversationComputerSelectionEntity::class,
        ComputerExecutionEntity::class,
        ComputerPreviewEntity::class,
        WorkspaceSecretMetadataEntity::class,
        ComputerAuditEventEntity::class,
        AgentRunEntity::class,
        AgentRunSnapshotChunkEntity::class,
        AgentEntryEntity::class,
        AgentRequestEntity::class,
        AgentRequestUsageEntity::class,
        AgentContextSnapshotEntity::class,
        AgentCompactionEntryEntity::class,
        ProviderContinuationStateEntity::class,
        SkillInstallationEntity::class,
        SkillVersionEntity::class,
    ],
    version = 28,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun voiceConfigDao(): VoiceConfigDao
    abstract fun chatDao(): ChatDao
    abstract fun settingsDao(): SettingsDao
    abstract fun mcpConfigDao(): McpConfigDao
    abstract fun computerDao(): ComputerDao
    abstract fun agentDao(): AgentDao
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "eztalk_room_database"
                )
                // 数据库本体使用 Android Room + SQLite。
                // 当前没有接入 SQLCipher/SupportFactory，所以不是整库加密；敏感密钥的保护在上层字段处理逻辑中完成。
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                )
                .addCallback(DATABASE_MAINTENANCE_CALLBACK)
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加版本 1 到 2 的迁移逻辑
                // 如果没有具体变更，可以是空实现
            }
        }
        
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add useRealtimeStreaming column to voice_backend_configs table
                // SQLite doesn't support BOOLEAN type directly, uses INTEGER (0/1)
                db.execSQL("ALTER TABLE voice_backend_configs ADD COLUMN useRealtimeStreaming INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create MCP server configs table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mcp_server_configs (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        transportType TEXT NOT NULL DEFAULT 'SSE',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        headers TEXT NOT NULL DEFAULT '{}'
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 该版本不再需要结构变更。
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 该版本不需要结构变更。
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS api_configs_new (
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
                db.execSQL(
                    """
                    INSERT INTO api_configs_new (
                        id, address, key, model, provider, name, channel, isValid,
                        modalityType, temperature, topP, maxTokens, defaultUseWebSearch,
                        imageSize, numInferenceSteps, guidanceScale, toolsJson,
                        enableCodeExecution, isImageGenConfig
                    )
                    SELECT
                        id, address, key, model, provider, name, channel, isValid,
                        modalityType, temperature, topP, maxTokens, defaultUseWebSearch,
                        imageSize, numInferenceSteps, guidanceScale, toolsJson,
                        enableCodeExecution, isImageGenConfig
                    FROM api_configs
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE api_configs")
                db.execSQL("ALTER TABLE api_configs_new RENAME TO api_configs")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_configs ADD COLUMN modelParameters TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("DROP TABLE IF EXISTS conversation_params")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN enabledToolIds TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tokenUsage TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN contextUsageSnapshot TEXT")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN executionSteps TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contextCompressionState TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN computerIdSnapshot TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN workspaceIdSnapshot TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS computers (
                        id TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        resolvedAddress TEXT,
                        hostKeyAlgorithm TEXT,
                        hostKeyBlobBase64 TEXT,
                        hostKeyFingerprint TEXT,
                        authKind TEXT NOT NULL,
                        credentialState TEXT NOT NULL,
                        runMode TEXT NOT NULL,
                        status TEXT NOT NULL,
                        capabilitiesJson TEXT,
                        bootstrapVersion TEXT,
                        sandboxImage TEXT,
                        allowPrivateNetwork INTEGER NOT NULL,
                        lastConnectedAt INTEGER,
                        lastErrorCode TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computers_status ON computers(status)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS computer_workspaces (
                        id TEXT NOT NULL PRIMARY KEY,
                        computerId TEXT NOT NULL,
                        conversationId TEXT NOT NULL,
                        runMode TEXT NOT NULL,
                        hostPath TEXT NOT NULL,
                        containerName TEXT,
                        containerImage TEXT,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        FOREIGN KEY(computerId) REFERENCES computers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computer_workspaces_computerId ON computer_workspaces(computerId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_computer_workspaces_computerId_conversationId ON computer_workspaces(computerId, conversationId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_computer_selections (
                        conversationId TEXT NOT NULL PRIMARY KEY,
                        selectedComputerId TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(selectedComputerId) REFERENCES computers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_computer_selections_selectedComputerId ON conversation_computer_selections(selectedComputerId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS computer_executions (
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
                        FOREIGN KEY(computerId) REFERENCES computers(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(workspaceId) REFERENCES computer_workspaces(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computer_executions_computerId ON computer_executions(computerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computer_executions_workspaceId ON computer_executions(workspaceId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_computer_executions_toolCallId ON computer_executions(toolCallId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS computer_previews (
                        id TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        remotePort INTEGER NOT NULL,
                        localPort INTEGER,
                        publicPort INTEGER,
                        protocol TEXT NOT NULL,
                        visibility TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER,
                        FOREIGN KEY(workspaceId) REFERENCES computer_workspaces(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computer_previews_workspaceId ON computer_previews(workspaceId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspace_secret_metadata (
                        id TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(workspaceId) REFERENCES computer_workspaces(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_secret_metadata_workspaceId ON workspace_secret_metadata(workspaceId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workspace_secret_metadata_workspaceId_name ON workspace_secret_metadata(workspaceId, name)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS computer_audit_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        computerId TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        safeSummary TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(computerId) REFERENCES computers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computer_audit_events_computerId_createdAt ON computer_audit_events(computerId, createdAt)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN executionTrace TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE computer_previews ADD COLUMN target TEXT NOT NULL DEFAULT 'CONTAINER'")
                // 数据库升级先于旧 Direct 记录迁移，因此此处仍能准确识别已有 Host Preview。
                db.execSQL(
                    """
                    UPDATE computer_previews
                    SET target = 'HOST'
                    WHERE workspaceId IN (
                        SELECT id FROM computer_workspaces WHERE runMode = 'DIRECT'
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE computers ADD COLUMN permissionMode TEXT NOT NULL DEFAULT 'MANUAL'",
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_runs (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        userMessageId TEXT NOT NULL,
                        visibleAssistantMessageId TEXT NOT NULL,
                        configIdSnapshot TEXT,
                        status TEXT NOT NULL,
                        currentRequestOrdinal INTEGER NOT NULL,
                        terminalReason TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_runs_sessionId ON agent_runs(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_runs_visibleAssistantMessageId ON agent_runs(visibleAssistantMessageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_runs_status ON agent_runs(status)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        runId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        requestId TEXT,
                        toolCallId TEXT,
                        payloadJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        finalizedAt INTEGER,
                        FOREIGN KEY(runId) REFERENCES agent_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_entries_runId_sequence ON agent_entries(runId, sequence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_entries_requestId ON agent_entries(requestId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_entries_toolCallId ON agent_entries(toolCallId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_requests (
                        id TEXT NOT NULL PRIMARY KEY,
                        runId TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        purpose TEXT NOT NULL,
                        modelTurnOrdinal INTEGER,
                        attempt INTEGER NOT NULL,
                        retryOfRequestId TEXT,
                        provider TEXT NOT NULL,
                        endpoint TEXT,
                        model TEXT NOT NULL,
                        payloadFingerprint TEXT NOT NULL,
                        status TEXT NOT NULL,
                        finishReason TEXT,
                        startedAt INTEGER,
                        firstEventAt INTEGER,
                        finishedAt INTEGER,
                        FOREIGN KEY(runId) REFERENCES agent_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_requests_runId_ordinal ON agent_requests(runId, ordinal)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_requests_runId_status ON agent_requests(runId, status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_requests_retryOfRequestId ON agent_requests(retryOfRequestId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_request_usage (
                        requestId TEXT NOT NULL PRIMARY KEY,
                        promptTokens INTEGER,
                        freshInputTokens INTEGER,
                        cacheReadTokens INTEGER,
                        cacheWriteTokens INTEGER,
                        outputTokens INTEGER,
                        reasoningTokens INTEGER,
                        requestTotalTokens INTEGER,
                        providerTotalTokens INTEGER,
                        source TEXT NOT NULL,
                        quality TEXT NOT NULL,
                        rawUsageJson TEXT,
                        FOREIGN KEY(requestId) REFERENCES agent_requests(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_context_snapshots (
                        requestId TEXT NOT NULL PRIMARY KEY,
                        systemPromptTokens INTEGER NOT NULL,
                        conversationTextTokens INTEGER NOT NULL,
                        mediaTokens INTEGER NOT NULL,
                        toolSchemaTokens INTEGER NOT NULL,
                        protocolOverheadTokens INTEGER NOT NULL,
                        estimatedPromptTokens INTEGER NOT NULL,
                        reservedOutputTokens INTEGER NOT NULL,
                        contextWindowTokens INTEGER NOT NULL,
                        activeContextTokens INTEGER NOT NULL,
                        calibrationTokens INTEGER NOT NULL,
                        compactionId TEXT,
                        transcriptFingerprint TEXT NOT NULL,
                        source TEXT NOT NULL,
                        FOREIGN KEY(requestId) REFERENCES agent_requests(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_context_snapshots_compactionId ON agent_context_snapshots(compactionId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_compactions (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        configIdSnapshot TEXT,
                        summary TEXT NOT NULL,
                        summarizedThroughItemId TEXT NOT NULL,
                        prefixFingerprint TEXT NOT NULL,
                        retainedTailJson TEXT NOT NULL,
                        tokensBefore INTEGER NOT NULL,
                        estimatedTokensAfter INTEGER NOT NULL,
                        summaryRequestId TEXT,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_compactions_sessionId_createdAt ON agent_compactions(sessionId, createdAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_continuation_states (
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
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_provider_continuation_states_sessionId_configId_provider_endpoint_model " +
                        "ON provider_continuation_states(sessionId, configId, provider, endpoint, model)",
                )
            }
        }

        /** 增加不含 API Key 的 Run 恢复快照，旧聊天和 Agent 事实原样保留。 */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_runs ADD COLUMN requestSnapshotJson TEXT")
                db.execSQL("ALTER TABLE provider_continuation_states ADD COLUMN protocol TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_provider_continuation_states_sessionId_configId_provider_endpoint_model")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_provider_continuation_states_sessionId_configId_protocol_provider_endpoint_model " +
                        "ON provider_continuation_states(sessionId, configId, protocol, provider, endpoint, model)",
                )
            }
        }

        /**
         * 为 ComputerExecution 增加远端执行事实。
         * 所有列都允许为空，旧执行记录保持原有 Tool 状态，不在迁移阶段连接 VPS 或扫描旧目录。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN target TEXT")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN completionMode TEXT")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN remoteProcessId TEXT")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN remoteStatePath TEXT")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN remoteStatus TEXT")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN remoteExitCode INTEGER")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN lastObservedAt INTEGER")
            }
        }

        /**
         * 为 ComputerExecution 补齐后台持续执行、Run 关联与结果对账消费字段。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN runId TEXT")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN stdoutCursor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN stderrCursor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN lastEventAt INTEGER")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN cancelRequestedAt INTEGER")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN cancelCompletedAt INTEGER")
                db.execSQL("ALTER TABLE computer_executions ADD COLUMN resultAttachedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_computer_executions_runId ON computer_executions(runId)")
            }
        }

        /** 保存执行过程结束时间，让历史消息重进会话后仍能显示准确耗时。 */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN executionFinishedAt INTEGER")
            }
        }

        /** 新增动态 Skill 安装和不可变版本表。 */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contentParts TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skill_installations (
                        skillId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceRepository TEXT,
                        sourcePath TEXT,
                        currentHash TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        invocationMode TEXT NOT NULL,
                        updateHash TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastUsedAt INTEGER,
                        useCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_installations_enabled ON skill_installations(enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_installations_currentHash ON skill_installations(currentHash)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skill_versions (
                        skillId TEXT NOT NULL,
                        contentHash TEXT NOT NULL,
                        versionLabel TEXT,
                        rootPath TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        frontmatterJson TEXT NOT NULL,
                        installedAt INTEGER NOT NULL,
                        PRIMARY KEY(skillId, contentHash),
                        FOREIGN KEY(skillId) REFERENCES skill_installations(skillId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_versions_skillId ON skill_versions(skillId)")
            }
        }

        /** 删除 Skill 安装审计字段，原有安装、版本和用户启停状态全部保留。 */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 先把子表数据复制到无外键备份表。这样重建父表时不会留下指向临时表名的外键。
                db.execSQL(
                    """
                    CREATE TABLE skill_versions_backup (
                        skillId TEXT NOT NULL,
                        contentHash TEXT NOT NULL,
                        versionLabel TEXT,
                        rootPath TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        frontmatterJson TEXT NOT NULL,
                        installedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO skill_versions_backup (
                        skillId, contentHash, versionLabel, rootPath, manifestJson, frontmatterJson, installedAt
                    )
                    SELECT
                        skillId, contentHash, versionLabel, rootPath, manifestJson, frontmatterJson, installedAt
                    FROM skill_versions
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE skill_versions")
                db.execSQL(
                    """
                    CREATE TABLE skill_installations_new (
                        skillId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceRepository TEXT,
                        sourcePath TEXT,
                        currentHash TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        invocationMode TEXT NOT NULL,
                        updateHash TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastUsedAt INTEGER,
                        useCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO skill_installations_new (
                        skillId, name, description, sourceType, sourceRepository, sourcePath,
                        currentHash, enabled, invocationMode, updateHash, createdAt, updatedAt,
                        lastUsedAt, useCount
                    )
                    SELECT
                        skillId, name, description, sourceType, sourceRepository, sourcePath,
                        currentHash, enabled, invocationMode, updateHash, createdAt, updatedAt,
                        lastUsedAt, useCount
                    FROM skill_installations
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE skill_installations")
                db.execSQL("ALTER TABLE skill_installations_new RENAME TO skill_installations")

                // 父表已使用最终名称后再创建子表，Room 读取到的外键目标才能稳定为 skill_installations。
                db.execSQL(
                    """
                    CREATE TABLE skill_versions (
                        skillId TEXT NOT NULL,
                        contentHash TEXT NOT NULL,
                        versionLabel TEXT,
                        rootPath TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        frontmatterJson TEXT NOT NULL,
                        installedAt INTEGER NOT NULL,
                        PRIMARY KEY(skillId, contentHash),
                        FOREIGN KEY(skillId) REFERENCES skill_installations(skillId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO skill_versions (
                        skillId, contentHash, versionLabel, rootPath, manifestJson, frontmatterJson, installedAt
                    )
                    SELECT
                        skillId, contentHash, versionLabel, rootPath, manifestJson, frontmatterJson, installedAt
                    FROM skill_versions_backup
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE skill_versions_backup")
                db.execSQL("CREATE INDEX index_skill_installations_enabled ON skill_installations(enabled)")
                db.execSQL("CREATE INDEX index_skill_installations_currentHash ON skill_installations(currentHash)")
                db.execSQL("CREATE INDEX index_skill_versions_skillId ON skill_versions(skillId)")
            }
        }

        /** 把已有单项安装归入稳定包；远端同仓库条目自动合并，本地条目各自成包。 */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skill_installations ADD COLUMN packageId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE skill_installations ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE skill_installations
                    SET packageId = CASE
                            WHEN sourceType = 'REMOTE' AND sourceRepository IS NOT NULL
                                THEN 'remote:' || replace(sourceRepository, 'https://github.com/', '')
                            ELSE skillId
                        END,
                        packageName = CASE
                            WHEN sourceType = 'REMOTE' AND sourceRepository IS NOT NULL
                                THEN replace(sourceRepository, 'https://github.com/', '')
                            ELSE name
                        END
                    """.trimIndent(),
                )
                // 旧版本允许同仓库子 Skill 分别启停；迁移后取最保守状态并统一整包。
                db.execSQL(
                    """
                    UPDATE skill_installations
                    SET enabled = (
                        SELECT MIN(sibling.enabled)
                        FROM skill_installations AS sibling
                        WHERE sibling.packageId = skill_installations.packageId
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_skill_installations_packageId ON skill_installations(packageId)")
            }
        }

        /**
         * 把 Agent 恢复快照从主表单行迁移为小块。
         *
         * substr 在 SQLite 内部执行，Android 不会先把数 MB 的旧字段读进 CursorWindow。
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_run_snapshot_chunks (
                        runId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        PRIMARY KEY(runId, chunkIndex),
                        FOREIGN KEY(runId) REFERENCES agent_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )

                val maxSnapshotLength = db.query(
                    "SELECT COALESCE(MAX(length(requestSnapshotJson)), 0) FROM agent_runs",
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
                var offset = 1L
                var chunkIndex = 0
                while (offset <= maxSnapshotLength) {
                    db.execSQL(
                        """
                        INSERT INTO agent_run_snapshot_chunks (runId, chunkIndex, payload)
                        SELECT id, $chunkIndex, substr(requestSnapshotJson, $offset, $AGENT_SNAPSHOT_CHUNK_CHARS)
                        FROM agent_runs
                        WHERE requestSnapshotJson IS NOT NULL
                          AND length(requestSnapshotJson) >= $offset
                        """.trimIndent(),
                    )
                    offset += AGENT_SNAPSHOT_CHUNK_CHARS
                    chunkIndex += 1
                }
                db.execSQL("UPDATE agent_runs SET requestSnapshotJson = NULL WHERE requestSnapshotJson IS NOT NULL")
            }
        }

        /**
         * 聊天保存改为按消息摘要增量同步，并为两个高频排序查询补联合索引。
         * 旧消息摘要留空，首次再次保存该会话时会安全地补齐。
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN storageFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_messages_sessionId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_sessionId_timestamp ON messages(sessionId, timestamp)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_sessions_isImageGeneration_lastModifiedTimestamp " +
                        "ON chat_sessions(isImageGeneration, lastModifiedTimestamp)",
                )
            }
        }

        /**
         * 为 Agent 恢复排序、会话 Workspace 反查和 Computer 活动任务扫描补联合索引。
         * 本次只创建索引，不重写表和用户数据。
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_agent_runs_sessionId")
                db.execSQL("DROP INDEX IF EXISTS index_agent_runs_status")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_runs_sessionId_createdAt " +
                        "ON agent_runs(sessionId, createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_runs_status_updatedAt " +
                        "ON agent_runs(status, updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_computer_workspaces_conversationId " +
                        "ON computer_workspaces(conversationId)",
                )
                db.execSQL("DROP INDEX IF EXISTS index_computer_workspaces_computerId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_computer_workspaces_computerId_lastUsedAt " +
                        "ON computer_workspaces(computerId, lastUsedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_computer_executions_toolName_remoteStatus_status " +
                        "ON computer_executions(toolName, remoteStatus, status)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_computer_executions_status_finishedAt " +
                        "ON computer_executions(status, finishedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_computer_previews_visibility_status_expiresAt " +
                        "ON computer_previews(visibility, status, expiresAt)",
                )
                db.execSQL("DROP INDEX IF EXISTS index_computer_previews_workspaceId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_computer_previews_workspaceId_createdAt " +
                        "ON computer_previews(workspaceId, createdAt)",
                )
            }
        }

        /**
         * 已结束的 AgentRun 不再需要请求恢复快照。
         *
         * 旧版本长期保留整份请求上下文，少量会话也能把数据库撑到上百 MB。
         * 迁移先删除无恢复价值的快照，再留下标记，由数据库打开回调在事务外执行 VACUUM。
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM agent_run_snapshot_chunks
                    WHERE runId IN (
                        SELECT run.id
                        FROM agent_runs AS run
                        LEFT JOIN messages AS message ON message.id = run.visibleAssistantMessageId
                        WHERE run.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                           OR message.id IS NULL
                           OR message.isError = 1
                           OR message.executionFinishedAt IS NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE agent_runs
                    SET requestSnapshotJson = NULL
                    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                       OR visibleAssistantMessageId NOT IN (SELECT id FROM messages)
                       OR visibleAssistantMessageId IN (
                           SELECT id FROM messages
                           WHERE isError = 1 OR executionFinishedAt IS NOT NULL
                       )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO system_settings (`key`, value) VALUES ('$DATABASE_COMPACTION_KEY', '1')",
                )
            }
        }

        /**
         * VACUUM 不能在 Room 迁移事务里执行，因此在数据库完成升级并打开后再压缩。
         * 标记只在成功后删除；空间不足或系统中断时，下次启动会自动重试。
         */
        internal fun compactIfPending(db: SupportSQLiteDatabase) {
            val pending = db.query(
                "SELECT 1 FROM system_settings WHERE `key` = '$DATABASE_COMPACTION_KEY' LIMIT 1",
            ).use { cursor -> cursor.moveToFirst() }
            if (!pending) {
                runCatching { db.execSQL("PRAGMA incremental_vacuum") }
                return
            }
            runCatching {
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor -> cursor.moveToFirst() }
                db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
                db.execSQL("VACUUM")
                db.execSQL("DELETE FROM system_settings WHERE `key` = '$DATABASE_COMPACTION_KEY'")
            }.onFailure { error ->
                Log.w("AppDatabase", "Database compaction will retry on next open", error)
            }
        }

        private val DATABASE_MAINTENANCE_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                compactIfPending(db)
            }
        }

        private const val AGENT_SNAPSHOT_CHUNK_CHARS = 65_536L
        private const val DATABASE_COMPACTION_KEY = "database_compaction_v28_pending"
    }
}
