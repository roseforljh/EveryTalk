package com.android.everytalk.util

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名工具类
 * 用于生成符合后端签名验证要求的请求签名
 * 
 * 签名算法:
 * 1. 计算请求体的 SHA-256 哈希
 * 2. 构建签名字符串: timestamp|method|path|bodyHash
 * 3. 使用 HMAC-SHA256 计算签名
 * 4. Base64 编码签名结果
 */
object RequestSignatureUtil {
    
    private const val TAG = "RequestSignature"
    
    /**
     * 签名密钥
     * ⚠️ 重要: 此密钥必须与后端 .env 文件中的 SIGNATURE_SECRET_KEYS 保持一致
     *
     * 当前密钥与后端同步: TRrYbMyQFs6B0T9L7wL0_2jvZQXZ8KFatqS5TN9haf4
     *
     * 生产环境应该:
     * 1. 使用 BuildConfig 从构建配置中读取
     * 2. 或使用 NDK 存储在 native 代码中
     * 3. 不要硬编码在代码中
     */
    private const val SECRET_KEY = "TRrYbMyQFs6B0T9L7wL0_2jvZQXZ8KFatqS5TN9haf4"
    
    /**
     * 生成请求签名
     * 
     * @param method HTTP 方法 (GET, POST, etc.)
     * @param path 请求路径 (如 /chat)
     * @param body 请求体内容 (JSON 字符串)
     * @param timestamp 时间戳(毫秒)
     * @return Base64 编码的签名字符串
     */
    fun generateSignature(
        method: String,
        path: String,
        body: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): String {
        try {
            // 1. 计算请求体的 SHA-256 哈希
            val bodyHash = if (body.isNotEmpty()) {
                calculateSHA256(body)
            } else {
                ""
            }
            
            // 2. 构建签名字符串
            // 格式: timestamp|method|path|bodyHash
            val signatureData = "$timestamp|${method.uppercase()}|$path|$bodyHash"
            
            Log.d(TAG, "签名数据: $signatureData")
            
            // 3. 使用 HMAC-SHA256 计算签名
            val hmacSha256 = Mac.getInstance("HmacSHA256")
            val secretKeySpec = SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")
            hmacSha256.init(secretKeySpec)
            
            val signatureBytes = hmacSha256.doFinal(signatureData.toByteArray(Charsets.UTF_8))
            
            // 4. Base64 编码
            val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
            
            Log.d(TAG, "生成签名: ${signature.take(20)}...")
            
            return signature
            
        } catch (e: Exception) {
            Log.e(TAG, "生成签名失败", e)
            throw RuntimeException("Failed to generate signature", e)
        }
    }
    
    /**
     * 计算字符串的 SHA-256 哈希
     */
    private fun calculateSHA256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 生成当前时间戳(毫秒)
     */
    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis()
    }
    
    /**
     * 为请求添加签名头
     * 
     * @return Map<String, String> 包含 X-Signature 和 X-Timestamp 的请求头
     */
    fun generateSignatureHeaders(
        method: String,
        path: String,
        body: String = ""
    ): Map<String, String> {
        val timestamp = getCurrentTimestamp()
        val signature = generateSignature(method, path, body, timestamp)
        
        return mapOf(
            "X-Signature" to signature,
            "X-Timestamp" to timestamp.toString()
        )
    }
}