package com.example.app1.ui.screens.BubbleMain.Main // 确保这是你正确的包名

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.app1.ui.screens.BubbleMain.ThreeDotsLoadingAnimation // 假设这是你 Animations 的正确路径
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS = 100L // 推理流式传输时自动滚动尝试间隔 (毫秒)
private const val REASONING_STREAM_SCROLL_ANIMATION_MS = 250 // 推理流式传输时滚动动画时长 (毫秒)
private const val REASONING_COMPLETE_SCROLL_ANIMATION_MS = 300 // 推理完成后滚动动画时长 (毫秒)

@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String, // 当前消息ID
    displayedReasoningText: String, // 当前显示的推理文本
    isReasoningStreaming: Boolean, // 推理内容是否正在流式传输
    isReasoningComplete: Boolean, // 推理内容是否已完成
    isManuallyExpanded: Boolean, // 推理部分是否被用户手动展开
    messageContentStarted: Boolean, // 消息主内容是否已开始
    messageIsError: Boolean, // 消息是否为错误消息
    fullReasoningTextProvider: () -> String?, // 提供完整推理文本的 lambda
    onToggleReasoning: () -> Unit, // 切换推理内容显示/隐藏的回调
    reasoningBubbleColor: Color, // 推理气泡背景色
    reasoningTextColor: Color, // 推理文本颜色
    reasoningToggleDotColor: Color, // 推理切换按钮上点的颜色
    modifier: Modifier = Modifier // Modifier
) {
    val focusManager = LocalFocusManager.current // 获取焦点管理器，用于清除焦点
    // 是否在切换按钮上显示三点加载动画
    val showThreeDotAnimationOnButton =
        isReasoningStreaming && !isReasoningComplete && !messageIsError

    Column(modifier = modifier) { // 整体纵向布局
        // 推理切换按钮区域
        Box(
            modifier = Modifier
                .align(Alignment.Start) // 左对齐
                .padding(start = 8.dp, bottom = 6.dp, top = 2.dp) // 内边距
        ) {
            Box( // 按钮的容器，用于实现点击效果和背景
                contentAlignment = Alignment.Center, // 内容居中
                modifier = Modifier
                    .height(38.dp) // 固定高度
                    .width(38.dp)  // 固定宽度
                    .clip(RoundedCornerShape(12.dp)) // 圆角裁剪
                    .background(Color.White) // 背景色
                    .clickable( // 点击事件
                        indication = null, // 无涟漪效果
                        interactionSource = remember { MutableInteractionSource() }, // 交互源
                        onClick = {
                            onToggleReasoning() // 执行切换回调
                            focusManager.clearFocus() // 清除当前焦点（例如，如果输入框有焦点）
                        }
                    )
            ) {
                if (showThreeDotAnimationOnButton) { // 如果显示加载动画
                    ThreeDotsLoadingAnimation( // 三点加载动画组件
                        dotColor = reasoningToggleDotColor,
                        dotSize = 6.dp,
                        spacing = 6.dp,
                        bounceHeight = 4.dp,
                        scaleAmount = 1.1f,
                        animationDuration = 350,
                        modifier = Modifier.offset(y = 0.dp)
                    )
                } else { // 否则显示静态点或展开/折叠指示器
                    // 根据是否手动展开，动态改变图标大小
                    val circleIconSize by animateDpAsState(
                        targetValue = if (isManuallyExpanded) 12.dp else 8.dp, // 展开时大，折叠时小
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                        label = "reasoningToggleIconSize_${currentMessageId}" // 动画标签，用于调试
                    )
                    Box( // 代表指示器的点
                        modifier = Modifier
                            .size(circleIconSize) // 动态大小
                            .background(reasoningToggleDotColor, CircleShape) // 背景色和圆形
                    )
                }
            }
        }

        // 推理内容区域，使用 AnimatedVisibility 实现展开/折叠动画
        AnimatedVisibility(
            // 可见条件：手动展开且有内容，或者正在流式传输且有内容（且内容已开始，无错误）
            visible = (isManuallyExpanded && displayedReasoningText.isNotBlank()) ||
                    (isReasoningStreaming && messageContentStarted && !messageIsError && displayedReasoningText.isNotBlank()),
            modifier = Modifier
                .align(Alignment.Start) // 左对齐
                .padding(start = 8.dp, bottom = 8.dp), // 内边距
            enter = fadeIn(tween(250)) + expandVertically(
                tween(250),
                expandFrom = Alignment.Top
            ), // 进入动画
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)) // 退出动画
        ) {
            Surface( // 推理内容的气泡表面
                shape = RoundedCornerShape(12.dp), // 圆角
                color = reasoningBubbleColor, // 背景色
                tonalElevation = 1.dp, // 色调海拔（用于阴影）
                shadowElevation = 0.dp, // 实际阴影（若有）
                modifier = Modifier
                    .heightIn(max = 200.dp) // 最大高度限制
                    .wrapContentWidth(align = Alignment.Start) // 包裹内容宽度，左对齐
            ) {
                val scrollState = rememberScrollState() // 内部垂直滚动状态
                val coroutineScope = rememberCoroutineScope() // 协程作用域，用于启动滚动动画
                var autoScrollJob by remember(currentMessageId) { mutableStateOf<Job?>(null) } // 自动滚动任务的 Job
                var userHasScrolledUp by remember(currentMessageId) { mutableStateOf(false) } // 标记用户是否已向上滚动

                // 自动滚动到末尾的 LaunchedEffect (流式传输时)
                LaunchedEffect(
                    currentMessageId,
                    isReasoningStreaming, // 当流式状态变化时
                    userHasScrolledUp,    // 当用户滚动状态变化时
                    messageContentStarted,// 当消息内容开始状态变化时
                    messageIsError       // 当消息错误状态变化时
                ) {
                    if (isReasoningStreaming && messageContentStarted && !userHasScrolledUp && !messageIsError) { // 条件：流式、内容开始、用户未上滚、无错误
                        autoScrollJob?.cancel() // 取消之前的自动滚动任务
                        autoScrollJob = coroutineScope.launch { // 启动新任务
                            try {
                                while (isActive) { // 协程活跃时持续执行
                                    delay(REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS) // 定期检查
                                    if (!isActive) break // 再次检查活跃状态
                                    val currentMaxValue = scrollState.maxValue // 获取当前可滚动的最大值
                                    if (scrollState.value < currentMaxValue) { // 如果还没滚到底部
                                        scrollState.animateScrollTo( // 动画滚动到底部
                                            currentMaxValue,
                                            animationSpec = tween(
                                                durationMillis = REASONING_STREAM_SCROLL_ANIMATION_MS,
                                                easing = LinearEasing
                                            )
                                        )
                                    }
                                }
                            } catch (e: CancellationException) { /* 预期中的取消异常 */
                            }
                        }
                    } else { // 不满足自动滚动条件
                        autoScrollJob?.cancel() // 取消自动滚动任务
                    }
                }

                // 推理完成后滚动到末尾的 LaunchedEffect
                LaunchedEffect(
                    currentMessageId,
                    isReasoningComplete,      // 当推理完成状态变化时
                    displayedReasoningText,   // 当显示的文本变化时 (确保是最终文本)
                    messageIsError            // 当消息错误状态变化时
                ) {
                    val fullText = fullReasoningTextProvider()?.trim() ?: "" // 获取完整的、去空格的推理文本
                    // 条件：推理完成、无错误、显示文本与完整文本一致、且文本非空
                    if (isReasoningComplete && !messageIsError && displayedReasoningText == fullText && fullText.isNotEmpty()) {
                        autoScrollJob?.cancel() // 取消可能正在进行的流式滚动任务
                        delay(100) // 短暂延迟，确保UI更新
                        if (isActive && scrollState.value < scrollState.maxValue) { // 如果协程活跃且未滚到底部
                            scrollState.animateScrollTo( // 动画滚动到底部
                                scrollState.maxValue,
                                tween(
                                    REASONING_COMPLETE_SCROLL_ANIMATION_MS,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                    }
                }

                // 嵌套滚动连接器，用于处理内部滚动与外部 LazyColumn 滚动的协调
                val nestedScrollConnection = remember(scrollState) { // 依赖 scrollState
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset, // 父滚动组件提供的可用滚动增量
                            source: NestedScrollSource // 滚动事件来源
                        ): Offset {
                            val deltaYFromParent = available.y // 从父级接收的垂直滚动增量

                            // 由于父级 LazyColumn 可能设置了 reverseLayout = true,
                            // 其传递的 deltaYFromParent 的符号可能与标准滚动约定相反。
                            // (标准：手指向下滑->内容向上滚->deltaY为负)
                            // (reverseLayout父级可能：手指向下滑->内容向上滚(逻辑上向列表头)->deltaY为正)
                            // 我们的内部 scrollState 遵循标准约定，所以需要反转父级deltaY。
                            val internalDeltaY = -deltaYFromParent // 转换为内部滚动方向的增量

                            if (source == NestedScrollSource.Drag) { // 如果是用户拖动事件
                                // 尝试向上滚动内部内容 (internalDeltaY < 0, 即用户手指向下滑动，想看上面内容)
                                if (internalDeltaY < 0) {
                                    if (scrollState.canScrollBackward) { // 如果内部内容还可以向上滚动
                                        val consumed =
                                            scrollState.dispatchRawDelta(internalDeltaY) // 实际消耗的滚动量
                                        if (consumed != 0f) { // 如果实际发生了滚动
                                            if (!userHasScrolledUp) userHasScrolledUp =
                                                true // 标记用户已向上滚动
                                        }
                                        // 返回给父级的消耗量，需要再次反转以匹配父级的坐标系
                                        return Offset(0f, -consumed)
                                    } else {
                                        // 内部内容已到达顶部，但用户仍尝试向上滚动。
                                        // **阻止滚动事件传递给父级**：
                                        // 我们声明消耗了父级提供的所有垂直滚动量 (available.y)，
                                        // 即使内部没有实际滚动。这可以防止外部列表滚动。
                                        return Offset(0f, deltaYFromParent)
                                    }
                                }
                                // 尝试向下滚动内部内容 (internalDeltaY > 0, 即用户手指向上滑动，想看下面内容)
                                else if (internalDeltaY > 0) {
                                    if (scrollState.canScrollForward) { // 如果内部内容还可以向下滚动
                                        val consumed =
                                            scrollState.dispatchRawDelta(internalDeltaY) // 实际消耗的滚动量
                                        if (consumed != 0f) { // 如果实际发生了滚动
                                            // 检查是否滚动到了内容的实际最底部
                                            // 允许一个微小的容差 (例如 1f) 来判断是否到达底部
                                            if (scrollState.value >= scrollState.maxValue - 1) {
                                                if (userHasScrolledUp) userHasScrolledUp =
                                                    false // 如果滚到底部，重置向上滚动标记
                                            }
                                            // 如果向下滚动但未到达底部，userHasScrolledUp 状态维持不变
                                        }
                                        // 返回给父级的消耗量，再次反转
                                        return Offset(0f, -consumed)
                                    } else {
                                        // 内部内容已到达底部，但用户仍尝试向下滚动。
                                        // **阻止滚动事件传递给父级**。
                                        if (userHasScrolledUp) userHasScrolledUp =
                                            false // 确保在底部时此标志为false
                                        return Offset(0f, deltaYFromParent)
                                    }
                                }
                            }
                            // 如果不是拖动事件，或者 internalDeltaY 为0 (无垂直滚动意图)
                            return Offset.Zero // 不消耗滚动
                        }
                    }
                }

                Column( // 包含可滚动文本的列
                    modifier = Modifier
                        .nestedScroll(nestedScrollConnection) // 应用嵌套滚动连接
                        .verticalScroll(scrollState) // 使其垂直可滚动，scrollState 本身按标准方向工作
                        .padding(horizontal = 15.dp, vertical = 10.dp) // 文本区域的内边距
                ) {
                    if (displayedReasoningText.isNotBlank()) { // 如果有推理文本
                        Text( // 显示推理文本
                            text = displayedReasoningText,
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}