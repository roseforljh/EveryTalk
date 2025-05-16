package com.example.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.ui.geometry.Offset
import com.example.everytalk.data.DataClass.Message

// 自定义涟漪状态
internal sealed class CustomRippleState {
    object Idle : CustomRippleState() // 空闲状态
    data class Animating(val pressPosition: Offset) : CustomRippleState() // 动画中状态，包含按压位置
}

// 用于列表显示的过滤后的对话项数据结构
internal data class FilteredConversationItem(
    val originalIndex: Int, // 在原始历史对话列表中的索引
    val conversation: List<Message>, // 对话消息列表 (仍用于搜索时匹配内容和生成高亮片段)
)