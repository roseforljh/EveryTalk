package com.android.everytalk.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT
import com.android.everytalk.data.DataClass.MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import kotlin.math.roundToInt

private const val COMPRESSION_THRESHOLD_STEP = 5
private const val FLUID_MARKER_COUNT = 6

@Composable
internal fun AutoContextCompressionSection(
    enabled: Boolean,
    thresholdPercent: Int,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
) {
    val switchContentDescription = stringResource(
        com.android.everytalk.R.string.auto_compression_switch,
    )
    val safeThreshold = thresholdPercent.coerceIn(
        MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT,
        MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(com.android.everytalk.R.string.auto_compression_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(com.android.everytalk.R.string.auto_compression_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = appDialogSubtextColor(),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier
                    .scale(0.78f)
                    .semantics { contentDescription = switchContentDescription },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.surface,
                    checkedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }

        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(com.android.everytalk.R.string.auto_compression_threshold_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(com.android.everytalk.R.string.auto_compression_threshold_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = appDialogSubtextColor(),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "$safeThreshold%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                FluidThresholdSlider(
                    value = safeThreshold,
                    onValueChange = onThresholdChange,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FluidThresholdSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val thresholdContentDescription = stringResource(
        com.android.everytalk.R.string.auto_compression_threshold_content_description,
    )
    val minValue = MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT
    val maxValue = MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT
    val fraction = (value - minValue).toFloat() / (maxValue - minValue)
    val fluidStartColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
    val fluidEndColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val inactiveMarkerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val fluidTransition = rememberInfiniteTransition(label = "自动压缩流体")
    val fluidPhase by fluidTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
        ),
        label = "自动压缩流体相位",
    )

    Slider(
        value = value.toFloat(),
        onValueChange = { candidate ->
            val stepped = (candidate / COMPRESSION_THRESHOLD_STEP).roundToInt() *
                COMPRESSION_THRESHOLD_STEP
            onValueChange(stepped.coerceIn(minValue, maxValue))
        },
        valueRange = minValue.toFloat()..maxValue.toFloat(),
        steps = (maxValue - minValue) / COMPRESSION_THRESHOLD_STEP - 1,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = thresholdContentDescription },
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .shadow(
                        elevation = 7.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.24f),
                    )
                    .background(Color.White, CircleShape)
                    .border(1.dp, Color.Black.copy(alpha = 0.05f), CircleShape),
            )
        },
        track = {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            ) {
                val radius = size.height / 2f
                val activeWidth = size.width * fraction.coerceIn(0f, 1f)
                drawRoundRect(
                    color = inactiveTrackColor,
                    cornerRadius = CornerRadius(radius),
                )
                if (activeWidth > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(fluidStartColor, fluidEndColor),
                            startX = 0f,
                            endX = activeWidth.coerceAtLeast(1f),
                        ),
                        size = Size(activeWidth, size.height),
                        cornerRadius = CornerRadius(radius),
                    )
                }

                repeat(FLUID_MARKER_COUNT) { index ->
                    val markerFraction = (index + 0.5f) / FLUID_MARKER_COUNT
                    val markerX = size.width * markerFraction
                    drawCircle(
                        color = if (markerX <= activeWidth) {
                            Color.White.copy(alpha = 0.32f)
                        } else {
                            inactiveMarkerColor
                        },
                        radius = 2.dp.toPx(),
                        center = Offset(markerX, size.height / 2f),
                    )
                }

                val bubbleArea = (activeWidth - radius).coerceAtLeast(0f)
                if (bubbleArea > radius * 2f) {
                    repeat(3) { index ->
                        val travel = (fluidPhase + index / 3f) % 1f
                        val bubbleX = radius + travel * (bubbleArea - radius)
                        val verticalOffset = if (index % 2 == 0) -0.20f else 0.22f
                        drawCircle(
                            color = Color.White.copy(alpha = 0.13f),
                            radius = (2.5f + index * 0.7f).dp.toPx(),
                            center = Offset(
                                x = bubbleX,
                                y = size.height * (0.5f + verticalOffset),
                            ),
                        )
                    }
                }
            }
        },
    )
}
