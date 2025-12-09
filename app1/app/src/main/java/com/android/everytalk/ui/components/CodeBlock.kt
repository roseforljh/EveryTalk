package com.android.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Simple code block renderer used by TableAwareText.
 * It shows code in a monospace box with optional horizontal scroll and long-press handling.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    textColor: Color = Color.Unspecified,
    enableHorizontalScroll: Boolean = true,
    modifier: Modifier = Modifier,
    maxHeight: Int = 0,
    onPreviewClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    val effectiveTextColor = when {
        textColor != Color.Unspecified -> textColor
        else -> MaterialTheme.colorScheme.onSurface
    }

    val baseModifier = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(8.dp)

    val scrollModifier = if (enableHorizontalScroll) {
        baseModifier.horizontalScroll(scrollState)
    } else {
        baseModifier
    }

    Surface(
        modifier = scrollModifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    if (onLongPress != null || onPreviewClick != null) {
                        detectTapGestures(
                            onLongPress = {
                                onLongPress?.invoke()
                            },
                            onTap = {
                                onPreviewClick?.invoke()
                            }
                        )
                    }
                }
                .verticalScroll(rememberScrollState())
    ) {
            Text(
                text = code,
                color = effectiveTextColor,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip
            )
        }
    }
}