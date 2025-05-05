package com.example.app1.ui.screens

// --- 枚举和常量 ---
enum class AppView { CurrentChat, HistoryList } // 应用视图枚举：当前聊天，历史列表

const val ERROR_VISUAL_PREFIX = "⚠️ [错误] " // 错误信息前缀
const val USER_CANCEL_MESSAGE = "用户手动停止" // 用户取消 API 调用时的原因