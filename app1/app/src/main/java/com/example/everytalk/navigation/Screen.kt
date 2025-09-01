package com.example.everytalk.navigation
object Screen {
    const val HOME_SCREEN = "home_screen"
    const val CHAT_SCREEN = "chat_screen"
    const val SETTINGS_SCREEN = "settings_screen"
    const val IMAGE_GENERATION_SCREEN = "image_generation_screen"
    const val IMAGE_GENERATION_SETTINGS_SCREEN = "image_generation_settings_screen"
    
    // 新增：带参数的路由
    const val CHAT_WITH_HISTORY = "chat_screen/{historyIndex}"
    const val IMAGE_WITH_HISTORY = "image_generation_screen/{historyIndex}"
    
    // 辅助函数
    fun chatWithHistory(index: Int) = "chat_screen/$index"
    fun imageWithHistory(index: Int) = "image_generation_screen/$index"
}