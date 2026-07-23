package com.android.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

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
