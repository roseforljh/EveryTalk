package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.MessageToolIds

internal fun supportedUserMessageToolIds(toolIds: List<String>): List<String> = toolIds
    .distinct()
    .filter { it == MessageToolIds.WEB_SEARCH || it == MessageToolIds.MCP }

internal fun resolveUserMessageContentEndPaddingDp(toolCount: Int): Float {
    if (toolCount <= 0) return 10f
    return 10f + toolCount * 16f + (toolCount - 1) * 5f + 6f
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
