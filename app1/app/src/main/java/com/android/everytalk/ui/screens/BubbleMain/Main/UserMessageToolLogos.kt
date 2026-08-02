package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.MessageToolIds

internal fun supportedUserMessageToolIds(toolIds: List<String>): List<String> = toolIds
    .distinct()
    .filter { it == MessageToolIds.WEB_SEARCH || it == MessageToolIds.MCP }

@Composable
internal fun UserMessageInlineContent(
    enabledToolIds: List<String>,
    modifier: Modifier = Modifier,
    textContent: @Composable () -> Unit,
) {
    val supportedToolIds = supportedUserMessageToolIds(enabledToolIds)
    if (supportedToolIds.isEmpty()) {
        Box(modifier = modifier) { textContent() }
        return
    }

    Layout(
        modifier = modifier.testTag("user-message-inline-content"),
        content = {
            Box { textContent() }
            UserMessageToolLogos(enabledToolIds = supportedToolIds)
        },
    ) { measurables, constraints ->
        val logoPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        val spacingPx = 6.dp.roundToPx()
        val textPlaceable = measurables[0].measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = (constraints.maxWidth - logoPlaceable.width - spacingPx)
                    .coerceAtLeast(0),
            ),
        )
        val actualSpacingPx = if (textPlaceable.width > 0) spacingPx else 0
        val width = (textPlaceable.width + actualSpacingPx + logoPlaceable.width)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(textPlaceable.height, logoPlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val logoBaselineLiftPx = 2.dp.roundToPx()

        layout(width, height) {
            textPlaceable.placeRelative(0, 0)
            logoPlaceable.placeRelative(
                x = textPlaceable.width + actualSpacingPx,
                y = (height - logoPlaceable.height - logoBaselineLiftPx).coerceAtLeast(0),
            )
        }
    }
}

@Composable
internal fun UserMessageToolLogos(
    enabledToolIds: List<String>,
    modifier: Modifier = Modifier,
) {
    val supportedToolIds = supportedUserMessageToolIds(enabledToolIds)
    if (supportedToolIds.isEmpty()) return

    Row(
        modifier = modifier.testTag("user-message-tool-logos"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        supportedToolIds.forEach { toolId ->
            when (toolId) {
                MessageToolIds.WEB_SEARCH -> Icon(
                    painter = painterResource(R.drawable.ic_globe),
                    contentDescription = "联网搜索工具",
                    tint = Color(0xFF66B5FF),
                    modifier = Modifier
                        .testTag("user-message-tool-logo-web-search")
                        .size(16.dp),
                )

                MessageToolIds.MCP -> Icon(
                    painter = painterResource(R.drawable.ic_hammer),
                    contentDescription = "MCP工具",
                    tint = Color(0xFFFF6B00),
                    modifier = Modifier
                        .testTag("user-message-tool-logo-mcp")
                        .size(16.dp),
                )
            }
        }
    }
}
