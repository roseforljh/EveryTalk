package com.android.everytalk.data.network.client

import android.util.Log
import com.android.everytalk.data.network.ModelInfo
import com.android.everytalk.data.network.ModelsResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * 模型 API 客户端
 * 从 ApiClient.kt 中提取，负责获取模型列表
 */
object ModelApiClient {
    private const val TAG = "ModelApiClient"
    
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * 获取可用模型列表
     */
    suspend fun fetchModels(
        client: HttpClient,
        baseAddress: String,
        apiKey: String
    ): List<String> {
        val modelsUrl = buildModelsUrl(baseAddress)
        Log.d(TAG, "Fetching models from: $modelsUrl")
        
        return try {
            val response: HttpResponse = client.get(modelsUrl) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
            }
            
            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText()
                parseModelsResponse(responseText)
            } else {
                Log.e(TAG, "Failed to fetch models: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching models", e)
            emptyList()
        }
    }
    
    /**
     * 构建模型列表 URL
     */
    private fun buildModelsUrl(baseAddress: String): String {
        val trimmedAddress = baseAddress.trim().removeSuffix("#").trimEnd('/')
        val withScheme = when {
            trimmedAddress.startsWith("http://") || trimmedAddress.startsWith("https://") -> trimmedAddress
            trimmedAddress.isNotEmpty() -> "https://$trimmedAddress"
            else -> trimmedAddress
        }
        
        val uri = try { 
            java.net.URI(withScheme) 
        } catch (_: Exception) { 
            java.net.URI("https://$trimmedAddress") 
        }
        
        val existingPath = uri.rawPath ?: ""
        val basePath = existingPath
            .replace(Regex("/v1/chat/completions/?$"), "/v1")
            .replace(Regex("/chat/completions/?$"), "")
            .replace(Regex("/v1/completions/?$"), "/v1")
            .replace(Regex("/completions/?$"), "")
        
        val finalPath = when {
            basePath.isEmpty() -> "/v1/models"
            basePath.contains("/models") -> basePath
            basePath.endsWith("/v1") -> "$basePath/models"
            basePath.endsWith("/") -> basePath + "v1/models"
            else -> "$basePath/v1/models"
        }.replace(Regex("/{2,}"), "/")
        
        return java.net.URI(
            uri.scheme, 
            uri.userInfo, 
            uri.host, 
            uri.port, 
            finalPath, 
            uri.rawQuery, 
            uri.rawFragment
        ).toString()
    }
    
    /**
     * 解析模型响应
     */
    private fun parseModelsResponse(responseText: String): List<String> {
        return try {
            val modelsResponse = jsonParser.decodeFromString<ModelsResponse>(responseText)
            modelsResponse.data.map { it.id }.also {
                Log.d(TAG, "Parsed ${it.size} models")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse models response", e)
            emptyList()
        }
    }
}
