package com.android.everytalk.ui.components.icons

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MDI (Material Design Icons) 图标名到 Unicode 码点的映射
 * 运行时从 assets/mdi-codepoints.json 加载
 */
object MdiIconMap {
    private val codepoints = ConcurrentHashMap<String, Int>()
    private var initialized = false

    /**
     * 初始化图标映射（需在 Application 或首次使用前调用）
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val json = context.assets.open("mdi-codepoints.json")
                    .bufferedReader()
                    .use { it.readText() }
                val jsonObject = JSONObject(json)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    codepoints[key] = jsonObject.getInt(key)
                }
                initialized = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 根据图标名获取 Unicode 码点
     * @param name 图标名，如 "database-import"
     * @return Unicode 码点，如果不存在返回 null
     */
    fun getCodepoint(name: String): Int? = codepoints[name.lowercase()]

    /**
     * 检查图标是否存在
     */
    fun contains(name: String): Boolean = codepoints.containsKey(name.lowercase())

    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean = initialized
}
