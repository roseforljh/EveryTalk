package com.example.app1.ui.screens.MainScreen

import android.util.Log
import androidx.activity.compose.BackHandler // 处理返回键
import androidx.compose.animation.Crossfade // 淡入淡出切换组件
import androidx.compose.animation.animateContentSize // 内容尺寸变化动画
import androidx.compose.animation.core.LinearEasing // 线性缓动曲线
import androidx.compose.animation.core.animateDpAsState // Dp 值动画状态
import androidx.compose.animation.core.animateFloatAsState // Float 值动画状态
import androidx.compose.animation.core.tween // 补间动画规格
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.GestureCancellationException // 手势取消异常
import androidx.compose.foundation.gestures.detectTapGestures // 检测点击和长按等手势
import androidx.compose.foundation.interaction.MutableInteractionSource // 可变交互源
import androidx.compose.foundation.interaction.collectIsFocusedAsState // 收集焦点状态
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn // 惰性列表
import androidx.compose.foundation.lazy.items // 惰性列表项
import androidx.compose.foundation.shape.CircleShape // 圆形
import androidx.compose.foundation.shape.RoundedCornerShape // 圆角矩形
import androidx.compose.foundation.text.KeyboardActions // 键盘操作
import androidx.compose.foundation.text.KeyboardOptions // 键盘选项
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Material 图标
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds // 超出边界时裁剪
import androidx.compose.ui.draw.drawBehind // 在内容之后绘制
import androidx.compose.ui.draw.shadow // 阴影
import androidx.compose.ui.focus.FocusRequester // 焦点请求器
import androidx.compose.ui.focus.focusRequester // 应用焦点请求器
import androidx.compose.ui.geometry.Offset // 二维偏移量
import androidx.compose.ui.graphics.Color // 颜色
import androidx.compose.ui.graphics.drawscope.Fill // 绘制样式：填充
import androidx.compose.ui.input.pointer.pointerInput // 指针输入处理
import androidx.compose.ui.platform.LocalConfiguration // 获取本地配置信息（如屏幕宽度）
import androidx.compose.ui.platform.LocalFocusManager // 获取本地焦点管理器
import androidx.compose.ui.platform.LocalSoftwareKeyboardController // 获取本地软键盘控制器
import androidx.compose.ui.text.AnnotatedString // 带注解的字符串
import androidx.compose.ui.text.SpanStyle // 文本片段样式
import androidx.compose.ui.text.buildAnnotatedString // 构建带注解的字符串
import androidx.compose.ui.text.font.FontWeight // 字体粗细
import androidx.compose.ui.text.input.ImeAction // 输入法操作类型（如搜索、完成）
import androidx.compose.ui.text.style.TextOverflow // 文本溢出处理
import androidx.compose.ui.text.withStyle // 应用文本样式
import androidx.compose.ui.unit.IntOffset // 整数二维偏移量
import androidx.compose.ui.unit.IntRect // 整数矩形
import androidx.compose.ui.unit.IntSize // 整数尺寸
import androidx.compose.ui.unit.LayoutDirection // 布局方向
import androidx.compose.ui.unit.dp // dp 单位
import androidx.compose.ui.window.Popup // 弹出层
import androidx.compose.ui.window.PopupPositionProvider // 弹出层位置提供者
import androidx.compose.ui.window.PopupProperties // 弹出层属性
import com.example.app1.data.DataClass.Message // 消息数据类

import kotlinx.coroutines.Job // 协程作业
import kotlinx.coroutines.delay // 协程延迟
import kotlinx.coroutines.launch // 启动协程
import kotlin.math.max // 取最大值
import kotlin.math.min // 取最小值
import kotlin.math.roundToInt // 四舍五入到整数

// --- 常量定义 ---
private val searchBackgroundColor = Color(0xFFEAEAEA) // 搜索框背景色
private val defaultDrawerWidth = 280.dp // 抽屉默认宽度
private const val EXPAND_ANIMATION_DURATION_MS = 300 // 展开/收起动画持续时间 (毫秒)
private const val CONTENT_CHANGE_ANIMATION_DURATION_MS = 200 // 内容变化动画持续时间 (毫秒)

// 自定义涟漪效果常量
private const val CUSTOM_RIPPLE_ANIMATION_DURATION_MS = 350 // 涟漪动画时长 (毫秒)
private val CUSTOM_RIPPLE_COLOR = Color.Black             // 涟漪颜色
private const val CUSTOM_RIPPLE_START_ALPHA = 0.12f       // 涟漪起始透明度
private const val CUSTOM_RIPPLE_END_ALPHA = 0f            // 涟漪结束透明度

// 自定义涟漪状态
sealed class CustomRippleState {
    object Idle : CustomRippleState() // 空闲状态
    data class Animating(val pressPosition: Offset) : CustomRippleState() // 动画中状态，包含按压位置
}

// 用于列表显示的过滤后的对话项数据结构
data class FilteredConversationItem(
    val originalIndex: Int, // 在原始历史对话列表中的索引
    val conversation: List<Message>, // 对话消息列表 (仍用于搜索时匹配内容和生成高亮片段)
)

/**
 * 根据搜索查询生成高亮的预览文本片段。
 * @param messageText 原始消息文本。
 * @param query 搜索查询。
 * @param contextChars 查询关键词前后显示的上下文的字符数。
 * @return 带高亮样式的 AnnotatedString，如果查询为空或未找到匹配则返回 null。
 */
@Composable
private fun rememberGeneratedPreviewSnippet(
    messageText: String, query: String, contextChars: Int = 10 // 上下文预览字符数
): AnnotatedString? {
    val highlightColor = MaterialTheme.colorScheme.primary // 高亮颜色使用主题色
    return remember(messageText, query, highlightColor, contextChars) { // 依赖项正确，确保仅在必要时重新计算
        if (query.isBlank()) return@remember null // 查询为空则不生成片段
        val queryLower = query.lowercase() // 查询转小写以忽略大小写匹配
        val textLower = messageText.lowercase() // 消息文本转小写
        val startIndex = textLower.indexOf(queryLower) // 查找查询词在消息中的起始位置
        if (startIndex == -1) return@remember null // 未找到匹配则不生成片段

        // 计算片段的起始和结束位置
        val snippetStart = maxOf(0, startIndex - contextChars)
        val snippetEnd = minOf(messageText.length, startIndex + query.length + contextChars)
        val prefix = if (snippetStart > 0) "..." else "" // 如果片段不是从文本开头，则加前缀 "..."
        val suffix = if (snippetEnd < messageText.length) "..." else "" // 如果片段不是到文本末尾，则加后缀 "..."
        val rawSnippet = messageText.substring(snippetStart, snippetEnd) // 截取原始片段

        buildAnnotatedString { // 构建带注解的字符串
            append(prefix)
            val queryIndexInRawSnippet = rawSnippet.lowercase().indexOf(queryLower) // 查询词在片段内的位置
            if (queryIndexInRawSnippet != -1) { // 如果在片段内能找到（理论上应该总能找到）
                append(rawSnippet.substring(0, queryIndexInRawSnippet)) // 添加查询词之前的部分
                withStyle( // 对查询词应用高亮样式
                    style = SpanStyle(
                        fontWeight = FontWeight.SemiBold, // 字体半粗
                        color = highlightColor // 高亮颜色
                    )
                ) {
                    append( // 添加查询词本身
                        rawSnippet.substring(
                            queryIndexInRawSnippet,
                            queryIndexInRawSnippet + query.length
                        )
                    )
                }
                append(rawSnippet.substring(queryIndexInRawSnippet + query.length)) // 添加查询词之后的部分
            } else { // 理论上不应发生，作为回退直接添加原始片段
                append(rawSnippet)
            }
            append(suffix)
        }
    }
}


@OptIn(
    ExperimentalMaterial3Api::class, // Material 3 实验性API
    ExperimentalComposeUiApi::class, // Compose UI 实验性API
    ExperimentalFoundationApi::class // Foundation 实验性API (例如用于 LazyColumn 的 stickyHeader)
)
@Composable
fun AppDrawerContent(
    historicalConversations: List<List<Message>>, // 历史对话列表 (来自ViewModel)
    loadedHistoryIndex: Int?,                     // 当前加载的历史对话的索引 (来自ViewModel)
    isSearchActive: Boolean,                      // 搜索模式是否激活 (来自ViewModel)
    currentSearchQuery: String,                   // 当前搜索查询 (来自ViewModel)
    onSearchActiveChange: (Boolean) -> Unit,      // 搜索模式激活状态改变的回调 (通知ViewModel)
    onSearchQueryChange: (String) -> Unit,        // 搜索查询改变的回调 (通知ViewModel)
    onConversationClick: (Int) -> Unit,           // 点击对话项的回调
    onNewChatClick: () -> Unit,                   // 点击“新建会话”的回调
    onRenameRequest: (index: Int) -> Unit,        // 请求重命名对话的回调
    onDeleteRequest: (index: Int) -> Unit,        // 请求删除对话的回调
    onClearAllConversationsRequest: () -> Unit,   // 请求清空所有对话的回调
    getPreviewForIndex: (Int) -> String,          // 新增参数：用于获取指定索引对话的预览文本/标题的函数
    modifier: Modifier = Modifier
) {
    // --- 状态定义 ---
    var expandedItemIndex by remember { mutableStateOf<Int?>(null) } // 当前展开（长按后显示菜单）的列表项索引
    val selectedSet = remember { mutableStateListOf<Int>() } // 当前选中的列表项索引集合（主要用于未来的批量操作，目前主要用于删除确认）
    var showDeleteConfirm by remember { mutableStateOf(false) } // 是否显示删除确认对话框
    var showClearAllConfirm by remember { mutableStateOf(false) } // 是否显示清空所有记录确认对话框
    var longPressPosition by remember { mutableStateOf<Offset?>(null) } // 长按位置，用于定位弹出菜单

    val focusRequester = remember { FocusRequester() } // 搜索框焦点请求器
    val keyboardController = LocalSoftwareKeyboardController.current // 软键盘控制器
    val focusManager = LocalFocusManager.current // 焦点管理器
    val configuration = LocalConfiguration.current // 获取当前配置
    val screenWidth = configuration.screenWidthDp.dp // 屏幕宽度 (dp)

    // --- 副作用处理 ---
    // 当加载的历史对话索引变化时（例如新建对话或加载不同对话），重置一些抽屉内的UI状态
    LaunchedEffect(loadedHistoryIndex) {
        if (loadedHistoryIndex == null) { // 如果当前没有加载任何特定的历史对话 (例如，新建会话后)
            var changed = false
            if (selectedSet.isNotEmpty()) {
                selectedSet.clear(); changed = true // 清空多选集合
            }
            if (expandedItemIndex != null) { // 如果有某个项的菜单是展开的
                expandedItemIndex = null; changed = true // 则收起它
            }
            if (changed) Log.d(
                "AppDrawerContent",
                "因 loadedHistoryIndex 变为 null，已清除部分UI状态（选中项、展开项）。"
            )
        }
    }

    // 当没有项的菜单是展开的时候，清除长按位置记录 (确保下次菜单从新位置弹出)
    LaunchedEffect(expandedItemIndex) {
        if (expandedItemIndex == null) {
            longPressPosition = null
        }
    }

    // 当搜索模式激活状态变化时，处理焦点和键盘的显示/隐藏
    LaunchedEffect(isSearchActive, keyboardController) {
        if (isSearchActive) {
            Log.d("AppDrawerContent", "搜索激活，请求焦点并尝试显示键盘...")
            delay(100) // 短暂延迟，以确保搜索框在UI上准备好接收焦点
            focusRequester.requestFocus()
            val shown = keyboardController?.show() // 尝试显示软键盘
            Log.d("AppDrawerContent", "键盘显示请求结果: $shown")
        } else {
            Log.d("AppDrawerContent", "搜索取消，隐藏键盘并清除焦点。")
            keyboardController?.hide() // 隐藏软键盘
            focusManager.clearFocus(force = true) // 强制清除当前焦点
            // onSearchQueryChange("") // 可选：退出搜索时清空搜索词
        }
    }

    // --- 数据过滤 ---
    // 根据当前搜索查询过滤历史对话列表
    // 注意: 此过滤逻辑在用户输入时会频繁执行。建议在 AppViewModel 中对 currentSearchQuery (用户输入)
    // 进行防抖(debounce)处理，以减少不必要的列表遍历和字符串比较。
    val filteredItems = remember(currentSearchQuery, historicalConversations, isSearchActive) {
        if (!isSearchActive || currentSearchQuery.isBlank()) { // 如果不是搜索模式或搜索词为空
            historicalConversations.mapIndexed { index, conversation -> // 直接使用原始列表
                FilteredConversationItem(index, conversation)
            }
        } else { // 如果是搜索模式且有搜索词
            historicalConversations.mapIndexedNotNull { index, conversation ->
                // 遍历对话中的每条消息，看是否有文本内容包含搜索词 (忽略大小写)
                val matches = conversation.any { message ->
                    message.text.contains(currentSearchQuery, ignoreCase = true)
                }
                if (matches) FilteredConversationItem(index, conversation) else null // 如果匹配，则加入过滤结果
            }
        }
    }

    // --- 抽屉宽度动画 ---
    val targetWidth =
        if (isSearchActive) screenWidth else defaultDrawerWidth // 搜索激活时抽屉宽度为全屏，否则为默认宽度
    val animatedWidth by animateDpAsState( // 动画化宽度变化
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = EXPAND_ANIMATION_DURATION_MS),
        label = "drawerWidthAnimation" // 动画标签，用于调试
    )

    // --- 返回键处理 ---
    // 当抽屉内的搜索模式激活时，系统返回键应首先用于关闭搜索模式，而不是关闭抽屉或返回上一屏幕
    BackHandler(enabled = isSearchActive) {
        onSearchActiveChange(false) // 通知ViewModel停用搜索模式
        Log.d("AppDrawerContent", "返回键按下，抽屉内搜索模式停用。")
    }

    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight() // 填充最大高度
            .width(animatedWidth) // 应用动画宽度
            .shadow(
                // 应用阴影效果
                elevation = 6.dp, // 阴影高度
                clip = false, // 允许阴影绘制在边界外
                spotColor = Color.Black.copy(alpha = 0.50f), // 点光源颜色
                ambientColor = Color.Black.copy(alpha = 0.40f), // 环境光颜色
            ),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, // 抽屉背景色
        drawerTonalElevation = 0.dp, // 禁用默认的色调海拔，我们用自定义阴影
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .animateContentSize(animationSpec = tween(durationMillis = CONTENT_CHANGE_ANIMATION_DURATION_MS)) // 内容尺寸变化时动画
        ) {
            val textFieldInteractionSource = remember { MutableInteractionSource() }
            val isTextFieldFocused by textFieldInteractionSource.collectIsFocusedAsState() // 跟踪搜索框焦点

            // 当搜索框获得焦点但搜索模式未激活时，自动激活搜索模式
            LaunchedEffect(isTextFieldFocused) {
                if (isTextFieldFocused && !isSearchActive) {
                    onSearchActiveChange(true)
                }
            }

            // --- 搜索框区域 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 8.dp), // 内边距
                verticalAlignment = Alignment.CenterVertically // 垂直居中对齐
            ) {
                OutlinedTextField(
                    value = currentSearchQuery, // 当前搜索查询文本
                    onValueChange = { newQuery -> // 文本变化回调
                        onSearchQueryChange(newQuery) // 通知ViewModel查询已改变
                        if (newQuery.isNotBlank() && !isSearchActive) { // 如果输入了内容且搜索未激活，则激活搜索
                            onSearchActiveChange(true)
                        }
                    },
                    modifier = Modifier
                        .weight(1f) // 占据剩余空间
                        .heightIn(min = 48.dp) // 最小高度
                        .focusRequester(focusRequester), // 应用焦点请求器
                    placeholder = { Text("搜索历史记录") }, // 占位提示文本
                    leadingIcon = { // 前导图标 (搜索/返回)
                        Crossfade( // 使用Crossfade实现图标切换动画
                            targetState = isSearchActive, // 目标状态是搜索是否激活
                            animationSpec = tween(EXPAND_ANIMATION_DURATION_MS),
                            label = "SearchIconCrossfade"
                        ) { active ->
                            if (active) { // 如果搜索激活，显示返回按钮
                                IconButton(
                                    onClick = { onSearchActiveChange(false) }, // 点击返回，停用搜索
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowBack,
                                        "返回",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else { // 否则显示搜索图标
                                IconButton(
                                    onClick = { onSearchActiveChange(true) }, // 点击搜索图标，激活搜索
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        "搜索图标",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    },
                    trailingIcon = { // 尾随图标 (清除搜索内容)
                        if (currentSearchQuery.isNotEmpty()) { // 仅当有搜索内容时显示
                            IconButton(onClick = { onSearchQueryChange("") }) { // 点击清空搜索查询
                                Icon(Icons.Filled.Close, "清除搜索")
                            }
                        }
                    },
                    shape = RoundedCornerShape(50.dp), // 圆角形状，使其看起来像胶囊
                    colors = OutlinedTextFieldDefaults.colors( // 自定义文本框颜色
                        focusedBorderColor = Color.Transparent, // 焦点时边框透明
                        unfocusedBorderColor = Color.Transparent, // 非焦点时边框透明
                        focusedContainerColor = searchBackgroundColor, // 焦点时背景色
                        unfocusedContainerColor = searchBackgroundColor, // 非焦点时背景色
                        cursorColor = MaterialTheme.colorScheme.primary // 光标颜色
                    ),
                    singleLine = true, // 单行输入
                    interactionSource = textFieldInteractionSource, // 交互源，用于监听焦点等
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), // 软键盘回车键设为“搜索”
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }) // 点击软键盘搜索键时清除焦点 (隐藏键盘)
                )
            }

            // --- “新建会话” 和 “清空记录” 按钮 ---
            Column {
                Spacer(Modifier.height(8.dp))
                Button( // 新建会话按钮
                    onClick = { onNewChatClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp), // 圆角
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White, // 按钮背景色
                        contentColor = Color.Black // 内容颜色 (文本和图标)
                    ),
                    elevation = ButtonDefaults.buttonElevation( // 移除默认阴影
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp
                    ),
                    contentPadding = PaddingValues(0.dp) // 移除默认内边距，手动控制
                ) {
                    Row( // 按钮内容布局
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp), // 左边距
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start // 从左开始排列
                    ) {
                        Icon(
                            Icons.Filled.AddCircleOutline,
                            "新建会话图标",
                            tint = Color.Black
                        ); Spacer(Modifier.width(20.dp)) // 图标和文本间距
                        Text(
                            "新建会话",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Button( // 清空记录按钮
                    onClick = { showClearAllConfirm = true }, // 点击显示清空确认对话框
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(Icons.Filled.ClearAll, "清空记录图标", tint = Color.Black); Spacer(
                        Modifier.width(20.dp)
                    )
                        Text(
                            "清空记录",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
            }

            // --- "聊天" 列表标题 ---
            Column {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "聊天", // 列表分组标题
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), // 文本样式
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp), // 内边距
                    color = MaterialTheme.colorScheme.onSurfaceVariant // 文本颜色
                )
            }

            // --- 列表显示区域 (历史对话或搜索结果) ---
            Box(modifier = Modifier.weight(1f)) { // 使用 Box 和 weight(1f) 使列表区域填充剩余空间
                when {
                    // 情况1: 没有任何历史对话
                    historicalConversations.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center // 内容居中
                        ) {
                            Text("暂无聊天记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 情况2: 处于搜索模式，有搜索查询，但过滤结果为空
                    isSearchActive && currentSearchQuery.isNotBlank() && filteredItems.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 情况3: 有对话可显示 (原始列表或过滤后的列表)
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) { // 惰性加载列表
                            items(
                                items = filteredItems, // 使用过滤后的项目列表
                                key = { item -> item.originalIndex } // 使用原始索引作为列表项的稳定键，有助于性能和动画
                            ) { itemData ->
                                val originalIndex = itemData.originalIndex // 当前项在原始列表中的索引

                                // --- 关键改动：调用ViewModel的函数来获取正确的预览文本/标题 ---
                                val definitivePreviewText = getPreviewForIndex(originalIndex)

                                // 搜索时的高亮预览片段（如果适用）
                                val previewTextToShowInSnippet: AnnotatedString? =
                                    if (isSearchActive && currentSearchQuery.isNotBlank()) {
                                        // 查找对话中第一条匹配搜索词的消息，并为其生成高亮片段
                                        itemData.conversation.firstNotNullOfOrNull { msg ->
                                            if (msg.text.contains(
                                                    currentSearchQuery,
                                                    ignoreCase = true
                                                )
                                            )
                                                rememberGeneratedPreviewSnippet( // 生成带高亮的文本片段
                                                    msg.text,
                                                    currentSearchQuery
                                                )
                                            else null
                                        }
                                    } else {
                                        null // 非搜索模式或无搜索词，则不显示片段
                                    }
                                val isActuallyActive =
                                    loadedHistoryIndex == originalIndex // 判断此项是否为当前活动/加载的对话

                                // 自定义涟漪效果相关状态
                                var rippleState by remember {
                                    mutableStateOf<CustomRippleState>(
                                        CustomRippleState.Idle
                                    )
                                }
                                var currentPressPosition by remember { mutableStateOf(Offset.Zero) } // 当前按压位置
                                val animationProgress by animateFloatAsState( // 涟漪动画进度 (0f -> 1f -> 0f)
                                    targetValue = if (rippleState is CustomRippleState.Animating) 1f else 0f,
                                    animationSpec = tween(
                                        durationMillis = CUSTOM_RIPPLE_ANIMATION_DURATION_MS,
                                        easing = LinearEasing // 线性缓动
                                    ),
                                    finishedListener = { // 动画结束监听 (当 progress 变回 0f 时)
                                        if (rippleState !is CustomRippleState.Idle && it == 0f) {
                                            rippleState = CustomRippleState.Idle // 重置涟漪状态
                                        }
                                    },
                                    label = "rippleAnimationProgress"
                                )
                                val scope = rememberCoroutineScope() // 协程作用域
                                var pressAndHoldJob by remember { mutableStateOf<Job?>(null) } // 用于管理长按手势的协程

                                Box( // 每个列表项的根Box，应用手势和涟漪效果
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clipToBounds() // 裁剪涟漪，避免溢出到其他项
                                        .pointerInput(originalIndex) { // 监听指针输入，key 为 originalIndex 确保每个item有自己的手势处理器
                                            detectTapGestures( // 检测点击、长按等手势
                                                onPress = { offset -> // 按下时触发
                                                    pressAndHoldJob?.cancel() // 取消之前的长按任务 (如果存在)
                                                    currentPressPosition = offset // 记录按压位置
                                                    rippleState =
                                                        CustomRippleState.Animating(offset) // 开始涟漪动画
                                                    pressAndHoldJob =
                                                        scope.launch { // 启动一个协程等待释放或取消
                                                            try {
                                                                this@detectTapGestures.awaitRelease() // 等待手指抬起
                                                                rippleState =
                                                                    CustomRippleState.Idle // 手指抬起，重置涟漪 (动画会自动播放完)
                                                            } catch (e: GestureCancellationException) { // 如果手势被取消 (例如滚动)
                                                                rippleState =
                                                                    CustomRippleState.Idle // 也重置涟漪
                                                            }
                                                        }
                                                },
                                                onTap = { // 点击列表项时触发
                                                    if (expandedItemIndex == originalIndex) { // 如果当前项的菜单已展开
                                                        expandedItemIndex =
                                                            null // 则关闭菜单 (再次点击已展开项的空白处)
                                                    } else { // 否则，执行加载对话的操作
                                                        expandedItemIndex = null // 确保其他项的菜单已关闭
                                                        selectedSet.clear() // 清空多选集合 (如果之前有选择)
                                                        onConversationClick(originalIndex) // 调用外部传入的点击回调
                                                    }
                                                },
                                                onLongPress = { offset -> // 长按列表项时触发
                                                    pressAndHoldJob?.cancel() // 取消涟漪的 pressAndHoldJob
                                                    rippleState =
                                                        CustomRippleState.Idle // 立即停止涟漪动画，因为要显示菜单
                                                    longPressPosition = offset // 记录长按位置，用于Popup定位
                                                    // 如果长按的是当前已展开菜单的项，则关闭；否则展开新项的菜单
                                                    expandedItemIndex =
                                                        if (expandedItemIndex == originalIndex) null else originalIndex
                                                    Log.d(
                                                        "AppDrawerContent",
                                                        "长按索引 $originalIndex, expandedItemIndex 设置为 $expandedItemIndex"
                                                    )
                                                }
                                            )
                                        }
                                        .drawBehind { // 在Box内容之后绘制自定义涟漪效果
                                            if (animationProgress > 0f) { // 仅当动画进行中
                                                val rippleRadius = max(
                                                    size.width,
                                                    size.height
                                                ) * animationProgress * 0.8f // 涟漪半径随动画进度变化
                                                val alpha =
                                                    CUSTOM_RIPPLE_START_ALPHA * (1f - animationProgress) // 透明度从起始值减到0
                                                if (alpha > 0f) { // 仅当透明度大于0时绘制
                                                    drawCircle(
                                                        color = CUSTOM_RIPPLE_COLOR,
                                                        radius = rippleRadius,
                                                        center = currentPressPosition, // 从按压点开始扩散
                                                        alpha = alpha.coerceIn( // 确保alpha在有效范围内
                                                            CUSTOM_RIPPLE_END_ALPHA,
                                                            CUSTOM_RIPPLE_START_ALPHA
                                                        ),
                                                        style = Fill // 填充样式
                                                    )
                                                }
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) { // 列表项内部布局
                                        Row( // 包含指示器和主要内容
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isActuallyActive) { // 如果是当前活动的对话项，显示指示器
                                                Spacer(Modifier.width(16.dp)); Box(
                                                    modifier = Modifier
                                                        .size(
                                                            8.dp // 指示器大小
                                                        )
                                                        .background(
                                                            Color.Black,
                                                            CircleShape
                                                        ) // 黑色圆形指示器
                                                ); Spacer(Modifier.width(8.dp)) // 指示器与文本间距
                                            } else { // 否则，留出相同宽度的空白以保持对齐
                                                Spacer(Modifier.width(32.dp))
                                            }
                                            // --- 关键改动：将获取到的预览文本传递给内容组件 ---
                                            DrawerConversationItemContent(
                                                previewText = definitivePreviewText, // <-- 使用从外部获取的标题文本
                                                modifier = Modifier
                                                    .weight(1f) // 占据剩余宽度
                                                    .padding(end = 16.dp) // 右边距
                                            )
                                        }
                                        // 显示搜索结果的上下文预览片段 (如果存在)
                                        if (previewTextToShowInSnippet != null) {
                                            Spacer(Modifier.height(1.dp)) // 与主标题的微小间距
                                            Text(
                                                text = previewTextToShowInSnippet, // 高亮的文本片段
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.9f // 略微降低透明度
                                                ),
                                                style = MaterialTheme.typography.bodySmall, // 较小字号
                                                modifier = Modifier
                                                    .padding(
                                                        start = 32.dp, // 与主标题对齐 (考虑指示器宽度)
                                                        end = 16.dp,
                                                        bottom = 8.dp,
                                                        top = 0.dp
                                                    )
                                                    .fillMaxWidth(),
                                                maxLines = 2, // 最多显示两行
                                                overflow = TextOverflow.Ellipsis // 超出部分显示省略号
                                            )
                                        }

                                        // --- 上下文菜单显示逻辑 ---
                                        // 仅当此项是当前展开的项(expandedItemIndex)且长按位置已记录时，才显示Popup菜单
                                        if (expandedItemIndex == originalIndex && longPressPosition != null) {
                                            val currentLongPressPosition =
                                                longPressPosition!! // 确保非空
                                            Popup( // 弹出式菜单
                                                popupPositionProvider = object : // 自定义Popup位置
                                                    PopupPositionProvider {
                                                    override fun calculatePosition(
                                                        anchorBounds: IntRect, // 锚点 (列表项Box) 的边界
                                                        windowSize: IntSize, // 窗口大小
                                                        layoutDirection: LayoutDirection,
                                                        popupContentSize: IntSize // Popup菜单自身的大小
                                                    ): IntOffset {
                                                        // 尝试将Popup的左上角置于长按点
                                                        val x =
                                                            anchorBounds.left + currentLongPressPosition.x.roundToInt()
                                                        val y =
                                                            anchorBounds.top + currentLongPressPosition.y.roundToInt()
                                                        // 确保Popup不会超出屏幕边界
                                                        val finalX = x.coerceIn(
                                                            0,
                                                            windowSize.width - popupContentSize.width
                                                        )
                                                        val finalY = y.coerceIn(
                                                            0,
                                                            windowSize.height - popupContentSize.height
                                                        )
                                                        return IntOffset(finalX, finalY)
                                                    }
                                                },
                                                onDismissRequest = { // 当点击外部或按返回键时
                                                    expandedItemIndex = null // 关闭菜单
                                                },
                                                properties = PopupProperties(focusable = true) // focusable=true 允许Popup通过返回键关闭
                                            ) {
                                                // 判断重命名按钮是否可用：此处简单设定为总是可用，除非有更复杂的逻辑 (例如批量选择时禁用)
                                                val isRenameEnabledForThisPopup =
                                                    true // !selectedSet.contains(originalIndex)
                                                Surface( // 菜单背景
                                                    color = Color.White, shadowElevation = 8.dp,
                                                    shape = RoundedCornerShape(12.dp), // 圆角
                                                    modifier = Modifier.widthIn(max = 120.dp) // 菜单宽度限制
                                                ) {
                                                    Column( // 菜单项垂直排列
                                                        modifier = Modifier.padding(
                                                            vertical = 4.dp,
                                                            horizontal = 8.dp
                                                        )
                                                    ) {
                                                        // 重命名选项
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 12.dp) // 上下内边距增加点击区域
                                                                .clickable( // 使整个Row可点击
                                                                    enabled = isRenameEnabledForThisPopup,
                                                                    onClick = {
                                                                        if (isRenameEnabledForThisPopup) {
                                                                            onRenameRequest(
                                                                                originalIndex
                                                                            ) // 调用重命名回调
                                                                            expandedItemIndex =
                                                                                null // 关闭菜单
                                                                        }
                                                                    },
                                                                    interactionSource = remember { MutableInteractionSource() }, // 移除默认涟漪
                                                                    indication = null
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.DriveFileRenameOutline,
                                                                "重命名图标",
                                                                tint = if (isRenameEnabledForThisPopup) Color.Black else Color.Gray,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                            Spacer(Modifier.width(12.dp))
                                                            Text(
                                                                "重命名",
                                                                color = if (isRenameEnabledForThisPopup) Color.Black else Color.Gray,
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                        }
                                                        Divider(color = Color.LightGray.copy(alpha = 0.5f)) // 分隔线
                                                        // 删除选项
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 12.dp)
                                                                .clickable(
                                                                    onClick = {
                                                                        // 如果需要批量删除，这里可以处理 selectedSet.add(originalIndex)
                                                                        // 但当前逻辑是单项删除，所以直接触发删除确认
                                                                        if (!selectedSet.contains(
                                                                                originalIndex
                                                                            )
                                                                        ) selectedSet.add(
                                                                            originalIndex
                                                                        ) // 确保至少当前项被选中
                                                                        else if (selectedSet.isEmpty() && expandedItemIndex == originalIndex) selectedSet.add(
                                                                            originalIndex
                                                                        ) // 确保即使没有多选，长按项也被加入

                                                                        expandedItemIndex =
                                                                            null // 关闭菜单
                                                                        showDeleteConfirm =
                                                                            true // 显示删除确认对话框
                                                                    },
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.Delete,
                                                                "删除图标",
                                                                tint = Color.Black, // 通常删除用红色，但这里保持一致性
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                            Spacer(Modifier.width(12.dp))
                                                            Text(
                                                                "删除",
                                                                color = Color.Black,
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // --- 删除确认 和 清空所有记录确认 对话框 ---
            if (showDeleteConfirm) { // 显示删除确认对话框
                AlertDialog(
                    onDismissRequest = {
                        showDeleteConfirm = false; selectedSet.clear()
                    }, // 点击外部或返回键，关闭对话框并清空选择集
                    title = { Text(if (selectedSet.size > 1) "确定删除所有所选项？" else if (selectedSet.size == 1) "确定删除所选项？" else "确定删除此项？") }, // 动态标题
                    // text = { Text("此操作无法撤销。") }, // 可选的描述文本
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val indicesToDelete = selectedSet.toList() // 创建副本以防并发修改
                                selectedSet.clear() // 清空选择集
                                expandedItemIndex = null // 关闭可能打开的菜单
                                showDeleteConfirm = false // 关闭对话框
                                // 从后往前删除，避免索引错位
                                indicesToDelete.sortedDescending().forEach(onDeleteRequest)
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red) // 确认按钮用红色表示危险操作
                        ) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false; selectedSet.clear() // 关闭对话框并清空选择集
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) {
                            Text(
                                "取消"
                            )
                        }
                    },
                    containerColor = Color.White, // 对话框背景色
                    titleContentColor = Color.Black, // 标题颜色
                    textContentColor = Color.Black // 内容文本颜色 (如果text属性被使用)
                )
            }
            if (showClearAllConfirm) { // 显示清空所有记录确认对话框
                AlertDialog(
                    onDismissRequest = { showClearAllConfirm = false }, // 点击外部或返回键，关闭对话框
                    title = { Text("确定清空所有聊天记录？") },
                    text = { Text("此操作无法撤销，所有聊天记录将被永久删除。") }, // 警告信息
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onClearAllConversationsRequest() // 调用ViewModel清空所有记录
                                showClearAllConfirm = false // 关闭对话框
                                selectedSet.clear() // 清空选择（以防万一）
                                expandedItemIndex = null // 关闭菜单（以防万一）
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                        ) { Text("确定清空") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showClearAllConfirm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
                        ) { Text("取消") }
                    },
                    containerColor = Color.White,
                    titleContentColor = Color.Black,
                    textContentColor = Color.Black
                )
            }
        }
    }
}

/**
 * 抽屉中单个对话项的内容（主要显示预览文本/标题）。
 * @param previewText 由ViewModel或外部逻辑提供的、应显示的对话预览/标题文本。
 */
@Composable
private fun DrawerConversationItemContent(
    previewText: String, // 直接接收计算好的预览文本/标题
    modifier: Modifier = Modifier
) {
    Text(
        text = previewText, // 使用传入的预览文本/标题
        maxLines = 1, // 最多显示一行
        overflow = TextOverflow.Ellipsis, // 超出部分显示省略号
        fontWeight = FontWeight.Medium, // 字体粗细
        style = MaterialTheme.typography.bodyLarge, // 文本样式 (与Material Design指南匹配)
        modifier = modifier.padding(vertical = 18.dp) // 上下内边距，提供足够的点击高度和视觉分隔
    )
}