package com.android.everytalk.data.network.client

import android.content.Context
import com.android.everytalk.data.network.AnySerializer
import com.android.everytalk.data.network.AppStreamEvent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.cache.storage.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okhttp3.ConnectionPool
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端工厂
 * 从 ApiClient.kt 中提取，负责创建和配置 Ktor HttpClient
 */
object HttpClientFactory {
    
    /**
     * 创建 JSON 解析器
     */
    fun createJsonParser(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(Any::class, AnySerializer)
            polymorphic(com.android.everytalk.data.DataClass.AbstractApiMessage::class) {
                subclass(com.android.everytalk.data.DataClass.SimpleTextApiMessage::class)
                subclass(com.android.everytalk.data.DataClass.PartsApiMessage::class)
            }
            polymorphic(AppStreamEvent::class) {
                subclass(AppStreamEvent.Text::class)
                subclass(AppStreamEvent.Content::class)
                subclass(AppStreamEvent.ContentFinal::class)
                subclass(AppStreamEvent.Reasoning::class)
                subclass(AppStreamEvent.ReasoningFinish::class)
                subclass(AppStreamEvent.StreamEnd::class)
                subclass(AppStreamEvent.WebSearchStatus::class)
                subclass(AppStreamEvent.WebSearchResults::class)
                subclass(AppStreamEvent.StatusUpdate::class)
                subclass(AppStreamEvent.ToolCall::class)
                subclass(AppStreamEvent.Error::class)
                subclass(AppStreamEvent.Finish::class)
                subclass(AppStreamEvent.ImageGeneration::class)
                subclass(AppStreamEvent.CodeExecutionResult::class)
                subclass(AppStreamEvent.CodeExecutable::class)
            }
        }
    }
    
    /**
     * 创建配置好的 HttpClient
     */
    fun createHttpClient(context: Context, jsonParser: Json): HttpClient {
        val cacheFile = File(context.cacheDir, "ktor_http_cache")
        
        return HttpClient(OkHttp) {
            engine {
                config {
                    // 超时配置：跨境场景适当增加
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(300, TimeUnit.SECONDS)   // 5分钟，适合长时间流式响应
                    writeTimeout(120, TimeUnit.SECONDS)  // 2分钟，适合大文件上传
                    
                    // 连接池配置：复用连接减少握手延迟
                    connectionPool(ConnectionPool(
                        10,  // 最大空闲连接数
                        5,   // 连接保持活跃时间
                        TimeUnit.MINUTES
                    ))
                    
                    // 启用 HTTP/2 + HTTP/1.1 回退
                    protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                }
            }
            
            install(ContentNegotiation) {
                json(jsonParser)
            }
            
            install(HttpTimeout) {
                requestTimeoutMillis = 1800_000
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 1800_000
            }
            
            install(HttpCache) {
                publicStorage(FileStorage(cacheFile))
                privateStorage(FileStorage(File(context.cacheDir, "ktor_private_cache")))
            }
            
            // WebSocket 支持
            install(WebSockets) {
                pingIntervalMillis = 30_000
            }
            
            // 日志记录
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        android.util.Log.d("ApiClient-HTTP", message)
                    }
                }
                level = LogLevel.INFO
            }
        }
    }
}
