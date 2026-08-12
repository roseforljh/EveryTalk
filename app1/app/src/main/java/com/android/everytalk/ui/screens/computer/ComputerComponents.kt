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
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.computerStatusLabelRes
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor

/**
 * 服务器页面使用配置页同款黑白控件色，阻断全局 Material 紫色主色。
 * 只覆盖服务器页面及其弹窗，错误色和其他语义色继续沿用应用主题。
 */
@Composable
fun ComputerNeutralTheme(content: @Composable () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val controlColor = appDialogContentColor()
    val onControlColor = appDialogContainerColor()
    MaterialTheme(
        colorScheme = colorScheme.copy(
            primary = controlColor,
            onPrimary = onControlColor,
            primaryContainer = controlColor,
            onPrimaryContainer = onControlColor,
            secondary = controlColor,
            onSecondary = onControlColor,
            secondaryContainer = controlColor,
            onSecondaryContainer = onControlColor,
            tertiary = controlColor,
            onTertiary = onControlColor,
            tertiaryContainer = controlColor,
            onTertiaryContainer = onControlColor,
            inversePrimary = controlColor,
            surfaceTint = Color.Transparent,
        ),
        content = content,
    )
}

@Composable
internal fun ComputerCard(
    computer: Computer,
    accentColor: Color,
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
                tint = accentColor,
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
                    color = if (isReady) accentColor else statusColor(computer.status),
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

/**
 * 服务器 ID 在创建时由 UUID 随机生成，因此由 ID 选择的初始色也是随机的。
 * 按创建顺序处理颜色冲突，保证色板容量内同一列表没有重复颜色，刷新页面也不会跳色。
 */
internal fun computerCardAccentColorIndexes(computers: List<Computer>): Map<String, Int> {
    val usedColorIndexes = mutableSetOf<Int>()
    return computers
        .sortedWith(compareBy<Computer> { it.createdAt }.thenBy { it.id })
        .associate { computer ->
            val initialIndex = Math.floorMod(computer.id.hashCode(), COMPUTER_CARD_ACCENT_COLOR_COUNT)
            val colorIndex = (0 until COMPUTER_CARD_ACCENT_COLOR_COUNT)
                .map { offset -> (initialIndex + offset) % COMPUTER_CARD_ACCENT_COLOR_COUNT }
                .firstOrNull(usedColorIndexes::add)
                ?: initialIndex
            computer.id to colorIndex
        }
}

internal fun computerCardAccentColors(computers: List<Computer>): Map<String, Color> =
    computerCardAccentColorIndexes(computers).mapValues { (_, colorIndex) ->
        ComputerCardAccentPalette[colorIndex]
    }

internal const val COMPUTER_CARD_ACCENT_COLOR_COUNT = 12

/** 避开服务器页面已经明确排除的紫色，并保持亮色、暗色主题下都清晰。 */
internal val ComputerCardAccentPalette = listOf(
    Color(0xFF1976D2),
    Color(0xFF0288D1),
    Color(0xFF0097A7),
    Color(0xFF00897B),
    Color(0xFF388E3C),
    Color(0xFF689F38),
    Color(0xFFF9A825),
    Color(0xFFF57C00),
    Color(0xFFEF6C00),
    Color(0xFFD32F2F),
    Color(0xFFC2185B),
    Color(0xFF546E7A),
)

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
