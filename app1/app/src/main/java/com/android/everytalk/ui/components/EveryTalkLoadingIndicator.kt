package com.android.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun everyTalkLoadingIndicatorColor(isDarkTheme: Boolean): Color =
    if (isDarkTheme) Color.White else Color.Black

@Composable
fun EveryTalkLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    strokeWidth: Dp = 3.dp,
    contentDescription: String,
) {
    CircularProgressIndicator(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        color = everyTalkLoadingIndicatorColor(isSystemInDarkTheme()),
        trackColor = Color.Transparent,
        strokeWidth = strokeWidth,
        strokeCap = StrokeCap.Round,
    )
}
