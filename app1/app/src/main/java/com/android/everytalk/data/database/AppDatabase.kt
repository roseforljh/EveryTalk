package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.everytalk.data.database.dao.ConversationDao
import com.android.everytalk.data.database.dao.ConversationGroupDao
import com.android.everytalk.data.database.dao.MessageDao
import com.android.everytalk.data.database.dao.UserPreferenceDao
import com.android.everytalk.data.database.entity.ConversationEntity
import com.android.everytalk.data.database.entity.ConversationGroupEntity
import com.android.everytalk.data.database.entity.MessageEntity
import com.android.everytalk.data.database.entity.UserPreferenceEntity

/**
 * Room 数据库主类
 *
 * 用于管理聊天历史数据的持久化存储，解决 SharedPreferences 大小限制问题
 *
 * 版本历史：
 * - v1: 初始版本，包含 conversations 和 messages 表
 * - v2: 添加 conversation_groups 和 user_preferences 表
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ConversationGroupEntity::class,
        UserPreferenceEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationGroupDao(): ConversationGroupDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    
    companion object {
        private const val DATABASE_NAME = "everytalk_database"
        
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * 获取数据库单例实例
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        /**
         * 数据库迁移：v1 -> v2
         * 添加 conversation_groups 和 user_preferences 表
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 创建 conversation_groups 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conversation_groups (
                        groupName TEXT NOT NULL PRIMARY KEY,
                        conversationIdsJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()},
                        updatedAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                    )
                """.trimIndent())
                
                // 创建 user_preferences 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_preferences (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                    )
                """.trimIndent())
            }
        }
        
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                // 添加数据库迁移
                .addMigrations(MIGRATION_1_2)
                // 数据库版本升级时的回退策略（仅在迁移失败时使用）
                .fallbackToDestructiveMigration(false)
                .build()
        }
        
        /**
         * 关闭数据库连接（用于测试或清理）
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}