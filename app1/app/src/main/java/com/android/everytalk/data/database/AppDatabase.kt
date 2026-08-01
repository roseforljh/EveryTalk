package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.everytalk.data.database.daos.ApiConfigDao
import com.android.everytalk.data.database.daos.ChatDao
import com.android.everytalk.data.database.daos.McpConfigDao
import com.android.everytalk.data.database.daos.SettingsDao
import com.android.everytalk.data.database.daos.VoiceConfigDao
import com.android.everytalk.data.database.entities.ApiConfigEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ExpandedGroupEntity
import com.android.everytalk.data.database.entities.McpServerConfigEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.PinnedItemEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.VoiceBackendConfigEntity

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
        McpServerConfigEntity::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun voiceConfigDao(): VoiceConfigDao
    abstract fun chatDao(): ChatDao
    abstract fun settingsDao(): SettingsDao
    abstract fun mcpConfigDao(): McpConfigDao

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
                )
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
    }
}
