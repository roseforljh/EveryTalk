package com.android.everytalk.network

import com.android.everytalk.security.RequestSignatureUtil
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * OkHttp拦截器 - 自动为所有请求添加签名
 * 
 * 使用方法：
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(SignatureInterceptor())
 *     .build()
 * ```
 */
class SignatureInterceptor : Interceptor {
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 获取请求信息
        val method = originalRequest.method
        val url = originalRequest.url.toString()
        val path = RequestSignatureUtil.extractPath(url)
        
        // 读取请求体
        val requestBody = originalRequest.body
        val bodyString = if (requestBody != null) {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } else {
            null
        }
        
        // 生成签名头部
        val signatureHeaders = RequestSignatureUtil.generateSignatureHeaders(
            method = method,
            path = path,
            body = bodyString
        )
        
        // 构建新请求，添加签名头部
        val newRequest = originalRequest.newBuilder()
            .apply {
                signatureHeaders.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        // 继续请求
        return chain.proceed(newRequest)
    }
}