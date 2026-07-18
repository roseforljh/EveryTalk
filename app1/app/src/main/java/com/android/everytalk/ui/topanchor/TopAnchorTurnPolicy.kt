package com.android.everytalk.ui.topanchor

fun resolveActiveTopAnchorTurn(
    items: List<TopAnchorItem>,
    sentUserMessageId: String?,
    sessionKey: String,
    generation: Long
): TopAnchorTurn? {
    if (sentUserMessageId.isNullOrBlank()) return null
    val anchorIndex = items.indexOfFirst {
        it.id == sentUserMessageId && it.role == TopAnchorItemRole.User
    }
    if (anchorIndex < 0) return null
    val usersBeforeOrAtAnchor = items.take(anchorIndex + 1).count {
        it.role == TopAnchorItemRole.User
    }
    if (usersBeforeOrAtAnchor <= 1) return null
    val target = items.drop(anchorIndex + 1).firstOrNull {
        it.role == TopAnchorItemRole.AssistantTarget ||
            it.role == TopAnchorItemRole.LoadingTarget ||
            it.role == TopAnchorItemRole.StatusTarget
    } ?: return null
    return TopAnchorTurn(
        anchorMessageId = sentUserMessageId,
        targetItemId = target.id,
        sessionKey = sessionKey,
        generation = generation
    )
}
