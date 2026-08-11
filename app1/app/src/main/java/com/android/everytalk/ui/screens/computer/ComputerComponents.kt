package com.android.everytalk.ui.screens.computer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatAgentColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.computerStatusLabelRes

@Composable
internal fun ComputerCard(
    computer: Computer,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    val isReady = computer.status == ComputerStatus.READY
    val mode = stringResource(
        if (computer.runMode == ComputerRunMode.CONTAINER) {
            R.string.computer_mode_container
        } else {
            R.string.computer_mode_direct
        },
    )
    val status = stringResource(computerStatusLabelRes(computer.status))
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_gpt_terminal),
                contentDescription = null,
                tint = if (isReady) ChatAgentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = computer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${computer.username}@${computer.host}:${computer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.computer_card_mode, mode, status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isReady) ChatAgentColor else statusColor(computer.status),
                )
                computer.capabilities?.let { capabilities ->
                    val resources = buildList {
                        capabilities.cpuCount?.let { add("${it} CPU") }
                        capabilities.memoryBytes?.let { add(formatComputerBytes(it)) }
                        capabilities.diskAvailableBytes?.let {
                            add(stringResource(R.string.computer_card_disk_free, formatComputerBytes(it)))
                        }
                    }.joinToString(" · ")
                    if (resources.isNotEmpty()) {
                        Text(
                            text = resources,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.computer_card_refresh),
                )
            }
        }
    }
}

private fun statusColor(status: ComputerStatus): Color = when (status) {
    ComputerStatus.HOST_KEY_CHANGED, ComputerStatus.ACTION_REQUIRED, ComputerStatus.ERROR -> Color(0xFFD32F2F)
    ComputerStatus.CONFIGURATION_REQUIRED -> Color(0xFFF57C00)
    else -> Color(0xFF757575)
}

internal fun formatComputerBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    val decimals = if (value >= 10.0) 0 else 1
    return "%.${decimals}f %s".format(java.util.Locale.US, value, units[unitIndex])
}

/** 敏感凭据悬浮卡片显示期间禁止系统截图和最近任务缩略图。 */
@Composable
internal fun ComputerSecureWindowEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (enabled) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
