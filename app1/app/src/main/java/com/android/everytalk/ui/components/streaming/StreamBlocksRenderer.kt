package com.android.everytalk.ui.components.streaming

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.markdown.MikePenzMarkdownRenderer

@Composable
fun UnifiedMarkdownRenderer(
    markdown: String,
    contentKey: String,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    isStreaming: Boolean = false,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    MikePenzMarkdownRenderer(
        markdown = markdown,
        contentKey = contentKey,
        modifier = modifier,
        sender = sender,
        isStreaming = isStreaming,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
    )
}

internal data class FencedCodeBlockContent(
    val language: String?,
    val code: String,
)

private val fencedCodeOpeningLinePattern = Regex("""^\s*([`~]{3,})([^\n`~]*)$""")

internal fun extractFencedCodeBlockContent(text: String): FencedCodeBlockContent {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val opening = lines.firstOrNull()?.let { fencedCodeOpeningLinePattern.matchEntire(it) }
    if (opening != null) {
        val marker = opening.groupValues[1]
        val language = opening.groupValues[2].trim().ifBlank { null }
        val bodyLines = lines.drop(1).toMutableList()
        if (bodyLines.isNotEmpty() && isFenceClosingLineForMarker(bodyLines.last(), marker)) {
            bodyLines.removeAt(bodyLines.lastIndex)
        }
        return FencedCodeBlockContent(
            language = language,
            code = bodyLines.joinToString("\n"),
        )
    }
    return FencedCodeBlockContent(language = null, code = normalized)
}

private fun isFenceClosingLineForMarker(line: String, marker: String): Boolean {
    val trimmed = line.trimStart()
    val markerChar = marker.first()
    var markerLength = 0
    while (markerLength < trimmed.length && trimmed[markerLength] == markerChar) {
        markerLength++
    }
    return markerLength >= marker.length && trimmed.substring(markerLength).isBlank()
}
