package com.example.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun rememberGeneratedPreviewSnippet(
    messageText: String, query: String, contextChars: Int = 10
): AnnotatedString? {
    val highlightColor = MaterialTheme.colorScheme.primary
    return remember(messageText, query, highlightColor, contextChars) {
        if (query.isBlank()) return@remember null
        val queryLower = query.lowercase()
        val textLower = messageText.lowercase()
        val startIndex = textLower.indexOf(queryLower)
        if (startIndex == -1) return@remember null

        val snippetStart = max(0, startIndex - contextChars)
        val snippetEnd = min(messageText.length, startIndex + query.length + contextChars)
        val prefix = if (snippetStart > 0) "..." else ""
        val suffix = if (snippetEnd < messageText.length) "..." else ""
        val rawSnippet = messageText.substring(snippetStart, snippetEnd)

        buildAnnotatedString {
            append(prefix)
            val queryIndexInRawSnippet = rawSnippet.lowercase().indexOf(queryLower)
            if (queryIndexInRawSnippet != -1) {
                append(rawSnippet.substring(0, queryIndexInRawSnippet))
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = highlightColor
                    )
                ) {
                    append(
                        rawSnippet.substring(
                            queryIndexInRawSnippet,
                            queryIndexInRawSnippet + query.length
                        )
                    )
                }
                append(rawSnippet.substring(queryIndexInRawSnippet + query.length))
            } else {
                append(rawSnippet)
            }
            append(suffix)
        }
    }
}
@Composable
internal fun rememberHighlightedText(
    text: String,
    query: String
): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primary
    return remember(text, query, highlightColor) {
        if (query.isBlank()) return@remember AnnotatedString(text)

        buildAnnotatedString {
            var lastIndex = 0
            val queryLower = query.lowercase()
            while (lastIndex < text.length) {
                val startIndex = text.lowercase().indexOf(queryLower, lastIndex)
                if (startIndex == -1) {
                    append(text.substring(lastIndex))
                    break
                }
                append(text.substring(lastIndex, startIndex))
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = highlightColor
                    )
                ) {
                    append(text.substring(startIndex, startIndex + query.length))
                }
                lastIndex = startIndex + query.length
            }
        }
    }
}