package com.android.everytalk.navigation
object Screen {
    const val HOME_SCREEN = "home_screen"
    const val CHAT_SCREEN = "chat_screen"
    const val SETTINGS_SCREEN = "settings_screen"
    const val COMPUTER_SCREEN = "computer_screen"
    const val COMPUTER_DETAIL_SCREEN = "computer_detail/{computerId}"
    const val APP_INFO_SCREEN = "app_info_screen"
    const val PRIVACY_POLICY_SCREEN = "privacy_policy_screen"
    const val IMAGE_GENERATION_SCREEN = "image_generation_screen"
    const val IMAGE_GENERATION_SETTINGS_SCREEN = "image_generation_settings_screen"
    const val VOICE_INPUT_SCREEN = "voice_input_screen"

    /** 服务器页返回设置页时，用于指定平台配置、联网搜索或 MCP 页签。 */
    const val SETTINGS_TAB_REQUEST_KEY = "settings_tab_request"

    /** 服务器页返回设置页时，用于请求打开导入导出对话框。 */
    const val SETTINGS_IMPORT_EXPORT_REQUEST_KEY = "settings_import_export_request"
    
    // 新增：带参数的路由
    const val CHAT_WITH_HISTORY = "chat_screen/{historyIndex}"
    const val IMAGE_WITH_HISTORY = "image_generation_screen/{historyIndex}"
    
    // 辅助函数
    fun chatWithHistory(index: Int) = "chat_screen/$index"
    fun imageWithHistory(index: Int) = "image_generation_screen/$index"
    fun computerDetail(computerId: String) = "computer_detail/$computerId"
}
