package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun everyTalkLoadingIndicatorColor(isDarkTheme: Boolean): Color =
    if (isDarkTheme) Color.White else Color.Black

internal fun everyTalkLoadingElapsedText(elapsedMs: Long): String =
    "${elapsedMs.coerceAtLeast(0L) / 1000L}s"

@Composable
internal fun rememberEveryTalkLoadingElapsedText(
    isLoading: Boolean,
    key: Any? = Unit,
): String? {
    val startedAt = remember(isLoading, key) {
        if (isLoading) SystemClock.elapsedRealtime() else 0L
    }
    var elapsedMs by remember(isLoading, key) { mutableLongStateOf(0L) }

    LaunchedEffect(isLoading, key) {
        if (!isLoading) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (isActive) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(1000L)
        }
    }

    return if (isLoading) everyTalkLoadingElapsedText(elapsedMs) else null
}

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

@Composable
fun EveryTalkTimedLoadingStatus(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String = text,
) {
    val elapsedText = rememberEveryTalkLoadingElapsedText(
        isLoading = true,
        key = text,
    ) ?: "0s"

    Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EveryTalkLoadingIndicator(
            size = size,
            strokeWidth = strokeWidth,
            contentDescription = contentDescription,
        )
        Text(
            text = text,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "·",
            style = textStyle,
            color = textColor.copy(alpha = 0.72f),
            maxLines = 1,
        )
        AnimatedContent(
            targetState = elapsedText,
            label = "EveryTalkLoadingElapsedAnimation",
        ) { value ->
            Text(
                text = value,
                style = textStyle,
                color = textColor.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}
