package com.android.everytalk.ui.topanchor

fun resolveActiveTopAnchorTurn(
    items: List<TopAnchorItem>,
    sentUserMessageId: String?,
    sessionKey: String,
    generation: Long,
): TopAnchorTurn? {
    // Android 列表先提交用户消息，AI 占位随后异步进入。
    // 用户项出现即建立稳定锚点，避免把置顶时机绑定到易变化的 AI 子项结构。
    if (sentUserMessageId.isNullOrBlank()) return null
    val anchorIndex = items.indexOfFirst {
        it.id == sentUserMessageId && it.role == TopAnchorItemRole.User
    }
    if (anchorIndex < 0) return null
    val target = items.drop(anchorIndex + 1).firstOrNull {
        it.role == TopAnchorItemRole.AssistantTarget ||
            it.role == TopAnchorItemRole.LoadingTarget ||
            it.role == TopAnchorItemRole.StatusTarget
    }
    return TopAnchorTurn(
        anchorMessageId = sentUserMessageId,
        targetItemId = target?.id,
        sessionKey = sessionKey,
        generation = generation,
    )
}
