package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ContextUsageDataSource
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.TokenUsageSource
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ContextUsageGreen = Color(0xFF22C55E)
private val ContextUsageAmber = Color(0xFFF59E0B)
private val ContextUsageRed = Color(0xFFEF4444)
private val InputUsageColor = Color(0xFF10A37F)
private val OutputUsageColor = Color(0xFF6D5BD0)

internal data class AiContextUsageSummary(
    val inputTokens: Long,
    val outputTokens: Long,
    val turnTotalTokens: Long,
    val contextWindowTokens: Long,
    val currentContextTokens: Long,
    val conversationTotalTokens: Long,
    val isMeasured: Boolean,
) {
    val fraction: Float
        get() = contextUsageFraction(currentContextTokens, contextWindowTokens)
}

internal fun aiContextUsageSummary(
    message: Message,
    conversationTotalTokens: Long,
    liveContextWindowTokens: Long? = null,
): AiContextUsageSummary? {
    val usage = message.tokenUsage
    val snapshot = message.contextUsageSnapshot
    if (usage == null && snapshot == null) return null

    val input = (usage?.inputTokens ?: snapshot?.measuredInputTokens
        ?: snapshot?.estimatedInputTokens ?: 0L).coerceAtLeast(0L)
    val output = (usage?.outputTokens ?: snapshot?.measuredOutputTokens ?: 0L).coerceAtLeast(0L)
    val turnTotal = (usage?.totalTokens ?: safeTokenSum(input, output)).coerceAtLeast(0L)
    val currentContext = (snapshot?.displayedUsedTokens ?: turnTotal).coerceAtLeast(0L)
    val isMeasured = snapshot?.dataSource == ContextUsageDataSource.MEASURED ||
        usage?.source?.let { it != TokenUsageSource.ESTIMATED } == true

    val contextWindowTokens = liveContextWindowTokens
        ?.takeIf { it > 0L }
        ?: snapshot?.contextWindowTokens?.coerceAtLeast(0L)
        ?: 0L

    return AiContextUsageSummary(
        inputTokens = input,
        outputTokens = output,
        turnTotalTokens = turnTotal,
        contextWindowTokens = contextWindowTokens,
        currentContextTokens = currentContext,
        conversationTotalTokens = conversationTotalTokens.coerceAtLeast(0L),
        isMeasured = isMeasured,
    )
}

internal fun resolveLiveContextWindowTokens(
    message: Message,
    configs: List<ApiConfig>,
    activeConfigId: String? = null,
): Long {
    val snapshot = message.contextUsageSnapshot
    val configId = snapshot?.configId
    val liveConfig = activeConfigId
        ?.let { id -> configs.firstOrNull { it.id == id } }
        ?: configId
        ?.let { id -> configs.firstOrNull { it.id == id } }
        ?: configs.firstOrNull { config ->
            config.model == message.modelName && config.provider == message.providerName
        }
    return liveConfig
        ?.modelParameters
        ?.maxContextTokens
        ?.toLong()
        ?.takeIf { it > 0L }
        ?: snapshot?.contextWindowTokens?.coerceAtLeast(0L)
        ?: 0L
}

internal fun totalConversationTokenUsage(messages: Iterable<Message>): Long {
    val countedMessageIds = mutableSetOf<String>()
    return messages.fold(0L) { total, message ->
        if (message.sender != Sender.AI || !countedMessageIds.add(message.id)) {
            total
        } else {
            val usage = message.tokenUsage
            val snapshot = message.contextUsageSnapshot
            val input = usage?.inputTokens ?: snapshot?.measuredInputTokens
                ?: snapshot?.estimatedInputTokens ?: 0L
            val output = usage?.outputTokens ?: snapshot?.measuredOutputTokens ?: 0L
            val turnTotal = (usage?.totalTokens ?: safeTokenSum(input, output)).coerceAtLeast(0L)
            safeTokenSum(total, turnTotal)
        }
    }
}

internal fun totalMeasuredConversationTokenUsage(messages: Iterable<Message>): Long? {
    val latestMessagesById = linkedMapOf<String, Message>()
    messages.forEach { message ->
        if (message.sender == Sender.AI) latestMessagesById[message.id] = message
    }
    if (latestMessagesById.isEmpty()) return null

    var total = 0L
    latestMessagesById.values.forEach { message ->
        val usage = message.tokenUsage ?: return null
        if (usage.source == TokenUsageSource.ESTIMATED) return null
        val turnTotal = usage.totalTokens ?: when {
            usage.inputTokens == null -> usage.outputTokens
            usage.outputTokens == null -> usage.inputTokens
            else -> safeTokenSum(usage.inputTokens, usage.outputTokens)
        } ?: return null
        total = safeTokenSum(total, turnTotal.coerceAtLeast(0L))
    }
    return total
}

private fun safeTokenSum(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

internal fun contextUsageFraction(usedTokens: Long, contextWindowTokens: Long): Float {
    if (contextWindowTokens <= 0L) return 0f
    return (usedTokens.coerceAtLeast(0L).toDouble() / contextWindowTokens.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
}

internal fun contextUsageColor(fraction: Float): Color {
    val progress = fraction.coerceIn(0f, 1f)
    return if (progress <= 0.5f) {
        lerp(ContextUsageGreen, ContextUsageAmber, progress * 2f)
    } else {
        lerp(ContextUsageAmber, ContextUsageRed, (progress - 0.5f) * 2f)
    }
}

internal fun formatUsageTokens(tokens: Long): String =
    String.format(Locale.US, "%,d", tokens.coerceAtLeast(0L))

@Composable
internal fun AiContextUsageButton(
    message: Message,
    conversationTotalTokens: Long,
    liveContextWindowTokens: Long? = null,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = remember(
        message.tokenUsage,
        message.contextUsageSnapshot,
        conversationTotalTokens,
        liveContextWindowTokens,
    ) {
        aiContextUsageSummary(
            message = message,
            conversationTotalTokens = conversationTotalTokens,
            liveContextWindowTokens = liveContextWindowTokens,
        )
    }
    val popupOffset = with(LocalDensity.current) {
        IntOffset(x = (-72).dp.roundToPx(), y = 0)
    }
    Box {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(36.dp)
                .semantics {
                    contentDescription = summary?.let {
                        "查看上下文用量 ${(it.fraction * 100).roundToInt()}%"
                    } ?: "查看上下文用量"
                },
        ) {
            ContextUsageRing(
                fraction = summary?.fraction ?: 0f,
                hasData = summary != null,
            )
        }
        AiMessageFloatingPopupCard(
            expanded = expanded,
            onDismiss = onDismiss,
            minWidth = 0.dp,
            modifier = Modifier.width(284.dp),
            offset = popupOffset,
            transformOrigin = TransformOrigin(0.25f, 1f),
        ) {
            AiContextUsagePopupContent(summary)
        }
    }
}

@Composable
private fun ContextUsageRing(
    fraction: Float,
    hasData: Boolean,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val progressColor = if (hasData) {
        contextUsageColor(fraction)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    }
    Canvas(modifier = Modifier.size(17.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = trackColor, style = stroke)
        if (hasData && fraction > 0f) {
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = stroke,
            )
        }
    }
}

@Composable
private fun AiContextUsagePopupContent(summary: AiContextUsageSummary?) {
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "上下文用量",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            summary?.let {
                UsageSourceBadge(if (it.isMeasured) "实测" else "估算")
            }
        }
        if (summary == null) {
            Text(
                text = "当前消息暂无用量数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "本轮会话消耗",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DirectionalUsage(
                iconRes = R.drawable.ic_arrow_up,
                label = "输入",
                tokens = summary.inputTokens,
                color = InputUsageColor,
                modifier = Modifier.weight(1f),
            )
            DirectionalUsage(
                iconRes = R.drawable.ic_gpt_arrow_down,
                label = "输出",
                tokens = summary.outputTokens,
                color = OutputUsageColor,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 15.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            UsageTotal(
                label = "目前总消耗",
                value = formatUsageTokens(summary.conversationTotalTokens),
                color = contextUsageColor(summary.fraction),
                modifier = Modifier.weight(1f),
            )
            UsageTotal(
                label = "总上下文",
                value = if (summary.contextWindowTokens > 0L) {
                    formatUsageTokens(summary.contextWindowTokens)
                } else {
                    "未知"
                },
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        UsageProgressBar(summary.fraction)
    }
}

@Composable
private fun UsageSourceBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
        )
    }
}

@Composable
private fun DirectionalUsage(
    iconRes: Int,
    label: String,
    tokens: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = "$label tokens",
                tint = color,
                modifier = Modifier.size(15.dp),
            )
        }
        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Text(
                text = formatUsageTokens(tokens),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun UsageTotal(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun UsageProgressBar(fraction: Float) {
    val progress = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(4.dp)
                .clip(CircleShape)
                .background(contextUsageColor(progress)),
        )
    }
}

@Composable
internal fun AiMessageFloatingPopupCard(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 200.dp,
    offset: IntOffset = IntOffset.Zero,
    transformOrigin: TransformOrigin = TransformOrigin(0f, 1f),
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scale = remember { Animatable(0.8f) }
    val alpha = remember { Animatable(0f) }
    val emphasizedDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val decelerate = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    LaunchedEffect(expanded) {
        if (expanded) {
            visible = true
            scale.snapTo(0.8f)
            alpha.snapTo(0f)
            coroutineScope {
                launch { scale.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alpha.animateTo(1f, tween(30, easing = decelerate)) }
            }
        } else if (visible) {
            coroutineScope {
                launch { alpha.animateTo(0f, tween(75, easing = decelerate)) }
                launch {
                    delay(74)
                    scale.snapTo(0.8f)
                }
            }
            visible = false
        }
    }

    if (!visible) return
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) Color(0xFF212121) else Color.White
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color(0xFF0D0D0D).copy(alpha = 0.05f)
    }

    Popup(
        alignment = Alignment.BottomStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = minWidth)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                    this.transformOrigin = transformOrigin
                }
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = cardBackground,
            content = content,
        )
    }
}
