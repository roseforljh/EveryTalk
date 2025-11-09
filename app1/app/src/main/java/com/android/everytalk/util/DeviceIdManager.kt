package com.android.everytalk.util

import android.content.Context
import android.provider.Settings

/**
 * 设备ID管理器
 * 用于获取设备唯一标识符，用于速率限制等功能
 * 
 * 使用 Android ID 作为设备标识：
 * - 设备唯一，重装APP后保持不变
 * - 无需额外权限
 * - 恢复出厂设置后会改变（这是可接受的）
 */
object DeviceIdManager {
    private var cachedDeviceId: String? = null
    
    /**
     * 获取设备唯一标识符
     * 
     * @param context Android Context
     * @return 设备ID字符串
     */
    fun getDeviceId(context: Context): String {
        // 如果已缓存，直接返回
        if (cachedDeviceId != null) {
            return cachedDeviceId!!
        }
        
        // 获取 Android ID（手机唯一标识）
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        
        // 缓存并返回
        cachedDeviceId = androidId ?: "unknown"
        return cachedDeviceId!!
    }
    
    /**
     * 获取设备ID的脱敏预览（用于调试和显示）
     * 
     * @param context Android Context
     * @return 脱敏后的设备ID，格式如 "abc12345..."
     */
    fun getDeviceIdPreview(context: Context): String {
        val fullId = getDeviceId(context)
        return if (fullId.length > 8) {
            fullId.take(8) + "..."
        } else {
            fullId
        }
    }
}