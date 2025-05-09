package com.example.app1.ui.screens.BubbleMain // 替换为你的实际包名基础 + .ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三点加载动画可组合项。
 * @param modifier Modifier。
 * @param dotSize 点的大小。
 * @param dotColor 点的颜色。
 * @param spacing 点之间的间距。
 * @param bounceHeight 点跳动的高度。
 * @param scaleAmount 点缩放的幅度。
 * @param animationDuration 单个动画周期（跳起落下）的时长。
 */
@Composable
fun ThreeDotsLoadingAnimation(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    spacing: Dp = 10.dp,
    bounceHeight: Dp = 10.dp, // 点向上跳动的高度
    scaleAmount: Float = 1.25f, // 点在最高点时的缩放倍数
    animationDuration: Int = 450 // 单个点完成一次跳动动画（上、下）所需的时间
) {
    // 无限循环过渡，用于驱动动画
    val infiniteTransition = rememberInfiniteTransition(label = "three_dots_loader_bubble_${dotColor.value.toULong()}")

    // 辅助函数，为单个点创建Y轴偏移和缩放的动画值
    @Composable
    fun animateDot(delayMillis: Int): Pair<Float, Float> {
        val key = "dot_anim_bubble_${dotColor.value.toULong()}_$delayMillis" // 确保每个动画key唯一

        // Y轴偏移（跳动效果）
        val yOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0f, // targetValue与initialValue相同，因为动画在keyframes中定义
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration * 3 // 总动画时长是单个周期的3倍，以错开三个点
                    // 定义关键帧：时间点 -> 值 with 缓动器
                    0f at (animationDuration * 0) + delayMillis with LinearEasing // 初始状态
                    -bounceHeight.value at (animationDuration * 1) + delayMillis with LinearEasing // 跳到最高点
                    0f at (animationDuration * 2) + delayMillis with LinearEasing // 回到原位
                    0f at (animationDuration * 3) + delayMillis with LinearEasing // 保持，等待下一轮
                },
                repeatMode = RepeatMode.Restart // 重复模式
            ),
            label = "${key}_yOffset_bubble"
        )

        // 缩放（跳动时变大效果）
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration * 3
                    1f at (animationDuration * 0) + delayMillis with LinearEasing
                    scaleAmount at (animationDuration * 1) + delayMillis with LinearEasing
                    1f at (animationDuration * 2) + delayMillis with LinearEasing
                    1f at (animationDuration * 3) + delayMillis with LinearEasing
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "${key}_scale_bubble"
        )
        return Pair(yOffset, scale)
    }

    // 为三个点创建动画值，通过delayMillis错开它们的动画相位
    val dotsAnim = listOf(
        animateDot(0),                                  // 第一个点，无延迟
        animateDot(animationDuration / 3),              // 第二个点，延迟1/3周期
        animateDot(animationDuration * 2 / 3)           // 第三个点，延迟2/3周期
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically, // 垂直居中对齐点
        horizontalArrangement = Arrangement.Center    // 水平居中排列点
    ) {
        dotsAnim.forEachIndexed { index, (yOffset, scale) ->
            Box(
                modifier = Modifier
                    .graphicsLayer { // 使用graphicsLayer进行高效变换
                        translationY = yOffset // 应用Y轴位移
                        scaleX = scale       // 应用X轴缩放
                        scaleY = scale       // 应用Y轴缩放
                    }
                    .size(dotSize) // 点的大小
                    .background(dotColor.copy(alpha = 1f), shape = CircleShape) // 点的背景和形状
            )
            if (index < dotsAnim.lastIndex) { // 在点之间添加间距，最后一个点后不加
                Spacer(Modifier.width(spacing))
            }
        }
    }
}