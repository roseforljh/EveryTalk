package com.example.everytalk.util

import android.util.Log

/**
 * 统一的日志工具类，用于减少日志记录的冗余
 * 提供统一的日志格式和标签管理
 */
object AppLogger {
    private const val APP_TAG = "KunTalk"
    private var isDebugEnabled = true
    
    /**
     * 设置是否启用调试日志
     */
    fun setDebugEnabled(enabled: Boolean) {
        isDebugEnabled = enabled
    }
    
    /**
     * 记录调试级别日志
     * @param component 组件名称
     * @param message 日志消息
     */
    fun debug(component: String, message: String) {
        if (isDebugEnabled) {
            Log.d("$APP_TAG:$component", message)
        }
    }
    
    /**
     * 记录信息级别日志
     * @param component 组件名称
     * @param message 日志消息
     */
    fun info(component: String, message: String) {
        Log.i("$APP_TAG:$component", message)
    }
    
    /**
     * 记录警告级别日志
     * @param component 组件名称
     * @param message 日志消息
     */
    fun warn(component: String, message: String) {
        Log.w("$APP_TAG:$component", message)
    }
    
    /**
     * 记录错误级别日志
     * @param component 组件名称
     * @param message 日志消息
     * @param error 异常对象
     */
    fun error(component: String, message: String, error: Throwable? = null) {
        Log.e("$APP_TAG:$component", message, error)
    }
    
    /**
     * 创建组件日志记录器
     * @param component 组件名称
     * @return 组件日志记录器
     */
    fun forComponent(component: String): ComponentLogger {
        return ComponentLogger(component)
    }
    
    /**
     * 组件日志记录器，简化特定组件的日志记录
     */
    class ComponentLogger(private val component: String) {
        fun debug(message: String) = AppLogger.debug(component, message)
        fun info(message: String) = AppLogger.info(component, message)
        fun warn(message: String) = AppLogger.warn(component, message)
        fun error(message: String, error: Throwable? = null) = AppLogger.error(component, message, error)
    }
}