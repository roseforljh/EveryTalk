package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.android.everytalk.data.database.entities.MessageSearchEntity
import com.android.everytalk.data.database.entities.MessageSearchMapEntity

data class IndexableMessage(val messageId: String, val text: String, val fingerprint: String, val docid: Long?)
data class StaleSearchEntry(val messageId: String, val docid: Long)
class SearchHit(val messageId: String, val info: ByteArray)
data class SearchCandidate(val messageId: String, val conversationId: String, val title: String, val sender: String, val timestamp: Long, val totalCharacters: Int)
data class SearchText(val messageId: String, val text: String)
data class TitleMatch(val conversationId: String, val title: String, val lastActivity: Long)

/** 历史全文索引的维护和查询；可检索的消息范围与原逐条扫描完全一致。 */
@Dao
interface MessageSearchDao {
    /** 新增或内容变化、尚未进入索引的正式正文。 */
    @Query("""
        SELECT m.id AS messageId, m.text AS text, m.storageFingerprint || ':' || length(m.text) AS fingerprint, x.docid AS docid
        FROM messages m LEFT JOIN message_search_map x ON x.messageId = m.id
        WHERE m.deleted = 0 AND m.isError = 0 AND m.isPlaceholderName = 0 AND m.sender IN ('User', 'AI') AND length(trim(m.text)) > 0
          AND (x.messageId IS NULL OR x.fingerprint != m.storageFingerprint || ':' || length(m.text))
        LIMIT :limit
    """)
    suspend fun pending(limit: Int): List<IndexableMessage>

    /** 消息已删除、出错或清空后，索引行要移除。 */
    @Query("""
        SELECT x.messageId AS messageId, x.docid AS docid FROM message_search_map x LEFT JOIN messages m ON m.id = x.messageId
        WHERE m.id IS NULL OR m.deleted = 1 OR m.isError = 1 OR m.isPlaceholderName = 1 OR length(trim(m.text)) = 0
        LIMIT :limit
    """)
    suspend fun stale(limit: Int): List<StaleSearchEntry>

    @Insert
    suspend fun insertBody(body: MessageSearchEntity): Long

    @Query("DELETE FROM message_search WHERE rowid = :docid")
    suspend fun deleteBody(docid: Long)

    @Upsert
    suspend fun upsertMap(entry: MessageSearchMapEntity)

    @Query("DELETE FROM message_search_map WHERE messageId = :messageId")
    suspend fun deleteMap(messageId: String)

    @Query("""
        SELECT x.messageId AS messageId, matchinfo(message_search, 'pcnalx') AS info
        FROM message_search JOIN message_search_map x ON x.docid = message_search.rowid
        WHERE message_search MATCH :match LIMIT :limit
    """)
    suspend fun match(match: String, limit: Int): List<SearchHit>

    /** 候选消息的元数据，过滤条件与原逐条扫描相同；正文只在需要展示的那一页再读。 */
    @Query("""
        SELECT m.id AS messageId, m.sessionId AS conversationId, substr(COALESCE(s.title, ''), 1, 120) AS title,
               m.sender AS sender, m.timestamp AS timestamp, length(m.text) AS totalCharacters
        FROM messages m INNER JOIN chat_sessions s ON s.id = m.sessionId
        WHERE m.id IN (:ids) AND m.deleted = 0 AND s.deleted = 0 AND m.isError = 0 AND m.isPlaceholderName = 0
          AND s.id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1')
          AND (:conversationId IS NOT NULL OR m.sessionId != :currentConversationId)
          AND (:conversationId IS NULL OR m.sessionId = :conversationId)
          AND (:startTime IS NULL OR m.timestamp >= :startTime)
          AND (:endTime IS NULL OR m.timestamp < :endTime)
    """)
    suspend fun candidates(ids: List<String>, currentConversationId: String, conversationId: String?, startTime: Long?, endTime: Long?): List<SearchCandidate>

    @Query("SELECT id AS messageId, text FROM messages WHERE id IN (:ids)")
    suspend fun texts(ids: List<String>): List<SearchText>

    /** 标题命中的会话单独列出，模型可以用 conversation_id 读取整段上下文。 */
    @Query("""
        SELECT s.id AS conversationId, COALESCE(s.title, '') AS title, MAX(m.timestamp) AS lastActivity
        FROM chat_sessions s INNER JOIN messages m ON m.sessionId = s.id
        WHERE s.deleted = 0 AND s.isImageGeneration = 0 AND m.deleted = 0 AND s.id != :currentConversationId
          AND s.id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1')
          AND instr(lower(COALESCE(s.title, '')), lower(:query)) > 0
        GROUP BY s.id ORDER BY lastActivity DESC LIMIT :limit
    """)
    suspend fun titleMatches(query: String, currentConversationId: String, limit: Int): List<TitleMatch>
}
