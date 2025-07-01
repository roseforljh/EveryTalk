package com.example.everytalk.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

class StreamingMarkdownRenderer {
    private val parser = IncrementalMarkdownParser

    fun renderStreaming(
        messageId: String,
        text: String,
        isComplete: Boolean,
        isUserScrolling: Boolean
    ): AnnotatedString {
        val elements = parser.parseIncrementalStream(messageId, text, isComplete)
        return parser.render(elements)
    }
}