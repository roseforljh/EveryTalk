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
    // 重答复用原消息 ID，而 ChatListItem 在后台线程生成。历史用户项移动到底部前，
    // 旧快照仍能命中同一 ID；此时提前激活会把锚点绑定到旧索引。
    // 仅当前分支最后一个用户项可以启动 top-anchor，等待结构提交完成后再置顶。
    if (items.drop(anchorIndex + 1).any { it.role == TopAnchorItemRole.User }) return null
    return TopAnchorTurn(
        anchorMessageId = sentUserMessageId,
        targetItemId = resolveTopAnchorResponseTargetId(items, sentUserMessageId),
        sessionKey = sessionKey,
        generation = generation,
    )
}

fun resolveTopAnchorResponseTargetId(
    items: List<TopAnchorItem>,
    anchorMessageId: String,
): String? {
    val anchorIndex = items.indexOfFirst {
        it.id == anchorMessageId && it.role == TopAnchorItemRole.User
    }
    if (anchorIndex < 0) return null
    return items
        .drop(anchorIndex + 1)
        .takeWhile { it.role != TopAnchorItemRole.User }
        .firstOrNull {
            it.role == TopAnchorItemRole.AssistantTarget ||
                it.role == TopAnchorItemRole.LoadingTarget ||
                it.role == TopAnchorItemRole.StatusTarget
        }
        ?.id
}
