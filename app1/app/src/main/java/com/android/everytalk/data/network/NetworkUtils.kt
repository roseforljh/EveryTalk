package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.config.PerformanceConfig
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*

object NetworkUtils {
    private const val TAG = "NetworkUtils"
    
    fun HttpRequestBuilder.configureSSERequest() {
        accept(ContentType.Text.EventStream)
        header(HttpHeaders.Accept, "text/event-stream")
        header(HttpHeaders.AcceptEncoding, "identity")
        header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0, must-revalidate")
        header(HttpHeaders.Pragma, "no-cache")
        header(HttpHeaders.Connection, "keep-alive")
        header("X-Accel-Buffering", "no")
        header(
            HttpHeaders.UserAgent,
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        )
        
        timeout {
            requestTimeoutMillis = PerformanceConfig.NETWORK_SSE_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = PerformanceConfig.NETWORK_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = PerformanceConfig.NETWORK_SSE_SOCKET_TIMEOUT_MS
        }
    }
    
    suspend fun handleApiError(
        statusCode: HttpStatusCode,
        errorBody: String?,
        apiName: String
    ): Pair<AppStreamEvent.Error, AppStreamEvent.Finish> {
        val body = errorBody ?: "(no body)"
        Log.e(TAG, "$apiName API 错误 $statusCode: $body")
        
        val errorMessage = when (statusCode.value) {
            401 -> "$apiName: API 密钥无效或已过期"
            403 -> "$apiName: 访问被拒绝，请检查 API 权限"
            429 -> "$apiName: 请求过于频繁，请稍后重试"
            500, 502, 503, 504 -> "$apiName: 服务器暂时不可用，请稍后重试"
            else -> "$apiName API 错误: $statusCode"
        }
        
        return AppStreamEvent.Error(errorMessage, statusCode.value) to 
               AppStreamEvent.Finish("api_error")
    }
    
    fun handleConnectionError(
        exception: Exception,
        apiName: String
    ): Pair<AppStreamEvent.Error, AppStreamEvent.Finish> {
        Log.e(TAG, "$apiName 连接失败", exception)
        
        val errorMessage = when {
            exception is java.net.UnknownHostException -> "$apiName: 无法连接服务器，请检查网络"
            exception is java.net.SocketTimeoutException -> "$apiName: 连接超时，请检查网络"
            exception is javax.net.ssl.SSLException -> "$apiName: SSL 连接失败，请检查网络安全设置"
            else -> "$apiName 连接失败: ${exception.message}"
        }
        
        return AppStreamEvent.Error(errorMessage, null) to
               AppStreamEvent.Finish("connection_failed")
    }
}
