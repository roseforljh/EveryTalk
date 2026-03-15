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
import com.android.everytalk.data.database.entities.ConversationParamsEntity
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
        ConversationParamsEntity::class,
        McpServerConfigEntity::class
    ],
    version = 6,
    exportSchema = false
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
                db.execSQL("ALTER TABLE api_configs ADD COLUMN openClawAccessMode TEXT NOT NULL DEFAULT 'bridge'")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN openClawBridgeUrl TEXT")
                db.execSQL("ALTER TABLE api_configs ADD COLUMN openClawSessionId TEXT")
                db.execSQL("UPDATE api_configs SET openClawSessionId = NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_configs ADD COLUMN openClawSessionId TEXT")
                db.execSQL("UPDATE api_configs SET openClawSessionId = NULL")
            }
        }
    }
}