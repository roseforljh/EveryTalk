package com.android.everytalk.ui.screens.MainScreen.chat.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI

@Composable
fun VoiceWaveAnimation(
    isRecording: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    currentVolume: Float = 0f
) {
    // 形变振幅：用于波形的不规则形变（保持原有逻辑）
    var amplitudeTarget by remember { mutableFloatStateOf(0.5f) }
    val amplitude by animateFloatAsState(
        targetValue = amplitudeTarget,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "amplitudeSmoothing"
    )
    
    // 连续相位：基于帧时间推进，不重启，避免周期性"卡顿"
    var phase by remember { mutableFloatStateOf(0f) }
    
    // 🎤 音量缩放：根据实时音量大小控制整体缩放（新增）
    var volumeScaleTarget by remember { mutableFloatStateOf(1f) }
    val volumeScale by animateFloatAsState(
        targetValue = volumeScaleTarget,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "volumeScaleSmoothing"
    )
    
    // 🔍 使用 rememberUpdatedState 获取最新的 currentVolume 值
    val latestVolume by rememberUpdatedState(currentVolume)
    
    // 连续帧驱动：形变振幅 + 音量缩放
    LaunchedEffect(isRecording) {
        if (isRecording) {
            var last = withFrameNanos { it }
            while (isRecording) {  // 改为检查 isRecording 状态
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f // s
                last = now

                // 形变振幅：叠加两个缓慢正弦作为包络（保持原有逻辑）
                val tSec = now / 1_000_000_000f
                val a = kotlin.math.sin(2f * PI.toFloat() * (tSec / 6f))
                val b = kotlin.math.sin(2f * PI.toFloat() * (tSec / 7.8f))
                val env = ((a + b) * 0.5f * 0.5f) + 0.5f // 归一到 0..1 并压缩
                val newAmplitudeTarget = 0.55f + 0.45f * env
                val newVolumeScaleTarget = 1f + latestVolume * 0.3f
                val omega = 0.8f // rad/s
                Snapshot.withMutableSnapshot {
                    amplitudeTarget = newAmplitudeTarget
                    volumeScaleTarget = newVolumeScaleTarget
                    phase += omega * dt
                }
            }
        }
        
        // 退场动画：无论如何都执行（录音停止后）
        if (!isRecording) {
            val startAmplitude = amplitudeTarget
            val startVolumeScale = volumeScaleTarget
            val duration = 0.5f
            var acc = 0f
            var last = withFrameNanos { it }
            while (acc < duration) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                acc += dt
                // 使用缓动函数使过渡更自然
                val rawProgress = (acc / duration).coerceIn(0f, 1f)
                val easedProgress = rawProgress * rawProgress * (3f - 2f * rawProgress) // smoothstep
                Snapshot.withMutableSnapshot {
                    amplitudeTarget = startAmplitude + (0.5f - startAmplitude) * easedProgress
                    volumeScaleTarget = startVolumeScale + (1f - startVolumeScale) * easedProgress
                }
            }
        }
    }
    
    // 基础大小和最终缩放：整体大小 = 基础大小 × 音量缩放
    val baseSize = 120.dp
    val finalScale = if (isRecording) volumeScale else 1f
    
    Canvas(
        modifier = modifier.size(baseSize * 1.5f)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = (baseSize.toPx() / 2) * finalScale
        
        if (isRecording) {
            // 绘制不规则波形圆
            drawIrregularCircle(
                centerX = centerX,
                centerY = centerY,
                radius = radius,
                color = color,
                phase = phase,
                amplitude = amplitude
            )
        } else {
            // 绘制普通圆形
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(centerX, centerY)
            )
        }
    }
}

fun DrawScope.drawIrregularCircle(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    phase: Float,
    amplitude: Float
) {
    val path = Path()
    // 更高采样，适配高刷新，边缘更丝滑
    val points = 240
    val angleStep = (2 * PI / points).toFloat()

    var angle = 0f
    // 使用 phase 作为持续旋转项，避免任何周期重启
    val p = phase
    for (i in 0 until points) {
        // 更温和的多波叠加，形变不过分，同时保持“有生命力”
        val wave1 = kotlin.math.sin(angle * 2f + p * 1.4f) * amplitude * 0.10f
        val wave2 = kotlin.math.sin(angle * 3f - p * 1.0f) * amplitude * 0.07f
        val wave3 = kotlin.math.cos(angle * 4f + p * 1.2f) * amplitude * 0.05f
        val distortion = (wave1 + wave2 + wave3) * radius
        val currentRadius = radius + distortion

        val x = centerX + currentRadius * kotlin.math.cos(angle)
        val y = centerY + currentRadius * kotlin.math.sin(angle)

        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }

        angle += angleStep
    }

    path.close()
    drawPath(path, color)
}
