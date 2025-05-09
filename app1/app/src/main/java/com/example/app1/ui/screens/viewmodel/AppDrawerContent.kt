package com.example.app1.ui.screens.viewmodel

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.* // 导入所有动画相关的
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// --- 常量定义 ---
private val targetShadowAlpha = 0.20f
private val defaultItemBackgroundColor = Color.Transparent
private const val REVEAL_ANIMATION_DURATION_MS = 400
private val searchBackgroundColor = Color(0xFFEAEAEA)
private val defaultDrawerWidth = 300.dp
private const val EXPAND_ANIMATION_DURATION_MS = 300
private const val CONTENT_CHANGE_ANIMATION_DURATION_MS = 200 // 用于animateContentSize


// --- 数据类：用于过滤后的列表项 ---
data class FilteredConversationItem(
    val originalIndex: Int,
    val conversation: List<Message>,
    // 注意：matchedPreview 将在 LazyColumn item 作用域内通过 rememberGeneratedPreviewSnippet 生成
    // 这里可以保持为 null，或者在 remember 块中存储用于生成预览的原始文本和查询
    // 为了简化，我们让 LazyColumn item 内部处理预览的生成
)

// --- 辅助 Composable 函数：生成带高亮的预览文本 ---
@Composable
private fun rememberGeneratedPreviewSnippet(
    messageText: String,
    query: String,
    contextChars: Int = 10 // 在查询词前后显示的字符数
): AnnotatedString? {
    val highlightColor = MaterialTheme.colorScheme.primary // 使用主题的主色调进行高亮

    return remember(messageText, query, highlightColor, contextChars) {
        if (query.isBlank()) return@remember null

        val queryLower = query.lowercase()
        val textLower = messageText.lowercase()
        val startIndex = textLower.indexOf(queryLower)

        if (startIndex == -1) return@remember null

        val snippetStart = maxOf(0, startIndex - contextChars)
        val snippetEnd = minOf(messageText.length, startIndex + query.length + contextChars)

        val prefix = if (snippetStart > 0) "..." else ""
        val suffix = if (snippetEnd < messageText.length) "..." else ""

        val rawSnippet = messageText.substring(snippetStart, snippetEnd)

        buildAnnotatedString {
            append(prefix)
            val queryIndexInRawSnippet = rawSnippet.lowercase().indexOf(queryLower)
            if (queryIndexInRawSnippet != -1) {
                append(rawSnippet.substring(0, queryIndexInRawSnippet))
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = highlightColor
                    )
                ) {
                    append(
                        rawSnippet.substring(
                            queryIndexInRawSnippet,
                            queryIndexInRawSnippet + query.length
                        )
                    )
                }
                append(rawSnippet.substring(queryIndexInRawSnippet + query.length))
            } else {
                append(rawSnippet)
            }
            append(suffix)
        }
    }
}


@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalComposeUiApi::class
)
@Composable
fun AppDrawerContent(
    historicalConversations: List<List<Message>>,
    loadedHistoryIndex: Int?,
    onConversationClick: (Int) -> Unit,
    onNewChatClick: () -> Unit,
    onRenameRequest: (index: Int) -> Unit,
    onDeleteRequest: (index: Int) -> Unit,
    onClearAllConversationsRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedItemIndex by remember { mutableStateOf<Int?>(null) }
    val selectedSet = remember { mutableStateListOf<Int>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var longPressPosition by remember { mutableStateOf<Offset?>(null) }
    var showPopupForIndex by remember { mutableStateOf<Int?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    LaunchedEffect(loadedHistoryIndex) {
        if (loadedHistoryIndex == null) {
            var changed = false
            if (selectedSet.isNotEmpty()) {
                selectedSet.clear(); changed = true
            }
            if (expandedItemIndex != null) {
                expandedItemIndex = null; longPressPosition = null; showPopupForIndex =
                    null; changed = true
            }
            if (isSearchActive) {
                isSearchActive =
                    false; /* searchQuery 会在 isSearchActive 的 LaunchedEffect 中被清除 */ changed =
                    true
            }
            if (changed) Log.d("AppDrawerContent", "因 loadedHistoryIndex 变为 null，已清除状态。")
        }
    }
    LaunchedEffect(expandedItemIndex) {
        if (expandedItemIndex == null) {
            longPressPosition = null; showPopupForIndex = null
        }
        if (expandedItemIndex != showPopupForIndex && showPopupForIndex != null) {
            showPopupForIndex = null
        }
    }

    LaunchedEffect(isSearchActive, keyboardController) {
        if (isSearchActive) {
            Log.d("AppDrawerContent", "搜索激活，请求焦点并尝试显示键盘...")
            delay(60)
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            Log.d("AppDrawerContent", "搜索取消，隐藏键盘并清除焦点")
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            searchQuery = "" // 当搜索被停用时清空搜索查询
        }
    }

    val filteredItems = remember(searchQuery, historicalConversations, isSearchActive) {
        if (!isSearchActive || searchQuery.isBlank()) {
            // 非搜索状态或搜索查询为空，显示所有历史记录，不生成预览
            historicalConversations.mapIndexed { index, conversation ->
                FilteredConversationItem(index, conversation)
            }
        } else {
            // 搜索状态且查询非空，过滤并标记哪些需要预览（预览本身在 item 中生成）
            historicalConversations.mapIndexedNotNull { index, conversation ->
                val matches = conversation.any { message ->
                    message.text.contains(searchQuery, ignoreCase = true)
                }
                if (matches) FilteredConversationItem(index, conversation) else null
            }
        }
    }

    val targetWidth = if (isSearchActive) screenWidth else defaultDrawerWidth
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = EXPAND_ANIMATION_DURATION_MS),
        label = "drawerWidthAnimation"
    )

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false // 触发 isSearchActive 的 LaunchedEffect
    }

    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .width(animatedWidth),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // animateContentSize 可以平滑由于上方元素（如下面的 Spacer 高度变化）带来的高度变化
                // 虽然“聊天”标题固定了，但保留此 Modifier 对于未来可能的布局调整有益
                .animateContentSize(animationSpec = tween(durationMillis = CONTENT_CHANGE_ANIMATION_DURATION_MS))
        ) {
            val textFieldInteractionSource = remember { MutableInteractionSource() }
            val isTextFieldFocused by textFieldInteractionSource.collectIsFocusedAsState()

            LaunchedEffect(isTextFieldFocused) {
                if (isTextFieldFocused && !isSearchActive) {
                    isSearchActive = true
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        // 如果用户开始输入，并且当前不是搜索状态，则激活搜索
                        if (it.isNotBlank() && !isSearchActive) {
                            isSearchActive = true
                        }
                        // (可选) 如果输入被清空，并且搜索框没有焦点，可以考虑自动退出搜索模式
                        // else if (it.isBlank() && isSearchActive && !isTextFieldFocused) {
                        // isSearchActive = false
                        // }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text("搜索历史记录") },
                    leadingIcon = {
                        Crossfade(
                            targetState = isSearchActive,
                            animationSpec = tween(EXPAND_ANIMATION_DURATION_MS),
                            label = "SearchIconCrossfade"
                        ) { active ->
                            if (active) {
                                IconButton(
                                    onClick = { isSearchActive = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowBack,
                                        "返回",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { isSearchActive = true },
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
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, "清除搜索")
                            }
                        }
                    },
                    shape = RoundedCornerShape(50.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = searchBackgroundColor,
                        unfocusedContainerColor = searchBackgroundColor,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true,
                    interactionSource = textFieldInteractionSource,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
            }

            // --- “新建会话” 和 “清空记录” 按钮 (始终可见) ---
            Column {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onNewChatClick,
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
                        Icon(Icons.Filled.AddCircleOutline, "新建会话图标", tint = Color.Black)
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "新建会话",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Button(
                    onClick = { showClearAllConfirm = true },
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
                        Icon(Icons.Filled.ClearAll, "清空记录图标", tint = Color.Black)
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "清空记录",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
            }

            // --- "聊天" 标题 (始终可见) ---
            Column { // 使用 Column 包裹，即使只有一个元素，也便于未来扩展或统一padding
                Spacer(Modifier.height(16.dp)) // 与列表的间距
                Text(
                    text = "聊天",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // --- 标题结束 ---

            // --- 列表显示区域 ---
            Box(modifier = Modifier.weight(1f)) {
                when {
                    // 情况1: 没有任何历史记录
                    historicalConversations.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无聊天记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 情况2: 正在搜索，查询非空，但没有匹配结果
                    isSearchActive && searchQuery.isNotBlank() && filteredItems.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 情况3: 其他情况（有历史记录，或者搜索有结果，或者搜索但查询为空）
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = filteredItems, // 使用过滤后的列表
                                key = { item -> item.originalIndex }
                            ) { itemData ->
                                val originalIndex = itemData.originalIndex
                                val conversation = itemData.conversation

                                // 在 item 作用域内生成预览文本 (如果需要)
                                val previewTextToShow: AnnotatedString? =
                                    if (isSearchActive && searchQuery.isNotBlank()) {
                                        // 查找此对话中第一条匹配的消息来生成预览
                                        conversation.firstNotNullOfOrNull { msg ->
                                            if (msg.text.contains(searchQuery, ignoreCase = true)) {
                                                rememberGeneratedPreviewSnippet(
                                                    msg.text,
                                                    searchQuery
                                                )
                                            } else null
                                        }
                                    } else {
                                        null // 非搜索或查询为空，不显示预览
                                    }

                                val isActuallyActive = loadedHistoryIndex == originalIndex
                                val isCurrentlyExpanded = expandedItemIndex == originalIndex

                                val revealFraction by animateFloatAsState(
                                    targetValue = if (isCurrentlyExpanded && !selectedSet.contains(
                                            originalIndex
                                        )
                                    ) 1f else 0f,
                                    animationSpec = tween(durationMillis = REVEAL_ANIMATION_DURATION_MS),
                                    label = "revealFractionAnim",
                                    finishedListener = { finishedValue ->
                                        if (finishedValue == 1.0f && expandedItemIndex == originalIndex) {
                                            showPopupForIndex = originalIndex
                                        }
                                    }
                                )
                                val shadowColorDark = Color.Black.copy(alpha = targetShadowAlpha)
                                val shadowColorLight = Color.Black.copy(alpha = 0.03f)
                                val shadowGradientBrush =
                                    remember(shadowColorDark, shadowColorLight) {
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                shadowColorDark,
                                                shadowColorLight
                                            )
                                        )
                                    }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(defaultItemBackgroundColor)
                                        .drawBehind {
                                            if (revealFraction > 0f) {
                                                val revealWidth = size.width * revealFraction
                                                drawRect(
                                                    brush = shadowGradientBrush,
                                                    size = Size(revealWidth, size.height)
                                                )
                                            }
                                        }
                                        .pointerInput(originalIndex) {
                                            detectTapGestures(
                                                onPress = { tryAwaitRelease() },
                                                onTap = {
                                                    val wasExpanded =
                                                        expandedItemIndex == originalIndex
                                                    expandedItemIndex = null; longPressPosition =
                                                    null; showPopupForIndex = null
                                                    if (!wasExpanded) {
                                                        selectedSet.clear(); onConversationClick(
                                                            originalIndex
                                                        )
                                                    }
                                                },
                                                onLongPress = { offset ->
                                                    showPopupForIndex = null; longPressPosition =
                                                    offset; expandedItemIndex = originalIndex
                                                }
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row( // 主要对话标题行
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isActuallyActive) {
                                                Spacer(Modifier.width(16.dp)); Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(Color.Black, CircleShape)
                                                ); Spacer(Modifier.width(8.dp))
                                            } else {
                                                Spacer(Modifier.width(32.dp)) // 对齐没有指示器的项
                                            }
                                            DrawerConversationItemContent( // 显示对话的第一条消息
                                                conversation = conversation,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 16.dp)
                                            )
                                        }

                                        // --- 显示匹配的预览文本 ---
                                        if (previewTextToShow != null) {
                                            Spacer(Modifier.height(1.dp)) // 预览和标题之间的小间距
                                            Text(
                                                text = previewTextToShow,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.9f
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier
                                                    .padding(
                                                        start = 32.dp,
                                                        end = 16.dp,
                                                        bottom = 8.dp,
                                                        top = 0.dp
                                                    ) // 调整内边距与标题对齐
                                                    .fillMaxWidth(),
                                                maxLines = 2, // 限制预览行数
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            //Spacer(Modifier.height(4.dp)) // 预览文本下方的额外间距
                                        } else {
                                            // 如果没有预览文本，为了保持列表项高度的一致性（可选）
                                            // 可以考虑添加一个固定高度的 Spacer，但这通常不是必需的
                                            // Spacer(Modifier.height(0.dp))
                                        }


                                        // --- 长按弹窗 (逻辑保持不变) ---
                                        if (showPopupForIndex == originalIndex && longPressPosition != null) {
                                            val currentLongPressPosition = longPressPosition!!
                                            Popup(
                                                popupPositionProvider = object :
                                                    PopupPositionProvider {
                                                    override fun calculatePosition(
                                                        anchorBounds: IntRect,
                                                        windowSize: IntSize,
                                                        layoutDirection: LayoutDirection,
                                                        popupContentSize: IntSize
                                                    ): IntOffset {
                                                        val x =
                                                            anchorBounds.left + currentLongPressPosition.x.roundToInt()
                                                        val y =
                                                            anchorBounds.top + currentLongPressPosition.y.roundToInt()
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
                                                onDismissRequest = { expandedItemIndex = null },
                                                properties = PopupProperties(focusable = false) // 弹窗可获焦点
                                            ) {
                                                val isRenameEnabledForThisPopup =
                                                    !selectedSet.contains(originalIndex)
                                                Surface(
                                                    color = Color.White,
                                                    shadowElevation = 8.dp,
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.widthIn(max = 120.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(
                                                            vertical = 4.dp,
                                                            horizontal = 8.dp
                                                        )
                                                    ) {
                                                        // 重命名选项
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 12.dp)
                                                                .clickable(
                                                                    enabled = isRenameEnabledForThisPopup,
                                                                    onClick = {
                                                                        if (isRenameEnabledForThisPopup) {
                                                                            onRenameRequest(
                                                                                originalIndex
                                                                            ); expandedItemIndex =
                                                                                null
                                                                        }
                                                                    },
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.DriveFileRenameOutline,
                                                                "重命名",
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
                                                        Divider(color = Color.LightGray.copy(alpha = 0.5f))
                                                        // 删除选项
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 12.dp)
                                                                .clickable(
                                                                    onClick = {
                                                                        if (!selectedSet.contains(
                                                                                originalIndex
                                                                            )
                                                                        ) selectedSet.add(
                                                                            originalIndex
                                                                        )
                                                                        if (selectedSet.isEmpty() && expandedItemIndex != null) selectedSet.add(
                                                                            expandedItemIndex!!
                                                                        ) // 确保当前项被选中
                                                                        expandedItemIndex =
                                                                            null; showDeleteConfirm =
                                                                        true
                                                                    },
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.Delete,
                                                                "删除",
                                                                tint = Color.Black,
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
                                    } // Column for item content end
                                } // Box for item root end
                            } // items end
                        } // LazyColumn end
                    } // else end
                } // when end
            } // Box for list area end
        } // Main Column for drawer content end

        // --- AlertDialogs (逻辑保持不变) ---
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(if (selectedSet.size > 1) "确定删除所有所选项？" else if (selectedSet.size == 1) "确定删除所选项？" else "确定删除此项？") },
                confirmButton = {
                    TextButton(onClick = {
                        val indicesToDelete =
                            selectedSet.toList(); selectedSet.clear(); expandedItemIndex = null;
                        longPressPosition = null; showPopupForIndex = null; showDeleteConfirm =
                        false;
                        indicesToDelete.sortedDescending().forEach(onDeleteRequest)
                    }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) {
                        Text(
                            "确定"
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirm = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
                    ) { Text("取消") }
                },
                containerColor = Color.White,
                titleContentColor = Color.Black,
                textContentColor = Color.Black
            )
        }
        if (showClearAllConfirm) {
            AlertDialog(
                onDismissRequest = { showClearAllConfirm = false },
                title = { Text("确定清空所有聊天记录？") },
                text = { Text("此操作无法撤销，所有聊天记录将被永久删除。") },
                confirmButton = {
                    TextButton(onClick = {
                        onClearAllConversationsRequest(); showClearAllConfirm =
                        false; selectedSet.clear();
                        expandedItemIndex = null; longPressPosition = null; showPopupForIndex = null
                    }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) {
                        Text(
                            "确定清空"
                        )
                    }
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

    } // ModalDrawerSheet end
}

// --- 列表项主要内容 Composable (无变化) ---
@Composable
private fun DrawerConversationItemContent(
    conversation: List<Message>,
    modifier: Modifier = Modifier
) {
    val firstUserMessage = conversation.firstOrNull { it.sender == Sender.User }?.text
    val previewText = firstUserMessage ?: conversation.firstOrNull()?.text ?: "空对话"
    Text(
        text = previewText, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(vertical = 18.dp) // 默认垂直内边距
    )
}