package com.android.everytalk.ui.components.coordinator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer

/**
 * 兼容旧调用方的内容入口，所有 Markdown 内容统一交给 Compose 渲染管线。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ContentCoordinator(
    text: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = "",
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    sender: Sender = Sender.AI,
    disableVerticalPadding: Boolean = false,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    UnifiedMarkdownRenderer(
        markdown = text,
        contentKey = contentKey.ifBlank { "content-${text.hashCode()}" },
        modifier = modifier,
        sender = sender,
        isStreaming = isStreaming,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
    )
}
