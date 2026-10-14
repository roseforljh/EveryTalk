package com.android.everytalk.data.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.android.everytalk.data.database.entities.UserSettingKind
import com.android.everytalk.data.database.entities.localSyncUuid
import org.json.JSONArray

/**
 * 42→43 的无损迁移。
 *
 * 迁移只增加列、索引和同步投影，不重建原表，因此正文、时间、外键关系和本地配置都保留。
 * 旧记录统一标记为待同步；是否真的上传由 LocalOnly/SignedIn 状态和后续同步调度共同决定。
 */
internal class SyncMetadataMigration : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addSessionColumns(db)
        addMessageColumns(db)
        addMemoryColumns(db)
        createDraftTable(db)
        createUserSettingsTable(db)
        fillSessionMetadata(db)
        fillMessageMetadata(db)
        fillMemoryMetadata(db)
        fillUserSettingProjection(db)
    }

    private fun addSessionColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
    }

    private fun addMessageColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
        db.execSQL("ALTER TABLE messages ADD COLUMN ever_synced INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN is_incomplete INTEGER NOT NULL DEFAULT 0")
    }

    private fun addMemoryColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memories ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
    }

    private fun createDraftTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE message_drafts (
                id TEXT NOT NULL PRIMARY KEY,
                sessionId TEXT NOT NULL,
                text TEXT NOT NULL,
                contentParts TEXT NOT NULL,
                sender TEXT NOT NULL,
                reasoning TEXT,
                contentStarted INTEGER NOT NULL,
                isError INTEGER NOT NULL,
                name TEXT,
                timestamp INTEGER NOT NULL,
                isPlaceholderName INTEGER NOT NULL,
                webSearchResults TEXT,
                currentWebSearchStage TEXT,
                imageUrls TEXT,
                attachments TEXT NOT NULL,
                outputType TEXT NOT NULL,
                parts TEXT NOT NULL,
                executionStatus TEXT,
                executionSteps TEXT NOT NULL,
                executionTrace TEXT NOT NULL,
                executionFinishedAt INTEGER,
                enabledToolIds TEXT NOT NULL,
                computerIdSnapshot TEXT,
                workspaceIdSnapshot TEXT,
                modelName TEXT,
                providerName TEXT,
                tokenUsage TEXT,
                contextUsageSnapshot TEXT,
                contextCompressionState TEXT,
                storageFingerprint TEXT NOT NULL,
                uuid TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_status TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                ever_synced INTEGER NOT NULL DEFAULT 0,
                is_incomplete INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_message_drafts_sessionId_timestamp ON message_drafts(sessionId, timestamp)")
    }

    private fun createUserSettingsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE user_settings (kind TEXT NOT NULL, `key` TEXT NOT NULL, value TEXT NOT NULL, uuid TEXT NOT NULL, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, sync_status TEXT NOT NULL DEFAULT 'PENDING', PRIMARY KEY(kind, `key`))",
        )
        db.execSQL("CREATE UNIQUE INDEX index_user_settings_uuid ON user_settings(uuid)")
    }

    private fun fillSessionMetadata(db: SupportSQLiteDatabase) {
        db.query("SELECT id, creationTimestamp, lastModifiedTimestamp, isImageGeneration FROM chat_sessions").useRows { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val lastModified = cursor.getLong(2)
                val image = cursor.getInt(3) != 0
                val syncable = !image && id != "last_open_chat_v1" && id != "last_open_image_generation_v1"
                db.execSQL(
                    "UPDATE chat_sessions SET uuid = ?, updated_at = ?, sync_status = ? WHERE id = ?",
                    arrayOf(localSyncUuid("chat_sessions", id), lastModified, if (syncable) "PENDING" else "LOCAL_ONLY", id),
                )
            }
        }
        db.execSQL("CREATE UNIQUE INDEX index_chat_sessions_uuid ON chat_sessions(uuid)")
    }

    private fun fillMessageMetadata(db: SupportSQLiteDatabase) {
        // 先用一次 SQL 按会话标记状态，再逐行只补 UUID；避免每条消息再查一次会话表。
        db.execSQL("UPDATE messages SET sync_status = CASE WHEN sessionId IN (SELECT id FROM chat_sessions WHERE isImageGeneration = 0 AND id NOT LIKE 'last_open_%') THEN 'PENDING' ELSE 'LOCAL_ONLY' END")
        db.query("SELECT id, sessionId, timestamp FROM messages").useRows { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val timestamp = cursor.getLong(2)
                db.execSQL(
                    "UPDATE messages SET uuid = ?, updated_at = ? WHERE id = ?",
                    arrayOf<Any?>(localSyncUuid("messages", id), timestamp, id),
                )
            }
        }
        db.execSQL("CREATE UNIQUE INDEX index_messages_uuid ON messages(uuid)")
    }

    private fun fillMemoryMetadata(db: SupportSQLiteDatabase) {
        db.query("SELECT id, updatedAt FROM memories").useRows { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val updatedAt = cursor.getLong(1)
                db.execSQL(
                    "UPDATE memories SET uuid = ?, updated_at = ?, sync_status = 'PENDING' WHERE id = ?",
                    arrayOf(localSyncUuid("memories", id), updatedAt, id),
                )
            }
        }
        db.execSQL("CREATE UNIQUE INDEX index_memories_uuid ON memories(uuid)")
    }

    private fun fillUserSettingProjection(db: SupportSQLiteDatabase) {
        val textSessions = linkedMapOf<String, String>()
        db.query("SELECT id, uuid FROM chat_sessions WHERE isImageGeneration = 0 AND deleted = 0 AND id NOT LIKE 'last_open_%'").useRows { cursor ->
            while (cursor.moveToNext()) textSessions[cursor.getString(0)] = cursor.getString(1)
        }
        db.query("SELECT groupName, conversationIds FROM conversation_groups").useRows { cursor ->
            while (cursor.moveToNext()) {
                val ids = jsonIds(cursor.getString(1)).mapNotNull(textSessions::get).distinct()
                val groupName = cursor.getString(0)
                insertSetting(db, UserSettingKind.CONVERSATION_GROUPS, groupName, ids)
            }
        }
        db.query("SELECT id FROM pinned_items WHERE isImageGeneration = 0").useRows { cursor ->
            val ids = buildList { while (cursor.moveToNext()) textSessions[cursor.getString(0)]?.let(::add) }.distinct()
            if (ids.isNotEmpty()) insertSetting(db, UserSettingKind.PINNED_ITEMS, UserSettingKind.PINNED_KEY, ids)
        }
    }

    private fun insertSetting(db: SupportSQLiteDatabase, kind: String, key: String, ids: List<String>) {
        val value = JSONArray(ids).toString()
        db.execSQL(
            "INSERT INTO user_settings(kind, `key`, value, uuid, updated_at, deleted, sync_status) VALUES(?, ?, ?, ?, ?, 0, 'PENDING')",
            arrayOf<Any?>(kind, key, value, localSyncUuid("user_settings", "$kind:$key"), System.currentTimeMillis()),
        )
    }

    private fun jsonIds(raw: String): List<String> = runCatching {
        JSONArray(raw).let { array -> List(array.length()) { index -> array.getString(index) } }
    }.getOrDefault(emptyList())

    private inline fun <T> Cursor.useRows(block: (Cursor) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}
