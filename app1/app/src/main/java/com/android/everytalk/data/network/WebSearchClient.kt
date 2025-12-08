package com.android.everytalk.data.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

object WebSearchClient {
    private const val TAG = "WebSearchClient"
    
    data class SearchResult(
        val title: String,
        val href: String,
        val snippet: String
    )
    
    suspend fun search(
        client: HttpClient,
        query: String,
        apiKey: String,
        cseId: String,
        count: Int = 5
    ): List<SearchResult> {
        val url = "https://www.googleapis.com/customsearch/v1"
        
        try {
            val response = client.get(url) {
                parameter("key", apiKey)
                parameter("cx", cseId)
                parameter("q", query)
                parameter("num", count)
            }
            
            if (!response.status.isSuccess()) {
                Log.e(TAG, "Search failed: ${response.status}")
                return emptyList()
            }
            
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            return json["items"]?.jsonArray?.mapNotNull { item ->
                try {
                    val obj = item.jsonObject
                    SearchResult(
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        href = obj["link"]?.jsonPrimitive?.content ?: "",
                        snippet = obj["snippet"]?.jsonPrimitive?.content ?: ""
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Search exception", e)
            return emptyList()
        }
    }
    
    fun formatSearchContext(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        
        return buildString {
            append("Search results for \"$query\":\n\n")
            results.forEachIndexed { index, result ->
                append("${index + 1}. ${result.title}\n")
                append("${result.snippet}\n")
                append("${result.href}\n\n")
            }
            append("Please answer based on the search results above.\n")
        }
    }
}