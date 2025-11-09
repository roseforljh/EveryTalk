package com.android.everytalk.security

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名工具类
 * 用于生成和验证API请求签名，防止中间人攻击和请求篡改
 */
object RequestSignatureUtil {
    
    // 签名密钥 - 生产环境密钥 (2024-11-09生成)
    // 注意: 此密钥必须与后端配置保持一致
    private const val SECRET_KEY = "TRrYbMyQFs6B0T9L7wL0_2jvZQXZ8KFatqS5TN9haf4"
    
    // HMAC算法
    private const val HMAC_ALGORITHM = "HmacSHA256"
    
    // 签名有效期（毫秒）- 5分钟
    private const val SIGNATURE_VALIDITY_MS = 5 * 60 * 1000L
    
    /**
     * 生成请求签名
     * 
     * @param method HTTP方法（GET, POST等）
     * @param path 请求路径（不包含域名和查询参数）
     * @param body 请求体内容（可为null）
     * @param timestamp 时间戳（毫秒）
     * @return 签名字符串（Base64编码）
     */
    fun generateSignature(
        method: String,
        path: String,
        body: String?,
        timestamp: Long
    ): String {
        // 1. 计算请求体的SHA-256哈希（如果有请求体）
        val bodyHash = if (body != null && body.isNotEmpty()) {
            sha256Hash(body)
        } else {
            ""
        }
        
        // 2. 构建待签名字符串
        // 格式: timestamp|method|path|bodyHash
        val signatureData = "$timestamp|${method.uppercase()}|$path|$bodyHash"
        
        // 3. 使用HMAC-SHA256生成签名
        val signature = hmacSha256(signatureData, SECRET_KEY)
        
        // 4. Base64编码
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }
    
    /**
     * 生成当前时间戳（毫秒）
     */
    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis()
    }
    
    /**
     * 验证时间戳是否在有效期内
     * 
     * @param timestamp 要验证的时间戳
     * @return 是否有效
     */
    fun isTimestampValid(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeDiff = Math.abs(currentTime - timestamp)
        return timeDiff <= SIGNATURE_VALIDITY_MS
    }
    
    /**
     * 计算字符串的SHA-256哈希
     * 
     * @param input 输入字符串
     * @return 十六进制哈希字符串
     */
    private fun sha256Hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 使用HMAC-SHA256算法生成签名
     * 
     * @param data 待签名数据
     * @param key 密钥
     * @return 签名字节数组
     */
    private fun hmacSha256(data: String, key: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * 生成签名头部信息
     * 
     * @param method HTTP方法
     * @param path 请求路径
     * @param body 请求体（可为null）
     * @return 包含签名和时间戳的Map
     */
    fun generateSignatureHeaders(
        method: String,
        path: String,
        body: String?
    ): Map<String, String> {
        val timestamp = getCurrentTimestamp()
        val signature = generateSignature(method, path, body, timestamp)
        
        return mapOf(
            "X-Signature" to signature,
            "X-Timestamp" to timestamp.toString()
        )
    }
    
    /**
     * 从URL中提取路径（不包含域名和查询参数）
     * 
     * @param url 完整URL
     * @return 路径部分
     */
    fun extractPath(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.path ?: "/"
        } catch (e: Exception) {
            "/"
        }
    }
}