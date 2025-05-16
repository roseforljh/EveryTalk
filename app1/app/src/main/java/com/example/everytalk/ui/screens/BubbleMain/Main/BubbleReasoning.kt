package com.example.everytalk.ui.screens.BubbleMain.Main // 您的实际包名

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG_REASONING = "推理界面" // 日志标签：推理界面

@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,           // 当前消息ID
    displayedReasoningText: String,     // 当前显示的推理文本
    isReasoningStreaming: Boolean,     // 推理过程是否正在流式传输
    isReasoningComplete: Boolean,      // 推理过程是否已完成
    messageContentStarted: Boolean,    // 主消息内容是否已开始
    messageIsError: Boolean,           // 主消息是否为错误
    reasoningTextColor: Color,         // 推理文本颜色
    reasoningToggleDotColor: Color,    // 推理切换按钮上点的颜色
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current // 获取焦点管理器
    // 全局加载状态：推理正在流式传输、尚未完成、且主消息无错误
    val showLoadingAnimationGlobal =
        isReasoningStreaming && !isReasoningComplete && !messageIsError

    // 控制推理内容对话框是否显示的本地状态
    var showReasoningDialog by remember(currentMessageId) { mutableStateOf(false) }

    // 对话框关闭请求的回调
    val onDismissDialogRequest = {
        Log.d(TAG_REASONING, "[$currentMessageId] Dialog dismiss requested.") // 日志：对话框关闭请求
        showReasoningDialog = false
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start // 列内容向起始位置对齐
    ) {
        // 1. 主界面临时显示的思考过程 (仅在流式输出期间，且主内容已开始，无错误)
        AnimatedVisibility(
            visible = isReasoningStreaming && !isReasoningComplete && messageContentStarted && !messageIsError,
            enter = fadeIn(tween(150)) + expandVertically( // 进入动画：淡入并从顶部展开
                animationSpec = tween(250),
                expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(150)) + shrinkVertically( // 退出动画：淡出并向顶部收缩
                animationSpec = tween(250),
                shrinkTowards = Alignment.Top
            )
        ) {
            Surface( // 用于承载临时推理文本的表面
                shape = RoundedCornerShape( // 定义形状，顶部较小圆角，底部较大圆角
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp,
                    topStart = 4.dp,
                    topEnd = 4.dp
                ),
                color = Color.White, // 背景色
                modifier = Modifier
                    .fillMaxWidth() // 填充最大宽度
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp) // 外边距
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 15.dp, vertical = 10.dp) // 内边距
                ) {
                    Text(
                        text = buildAnnotatedString { // 构建带样式的注解字符串
                            withStyle( // "思考中："部分的样式（虽然这里append的是空字符串，但可以快速修改）
                                style = SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.1 // 字体稍大
                                )
                            ) {
                                append("") // 这里原意可能是 "思考中："，当前为空
                            }
                            // 如果 displayedReasoningText 为空且正在加载，则显示一个空格占位，避免"思考中："后直接是空的
                            // 否则显示 displayedReasoningText；如果两者都空，则显示空字符串
                            append(displayedReasoningText.ifBlank {
                                if (showLoadingAnimationGlobal) " " // 动画时用空格占位
                                else ""
                            })
                        },
                        color = reasoningTextColor, // 文本颜色
                        style = MaterialTheme.typography.bodyMedium, // 文本样式
                    )
                }
            }
        }

        // 2. 切换按钮（小黑点），用于打开完整推理内容的Dialog
        // 显示条件：推理文本不为空，或者正在流式传输推理且主内容已开始且无错误
        val shouldShowToggleButton =
            displayedReasoningText.isNotBlank() || (isReasoningStreaming && messageContentStarted && !messageIsError)
        if (shouldShowToggleButton) {
            Box( // 切换按钮的容器
                modifier = Modifier.padding(start = 8.dp, top = 0.dp, bottom = 2.dp) // 外边距
            ) {
                Box( // 可点击区域
                    contentAlignment = Alignment.Center, // 内容居中
                    modifier = Modifier
                        .height(38.dp) // 固定高度
                        .width(38.dp)  // 固定宽度
                        .clip(RoundedCornerShape(12.dp)) // 圆角裁剪
                        .background(Color.White) // 背景色
                        .clickable( // 设置点击事件
                            indication = null, // 无点击水波纹效果
                            interactionSource = remember { MutableInteractionSource() }, // 交互源
                            onClick = {
                                focusManager.clearFocus() // 清除当前焦点（如输入框焦点）
                                showReasoningDialog = true // 显示推理对话框
                            }
                        )
                ) {
                    // 如果全局加载动画正在显示且对话框未打开，则显示三点波浪动画
                    if (showLoadingAnimationGlobal && !showReasoningDialog) {
                        ThreeDotsWaveAnimation( // 三点波浪动画
                            dotColor = reasoningToggleDotColor, // 点的颜色
                            dotSize = 7.dp,                      // 点的大小
                            spacing = 5.dp                       // 点之间的间距
                        )
                    } else { // 否则显示一个静态的、大小可变的圆形图标
                        val circleIconSize by animateDpAsState( // 图标大小的动画状态
                            targetValue = if (showReasoningDialog) 12.dp else 8.dp, // 对话框打开时大，关闭时小
                            animationSpec = tween( // 动画规格
                                durationMillis = 250,
                                easing = FastOutSlowInEasing // 缓动曲线
                            ),
                            label = "reasoningDialogToggleIconSize_${currentMessageId}" // 动画标签
                        )
                        Box( // 圆形图标
                            modifier = Modifier
                                .size(circleIconSize) // 应用动画大小
                                .background(reasoningToggleDotColor, CircleShape) // 背景色和圆形
                        )
                    }
                }
            }
        }
    }

    // 3. 推理内容的 Dialog (显示完整思考过程)
    if (showReasoningDialog) {
        Dialog(
            onDismissRequest = { onDismissDialogRequest() }, // 当用户请求关闭对话框时（点击外部或返回键）
            properties = DialogProperties( // 对话框属性
                dismissOnClickOutside = true,     // 点击外部可关闭
                dismissOnBackPress = true,        // 按返回键可关闭
                usePlatformDefaultWidth = false   // 不使用平台默认宽度，以便自定义宽度
            )
        ) {
            Card( // 卡片作为对话框背景
                shape = RoundedCornerShape(24.dp), // 圆角形状
                modifier = Modifier
                    .fillMaxWidth(0.9f) // 宽度为屏幕的90%
                    .padding(vertical = 32.dp) // 垂直外边距
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f), // 最大高度为屏幕的80%
                colors = CardDefaults.cardColors(containerColor = Color.White) // 卡片颜色
            ) {
                Column( // 对话框内容列布局
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp) // 内边距
                ) {
                    Text( // 标题
                        text = "Thinking Process", // "思考过程"
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), // 样式
                        modifier = Modifier
                            .padding(bottom = 16.dp) // 底部外边距
                            .align(Alignment.CenterHorizontally) // 水平居中
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color.Gray.copy(alpha = 0.2f)
                    ) // 水平分割线
                    Spacer(Modifier.height(16.dp)) // 间隔
                    Box( // 包裹可滚动文本的容器
                        modifier = Modifier
                            .weight(1f, fill = false) // 占据剩余空间，但不强制填满（允许内容滚动）
                            .verticalScroll(rememberScrollState()) // 垂直滚动
                    ) {
                        Text( // 推理文本内容
                            text = if (displayedReasoningText.isNotBlank()) displayedReasoningText // 如果有文本则显示
                            else if (showLoadingAnimationGlobal) "" // 如果在加载中，对话框内文本区域留空，让下面的动画显示
                            else if (messageIsError) "An error occurred during the thinking process." // 如果出错，显示错误信息
                            else "No detailed thoughts available.", // 其他情况，显示无详细信息
                            color = reasoningTextColor, // 文本颜色
                            style = MaterialTheme.typography.bodyLarge // 文本样式
                        )
                    }
                    if (showLoadingAnimationGlobal) { // 如果全局加载动画正在显示
                        Spacer(Modifier.height(16.dp)) // 顶部间隔
                        // 分割线逻辑，避免在没有文本且正在加载时出现不必要的分割线
                        if (displayedReasoningText.isNotBlank() && showLoadingAnimationGlobal) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = Color.Gray.copy(alpha = 0.2f)
                            )
                        } else if (messageIsError || (!displayedReasoningText.isNotBlank() && showLoadingAnimationGlobal)) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = Color.Gray.copy(alpha = 0.2f)
                            )
                        }

                        Row( // 加载动画容器
                            modifier = Modifier
                                .fillMaxWidth() // 填充宽度
                                .padding(top = 16.dp), // 顶部内边距
                            horizontalArrangement = Arrangement.Center, // 水平居中
                            verticalAlignment = Alignment.CenterVertically // 垂直居中
                        ) {
                            ThreeDotsWaveAnimation( // 三点波浪动画
                                dotColor = reasoningToggleDotColor, // 点颜色
                                dotSize = 10.dp,                     // 点大小
                                spacing = 8.dp                       // 点间距
                            )
                        }
                    }
                }
            }
        }
    }
}

// 三点波浪动画 Composable (用于推理切换按钮和对话框内的加载指示)
@Composable
fun ThreeDotsWaveAnimation(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary, // 点的颜色，默认为主题色
    dotSize: Dp = 12.dp,                                 // 点的大小
    spacing: Dp = 8.dp,                                  // 点之间的间距
    animationDelayMillis: Int = 200,                     // 每个点开始动画的延迟基数
    animationDurationMillis: Int = 600,                  // 单个点完成一次完整跳动（上然后下）的持续时间
    maxOffsetY: Dp = -(dotSize / 2)                      // 点向上跳动的最大Y轴偏移量（相对于其中心）
) {
    val dots = listOf( // 创建三个动画状态，用于控制每个点
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) }
    )
    val maxOffsetYPx = with(LocalDensity.current) { maxOffsetY.toPx() } // 将dp转换为像素

    dots.forEachIndexed { index, animatable -> // 遍历每个动画状态
        LaunchedEffect(animatable) { // 为每个点启动一个副作用来驱动动画
            delay(index * (animationDelayMillis / 2).toLong()) // 根据索引延迟启动，形成波浪效果
            launch { // 启动一个新协程来执行无限动画循环
                while (true) { // 无限循环
                    animatable.animateTo( // 向上动画
                        targetValue = maxOffsetYPx,
                        animationSpec = tween(
                            durationMillis = animationDurationMillis / 2,
                            easing = FastOutSlowInEasing
                        )
                    )
                    animatable.animateTo( // 向下动画
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = animationDurationMillis / 2,
                            easing = FastOutSlowInEasing
                        )
                    )
                }
            }
        }
    }
    Row( // 横向排列三个点
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing) // 点之间的水平间距
    ) {
        dots.forEach { animatable -> // 遍历每个动画状态，创建一个Box代表点
            Box(
                modifier = Modifier
                    .size(dotSize) // 设置点的大小
                    .graphicsLayer { translationY = animatable.value } // 应用Y轴位移来实现跳动效果
                    .background(color = dotColor, shape = CircleShape) // 设置点的颜色和圆形形状
            )
        }
    }
}