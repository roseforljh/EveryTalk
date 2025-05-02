package com.example.app1.ui.components

import androidx.compose.animation.AnimatedVisibility // 导入 AnimatedVisibility
import androidx.compose.animation.animateContentSize // 导入 animateContentSize
import androidx.compose.animation.core.Animatable // 导入 Animatable
import androidx.compose.animation.core.infiniteRepeatable // 导入 infiniteRepeatable
import androidx.compose.animation.core.keyframes // 导入 keyframes
import androidx.compose.animation.core.spring // 导入 spring (如果 animateContentSize 使用 spring)
import androidx.compose.animation.core.tween // 导入 tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable // 导入 clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape // 导入 CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer // 导入 SelectionContainer
import androidx.compose.material.icons.Icons // 导入 Icons
import androidx.compose.material.icons.filled.ExpandLess // 导入 ExpandLess
import androidx.compose.material.icons.filled.ExpandMore // 导入 ExpandMore
import androidx.compose.material3.Card // 导入 Card
import androidx.compose.material3.CardDefaults // 导入 CardDefaults
import androidx.compose.material3.Icon // 导入 Icon
import androidx.compose.material3.MaterialTheme // 导入 MaterialTheme
import androidx.compose.material3.Surface // 导入 Surface
import androidx.compose.material3.Text // 导入 Text
import androidx.compose.runtime.* // 导入所有 runtime 相关的
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale // 导入 scale 修饰符
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration // 导入 LocalConfiguration
import androidx.compose.ui.platform.LocalContext // 导入 LocalContext (如果需要)
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app1.data.models.Message // 导入 Message
import com.example.app1.data.models.Sender // 导入 Sender
import kotlinx.coroutines.delay // 导入 delay
import kotlinx.coroutines.launch // 导入 launch

@Composable
fun MessageBubble(
    message: Message,
    isExpanded: Boolean, // 接收 Reasoning 展开状态
    onToggleExpand: () -> Unit, // 接收切换 Reasoning 展开状态的回调
    isApiCalling: Boolean, // 整体 API 调用状态
    currentStreamingAiMessageId: String? // 当前正在流式处理的消息 ID
) {
    // 根据发送者确定对齐方式
    val alignment =
        if (message.sender == Sender.User) Alignment.CenterEnd else Alignment.CenterStart

    // 根据发送者和错误状态确定背景颜色 (只用于非加载状态的气泡)
    val bubbleBackgroundColor = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer // 错误时使用错误背景色
        message.sender == Sender.User -> MaterialTheme.colorScheme.primaryContainer // 用户消息背景色
        // AI 消息非加载状态的背景色
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    // 根据发送者和错误状态确定文本颜色 (也用于点点点动画颜色)
    val contentColor = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer // 错误时使用错误文本颜色
        message.sender == Sender.User -> MaterialTheme.colorScheme.onPrimaryContainer // 用户消息文本颜色
        // AI 消息文本颜色 (也用于加载点颜色)
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    // 根据发送者确定气泡的圆角形状
    val bubbleShape = if (message.sender == Sender.User) RoundedCornerShape(
        topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp
    ) else RoundedCornerShape(
        topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp
    )

    // 限制气泡的最大宽度 (只用于非加载状态的气泡)
    val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f

    // 判断当前消息是否是正在进行流式输出的消息，并且内容尚未开始
    val isThinkingAndContentNotStarted =
        message.sender == Sender.AI && isApiCalling && message.id == currentStreamingAiMessageId && !message.contentStarted && message.text == "..." && message.reasoning.isNullOrBlank()


    // 外层 Box 用于控制对齐方式和左右内边距
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                // 根据发送者调整左右 Padding
                start = if (message.sender == Sender.User) (LocalConfiguration.current.screenWidthDp.dp * 0.15f).coerceAtLeast(
                    16.dp
                ) else 8.dp,
                end = if (message.sender == Sender.User) 8.dp else (LocalConfiguration.current.screenWidthDp.dp * 0.15f).coerceAtLeast(
                    16.dp
                ),
                top = 2.dp, bottom = 2.dp // 上下小间距
            ),
        contentAlignment = alignment // 应用对齐方式
    ) {
        // Column 用于包裹内容，保持结构一致性
        Column(horizontalAlignment = if (message.sender == Sender.User) Alignment.End else Alignment.Start) {

            // *** 关键修改：将 AI 消息的气泡内容逻辑集中处理 ***
            if (message.sender == Sender.AI) {
                // AI 消息总是包裹在 Surface 中，即使是思考状态
                Surface(
                    modifier = Modifier.widthIn(max = maxBubbleWidth), // 应用最大宽度限制
                    shape = bubbleShape, // 应用气泡形状
                    color = bubbleBackgroundColor, // 应用背景色
                    tonalElevation = 1.dp // 添加一点阴影
                ) {
                    // 内层 Column 用于排列内容，并应用 animateContentSize
                    Column(
                        modifier = Modifier
                            .animateContentSize(animationSpec = spring(stiffness = 150f)) // 添加内容尺寸动画
                            .padding(horizontal = 12.dp, vertical = 8.dp) // 内容的内边距
                    ) {
                        // --- 思考过程区域 (放在文本上方) ---
                        // 仅在 AI 消息且有 reasoning 或正在思考时显示思考过程部分
                        if (!message.reasoning.isNullOrBlank() || isThinkingAndContentNotStarted) {
                            Column {
                                // 思考过程 Header 或 思考中... 文本
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            // 如果有 reasoning 且不是思考中状态，则 Header 可点击
                                            if (!message.reasoning.isNullOrBlank() && !isThinkingAndContentNotStarted) {
                                                Modifier.clickable { onToggleExpand() }.padding(vertical = 4.dp)
                                            } else {
                                                // 思考中状态或没有 reasoning，Header 不可点击
                                                Modifier.padding(vertical = 4.dp)
                                            }
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // 显示 "思考过程" 文本
                                    Text(
                                        text = if (isThinkingAndContentNotStarted) "思考中..." else if (isExpanded) "思考过程 (点击折叠)" else "思考过程 (点击展开)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), // 加粗
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f), // 使用 onSecondaryContainer 并降低透明度
                                        modifier = Modifier.weight(1f).padding(end = 8.dp) // 文本占据大部分空间
                                    )

                                    // 显示加载动画或展开/折叠图标
                                    if (isThinkingAndContentNotStarted) {
                                        // 思考中状态，显示波浪点动画
                                        WavyLoadingDots(
                                            dotColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f), // 动画颜色与文本颜色一致
                                            dotCount = 3,
                                            dotSpacing = 4.dp
                                        )
                                    } else if (!message.reasoning.isNullOrBlank()) {
                                        // 有 reasoning 且不是思考中状态，显示展开/折叠图标
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = if (isExpanded) "折叠思考过程" else "展开思考过程",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // 思考过程文本 (仅在有 reasoning 且展开时可见)
                                AnimatedVisibility(visible = !message.reasoning.isNullOrBlank() && isExpanded) {
                                    SelectionContainer { // 使 Reasoning 文本可选择
                                        Text(
                                            text = message.reasoning!!, // !! 因为前面已经判断不为空
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
                                            fontSize = 14.sp, // 思考过程文本可以小一点
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }

                                // 如果正在思考并且内容未开始，在思考中... 和文本之间加个间隔
                                if (isThinkingAndContentNotStarted) {
                                    Spacer(modifier = Modifier.height(8.dp)) // 间隔
                                } else if (!message.reasoning.isNullOrBlank() && !isExpanded && message.text.isNotBlank()) {
                                    // 如果有 reasoning 且折叠，并且有主体文本，在 Reasoning Header 和主体文本之间加个间隔
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }

                        // --- 主消息文本区域 ---
                        // 仅在内容已开始 或 消息文本非空白且不是思考占位符 时显示
                        if (message.contentStarted || (message.text.isNotBlank() && message.text != "...")) {
                            SelectionContainer { // 使文本可选择
                                Text(
                                    text = message.text,
                                    color = contentColor, // 应用文本颜色
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }

                        // 错误标记 (如果消息是错误状态)，可选，如果错误信息已包含在文本中，可以不需要单独标记
                        // if (message.isError && !message.text.contains(ERROR_VISUAL_PREFIX)) { // 避免重复标记
                        //      Spacer(modifier = Modifier.height(4.dp))
                        //      Text(
                        //           text = "[ERROR]",
                        //           color = MaterialTheme.colorScheme.error,
                        //           style = MaterialTheme.typography.labelSmall
                        //      )
                        // }
                    } // End Inner Column (Animated Content Size)
                } // End Surface for AI message
            } else if (message.sender == Sender.User) { // 用户消息
                // 用户消息气泡 (与之前逻辑类似)
                Surface(
                    modifier = Modifier.widthIn(max = maxBubbleWidth), // 应用最大宽度限制
                    shape = bubbleShape, // 应用气泡形状
                    color = bubbleBackgroundColor, // 应用背景色
                    tonalElevation = 1.dp // 添加一点阴影
                ) {
                    Column(
                        // 用户消息通常不会流式更新，但为了结构一致性可以保留 animateContentSize
                        modifier = Modifier
                            .animateContentSize(animationSpec = spring(stiffness = 300f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        SelectionContainer { // 使文本可选择
                            Text(
                                text = message.text,
                                color = contentColor,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            // 系统消息或其他类型的消息可以在这里添加else if 处理
            // 例如： else if (message.sender == Sender.System) { ... }
        } // End Outer Column
    } // End Alignment Box
}

// --- 修改并新增参数的波浪式缩放点点点动画 Composable ---
@Composable
fun WavyLoadingDots(dotColor: Color, dotCount: Int = 3, dotSpacing: Dp = 4.dp) {
    val dotSize = 8.dp // 小球基础尺寸
    val peakScale = 1.2f // 小球放大到的最大缩放比例
    val animationDuration = 400 // 单个小球一次缩放（放大或缩小）的持续时间 (毫秒)
    val delayBetweenDots = 150 // 不同小球动画开始的延迟差 (毫秒)

    // 为每个小球创建并记住一个 Animatable 实例，动画值表示缩放比例，初始为 1f (原大小)
    val animatables = List(dotCount) { remember { Animatable(1f) } } // 使用 dotCount

    // 启动协程来控制动画
    LaunchedEffect(dotColor, dotCount, dotSpacing) { // 当参数变化时重启动画
        animatables.forEachIndexed { index, animatable ->
            launch {
                // 重置到初始状态
                animatable.snapTo(1f)
                delay(index * delayBetweenDots.toLong()) // 根据索引添加延迟，实现波浪效果
                // 无限重复缩放动画
                animatable.animateTo(
                    targetValue = 1f, // 目标值在这里不直接使用，由 keyframes 定义
                    animationSpec = infiniteRepeatable( // 无限重复
                        animation = keyframes { // 关键帧动画定义缩放过程
                            durationMillis = animationDuration * 2 // 一个完整的放大-缩小周期时长
                            1f at 0 // 在动画开始时，缩放比例为 1f (原大小)
                            peakScale at animationDuration // 在动画中间点时，缩放比例达到 peakScale (放大)
                            1f at animationDuration * 2 // 在动画结束时，缩放比例回到 1f (原大小)
                            // 可以根据需要添加更多关键帧来调整动画曲线，增加“凹凸感”
                            // 例如： 0.9f at animationDuration * 0.5f // 在上升到一半时稍微收缩
                        },
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart // 重启模式
                    )
                )
            }
        }
    }

    // 使用 Row 将小球水平排列
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing) // 使用 dotSpacing 定义小球间距
    ) {
        animatables.forEach { animatable ->
            // 绘制每个小球
            Box(
                modifier = Modifier
                    .size(dotSize) // 应用基础尺寸
                    .scale(animatable.value) // *** 应用动画计算出的缩放值 ***
                    .clip(CircleShape) // 确保是圆形
                    .background(dotColor) // 设置颜色
            )
        }
    }
}
