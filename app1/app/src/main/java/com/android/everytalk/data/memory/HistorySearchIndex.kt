package com.android.everytalk.data.memory

import androidx.room.withTransaction
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.daos.SearchCandidate
import com.android.everytalk.data.database.entities.MessageSearchEntity
import com.android.everytalk.data.database.entities.MessageSearchMapEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 历史会话全文检索（参考 Hermes 的 session_search）。Android 不保证 FTS5，这里用全版本可用的 FTS4：
 * - 分词由应用完成：中日韩文字按相邻双字切分，其他文字按单词，原样小写；
 * - 索引增量维护：只处理新增、内容变化和已删除的消息，发送时后台补、搜索前再补；
 * - 排序用 BM25（matchinfo 计算），全部关键词都命中的优先；一条都没有时退回“命中越多越靠前”。
 */
class HistorySearchIndex(private val database: AppDatabase) {
    private val dao = database.messageSearchDao()
    private val lock = Mutex()

    /** complete=false 表示只命中部分关键词（退回“部分命中”时）。 */
    data class RankedHit(val candidate: SearchCandidate, val score: Double, val covered: Int, val complete: Boolean)

    /** 把未进入索引的消息补齐；已在更新时直接返回，不重复工作。 */
    suspend fun catchUp(maxMessages: Int = Int.MAX_VALUE) = lock.withLock {
        var done = 0
        while (done < maxMessages) {
            val stale = dao.stale(BATCH)
            val pending = dao.pending(BATCH)
            if (stale.isEmpty() && pending.isEmpty()) break
            database.withTransaction {
                stale.forEach { dao.deleteBody(it.docid); dao.deleteMap(it.messageId) }
                pending.forEach { message ->
                    message.docid?.let { dao.deleteBody(it) }
                    val docid = dao.insertBody(MessageSearchEntity(body = indexText(message.text)))
                    dao.upsertMap(MessageSearchMapEntity(message.messageId, docid, message.fingerprint))
                }
            }
            done += stale.size + pending.size
        }
    }

    /**
     * 按相关度返回命中的消息（已按过滤条件筛过）。返回 null 表示这个查询不适合用索引
     * （例如只有一个汉字），调用方改用逐条扫描。
     */
    suspend fun search(
        query: String, currentConversationId: String, conversationId: String?, startTime: Long?, endTime: Long?,
    ): List<RankedHit>? {
        val terms = queryTerms(query) ?: return null
        catchUp()
        val quoted = terms.map { "\"$it\"" }
        var hits = dao.match(quoted.joinToString(" "), CANDIDATE_LIMIT)
        val all = hits.isNotEmpty()
        // 只有用空格分开的多个关键词才退回部分命中；一个完整词组必须整体命中，避免拿半个词凑结果。
        if (!all && terms.size > 1 && query.trim().contains(Regex("""\s"""))) hits = dao.match(quoted.joinToString(" OR "), CANDIDATE_LIMIT)
        if (hits.isEmpty()) return emptyList()
        val scores = hits.associate { it.messageId to bm25(it.info, terms.size) }
        val candidates = scores.keys.chunked(500).flatMap { dao.candidates(it, currentConversationId, conversationId, startTime, endTime) }
        return candidates.map { candidate ->
            val (score, covered) = scores.getValue(candidate.messageId)
            val hit = if (all) terms.size else covered
            RankedHit(candidate, score, hit, hit >= terms.size)
        }.sortedWith(compareByDescending<RankedHit> { it.covered }.thenByDescending { it.score }.thenByDescending { it.candidate.timestamp })
    }

    suspend fun texts(ids: List<String>): Map<String, String> = dao.texts(ids).associate { it.messageId to it.text }

    suspend fun titleMatches(query: String, currentConversationId: String) = dao.titleMatches(query.trim(), currentConversationId, 5)

    companion object {
        private const val BATCH = 400
        private const val CANDIDATE_LIMIT = 2_000
        private const val K1 = 1.2
        private const val B = 0.75
        private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]+""")
        private val WORD = Regex("""[\p{L}\p{M}\p{N}]+""")

        /** 索引用的分词：中日韩连续文字切成相邻双字（只有一个字时保留单字），其他按单词。 */
        internal fun tokens(text: String): List<String> = WORD.findAll(text.lowercase()).flatMap { match ->
            val word = match.value
            if (!CJK.containsMatchIn(word)) return@flatMap sequenceOf(word)
            val parts = mutableListOf<String>()
            var last = 0
            CJK.findAll(word).forEach { run ->
                if (run.range.first > last) parts += word.substring(last, run.range.first)
                val chars = run.value.codePoints().toArray().map { String(Character.toChars(it)) }
                if (chars.size == 1) parts += chars[0] else chars.windowed(2).forEach { parts += it.joinToString("") }
                last = run.range.last + 1
            }
            if (last < word.length) parts += word.substring(last)
            parts.asSequence()
        }.filter(String::isNotEmpty).toList()

        internal fun indexText(text: String): String = tokens(text).joinToString(" ")

        /** 查询词：去重；只有单个汉字这类索引覆盖不全的查询返回 null，改走逐条扫描。 */
        internal fun queryTerms(query: String): List<String>? {
            val words = WORD.findAll(query.lowercase()).map { it.value }.toList()
            if (words.isEmpty()) return null
            if (words.any { word -> CJK.findAll(word).any { it.value.codePointCount(0, it.value.length) == 1 } }) return null
            return tokens(query).distinct().map { it.replace("\"", "") }.filter(String::isNotEmpty).take(32).ifEmpty { null }
        }

        /**
         * 解析 matchinfo('pcnalx')：p 短语数，c 列数，n 文档数，a 平均词数，l 本行词数，
         * x 每个短语每列三元组（本行命中次数、全部命中次数、命中文档数）。返回 BM25 分数和命中的词数。
         */
        internal fun bm25(info: ByteArray, phraseCount: Int): Pair<Double, Int> {
            val ints = ByteBuffer.wrap(info).order(ByteOrder.nativeOrder()).asIntBuffer()
            val p = ints.get(0); val c = ints.get(1); val n = ints.get(2).toDouble()
            val average = ints.get(3).toDouble().coerceAtLeast(1.0)
            val length = ints.get(3 + c).toDouble()
            var score = 0.0
            var covered = 0
            for (phrase in 0 until minOf(p, phraseCount)) {
                val base = 3 + 2 * c + phrase * c * 3
                val tf = ints.get(base).toDouble()
                val df = ints.get(base + 2).toDouble()
                if (tf <= 0) continue
                covered++
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / average))
            }
            return score to covered
        }
    }
}
