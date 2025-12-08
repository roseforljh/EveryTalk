package com.android.everytalk.data.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 智谱 BigModel Web Search API 客户端
 * 用于调用智谱的联网搜索服务
 */
object ZhipuWebSearchClient {
    private const val TAG = "ZhipuWebSearchClient"
    private const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4/web_search"
    
    /**
     * 搜索结果数据类
     */
    data class SearchResult(
        val title: String,
        val content: String,
        val link: String,
        val media: String?,
        val publishDate: String?
    )
    
    /**
     * 调用智谱 Web Search API
     * 
     * @param client HTTP 客户端
     * @param apiKey 智谱 API 密钥
     * @param query 搜索查询（最大 70 字符）
     * @param searchEngine 搜索引擎类型，默认 "search_pro"
     * @param searchIntent 是否进行搜索意图识别
     * @param count 返回结果条数（最大 50）
     * @param searchRecencyFilter 时间范围过滤，如 "year", "month", "week", "day"
     * @param contentSize 内容长度控制，如 "high", "medium", "low"
     * @return 搜索结果列表
     */
    suspend fun search(
        client: HttpClient,
        apiKey: String,
        query: String,
        searchEngine: String = "search_pro",
        searchIntent: Boolean = false,
        count: Int = 5,
        searchRecencyFilter: String = "year",
        contentSize: String = "high"
    ): List<SearchResult> {
        Log.i(TAG, "调用智谱 Web Search API: query='${query.take(50)}...'")
        
        try {
            // 构建请求体
            val payload = buildJsonObject {
                put("search_query", query.take(70))  // 限制最大 70 字符
                put("search_engine", searchEngine)
                put("search_intent", searchIntent)
                put("count", minOf(count, 50))  // 最大 50 条
                put("search_recency_filter", searchRecencyFilter)
                put("content_size", contentSize)
                put("request_id", UUID.randomUUID().toString())
            }.toString()
            
            val response = client.post(BASE_URL) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.ContentType, "application/json")
                setBody(payload)
                timeout {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
            }
            
            if (!response.status.isSuccess()) {
                Log.e(TAG, "智谱搜索失败: ${response.status}")
                return emptyList()
            }
            
            val responseText = response.bodyAsText()
            val json = Json.parseToJsonElement(responseText).jsonObject
            
            val searchResults = json["search_result"]?.jsonArray ?: return emptyList()
            
            val results = searchResults.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    SearchResult(
                        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        link = obj["link"]?.jsonPrimitive?.contentOrNull ?: "",
                        media = obj["media"]?.jsonPrimitive?.contentOrNull,
                        publishDate = obj["publish_date"]?.jsonPrimitive?.contentOrNull
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "解析搜索结果失败", e)
                    null
                }
            }
            
            Log.i(TAG, "智谱搜索成功，获取 ${results.size} 条结果")
            return results
            
        } catch (e: Exception) {
            Log.e(TAG, "智谱搜索异常", e)
            return emptyList()
        }
    }
    
    /**
     * 格式化搜索结果供 LLM 使用
     * 
     * @param query 原始查询
     * @param results 搜索结果列表
     * @return 格式化的文本
     */
    fun formatSearchResultsForLLM(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) {
            return "未找到相关搜索结果。"
        }
        
        val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINESE)
        val currentDate = dateFormat.format(Date())
        
        return buildString {
            append("[搜索时间: $currentDate]\n")
            append("搜索查询: \"$query\"\n\n")
            
            results.forEachIndexed { index, result ->
                append("${index + 1}. **${result.title}**")
                result.media?.let { append(" - $it") }
                result.publishDate?.let { append(" ($it)") }
                append("\n")
                append("   ${result.content}\n")
                if (result.link.isNotBlank()) {
                    append("   来源: ${result.link}\n")
                }
                append("\n")
            }
            
            append("请根据以上搜索结果回答问题。\n")
        }
    }
    
    /**
     * 转换为通用的 WebSearchResult 格式
     */
    fun toWebSearchResults(results: List<SearchResult>): List<com.android.everytalk.data.DataClass.WebSearchResult> {
        return results.mapIndexed { index, result ->
            com.android.everytalk.data.DataClass.WebSearchResult(
                index = index + 1,
                title = result.title,
                snippet = result.content,
                href = result.link
            )
        }
    }
}