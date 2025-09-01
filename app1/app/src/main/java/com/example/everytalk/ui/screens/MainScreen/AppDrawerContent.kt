package com.example.everytalk.ui.screens.MainScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message // 假设 Message 和 FilteredConversationItem 在这里
import com.example.everytalk.ui.screens.MainScreen.drawer.* // 导入抽屉子包下的所有内容
import kotlinx.coroutines.delay

// --- 常量定义 ---
private val DEFAULT_DRAWER_WIDTH = 320.dp
private const val EXPAND_ANIMATION_DURATION_MS = 200
private const val CONTENT_CHANGE_ANIMATION_DURATION_MS = 200
// 搜索框背景色将动态使用主题色
private val LIST_ITEM_MIN_HEIGHT = 48.dp // <--- 控制历史列表项的最小高度

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun AppDrawerContent(
    historicalConversations: List<List<Message>>,
    loadedHistoryIndex: Int?,
    isSearchActive: Boolean,
    currentSearchQuery: String,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onConversationClick: (Int) -> Unit,
    onImageGenerationConversationClick: (Int) -> Unit, // 新增：图像模式历史点击回调
    onNewChatClick: () -> Unit,
    onRenameRequest: (index: Int, newName: String) -> Unit,
    onDeleteRequest: (index: Int) -> Unit,
   onClearAllConversationsRequest: () -> Unit,
   onClearAllImageGenerationConversationsRequest: () -> Unit,
   showClearImageHistoryDialog: Boolean,
   onShowClearImageHistoryDialog: () -> Unit,
   onDismissClearImageHistoryDialog: () -> Unit,
    getPreviewForIndex: (Int) -> String,
    onAboutClick: () -> Unit,
    onImageGenerationClick: () -> Unit,
    isLoadingHistoryData: Boolean = false, // 新增：历史数据加载状态
    isImageGenerationMode: Boolean,
    modifier: Modifier = Modifier
) {
    var expandedItemIndex by remember { mutableStateOf<Int?>(null) }
    val selectedSet = remember { mutableStateListOf<Int>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var longPressPosition by remember { mutableStateOf<Offset?>(null) } // 长按位置，用于定位弹出菜单
    var renamingIndex by remember { mutableStateOf<Int?>(null) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    LaunchedEffect(loadedHistoryIndex) {
        if (loadedHistoryIndex == null) {
            selectedSet.clear()
            expandedItemIndex = null
        }
    }

    LaunchedEffect(expandedItemIndex) {
        if (expandedItemIndex == null) {
            longPressPosition = null
        }
    }

    LaunchedEffect(isSearchActive, keyboardController) {
        if (isSearchActive) {
            delay(100)
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // 优化：使用 derivedStateOf 和分页加载来提升性能
    val filteredItems = remember(currentSearchQuery, historicalConversations, isSearchActive) {
        derivedStateOf {
            if (!isSearchActive || currentSearchQuery.isBlank()) {
                // 非搜索模式：只显示前50个对话，实现懒加载
                val itemsToShow = if (historicalConversations.size > 50) {
                    historicalConversations.take(50)
                } else {
                    historicalConversations
                }
                itemsToShow.mapIndexed { index, conversation ->
                    FilteredConversationItem(index, conversation)
                }
            } else {
                // 搜索模式：异步搜索，避免阻塞UI
                historicalConversations.mapIndexedNotNull { index, conversation ->
                    // 优化搜索：只搜索前几条消息，避免搜索整个对话
                    val searchableMessages = conversation.take(3)
                    val matches = searchableMessages.any { message ->
                        message.text.contains(currentSearchQuery, ignoreCase = true)
                    }
                    if (matches) FilteredConversationItem(index, conversation) else null
                }
            }
        }
    }.value

    val targetWidth = if (isSearchActive) screenWidth else DEFAULT_DRAWER_WIDTH
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = EXPAND_ANIMATION_DURATION_MS),
        label = "drawerWidthAnimation"
    )

    BackHandler(enabled = isSearchActive) {
        onSearchActiveChange(false)
    }

    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .width(animatedWidth)
            .shadow(
                elevation = 6.dp,
                clip = false,
                spotColor = Color.Black.copy(alpha = 0.50f),
                ambientColor = Color.Black.copy(alpha = 0.40f),
            ),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        drawerTonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .animateContentSize(animationSpec = tween(durationMillis = CONTENT_CHANGE_ANIMATION_DURATION_MS))
        ) {
            val textFieldInteractionSource = remember { MutableInteractionSource() }
            val isTextFieldFocused by textFieldInteractionSource.collectIsFocusedAsState()

            LaunchedEffect(isTextFieldFocused) {
                if (isTextFieldFocused && !isSearchActive) {
                    onSearchActiveChange(true)
                }
            }

            // --- 搜索框区域 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = currentSearchQuery,
                    onValueChange = { newQuery ->
                        onSearchQueryChange(newQuery)
                        if (newQuery.isNotBlank() && !isSearchActive) {
                            onSearchActiveChange(true)
                        }
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
                                    onClick = { onSearchActiveChange(false) },
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
                                    onClick = { onSearchActiveChange(true) },
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
                        if (currentSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Close, "清除搜索")
                            }
                        }
                    },
                    shape = RoundedCornerShape(50.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true,
                    interactionSource = textFieldInteractionSource,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
            }

            // --- “新建会话” 和 “清空记录” 按钮 ---
            Column {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isImageGenerationMode) {
                            onImageGenerationClick()
                        } else {
                            onNewChatClick()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface
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
                        Icon(
                            Icons.Filled.AddCircleOutline,
                            "新建会话图标",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            if (isImageGenerationMode) "新建图像生成" else "新建会话",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Button(
                    onClick = {
                       if (isImageGenerationMode) {
                           onShowClearImageHistoryDialog()
                       } else {
                           showClearAllConfirm = true
                       }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface
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
                        Icon(
                            Icons.Filled.ClearAll,
                            "清空记录图标",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "清空记录",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                if (isImageGenerationMode) {
                    Button(
                        onClick = {
                            onNewChatClick()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                            Icon(
                                Icons.Filled.TextFields,
                                "文本生成图标",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(20.dp))
                            Text(
                                "文本生成",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (isImageGenerationMode) {
                                // Already in image gen mode, so just start a new chat
                                onNewChatClick()
                            } else {
                                onImageGenerationClick()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                            Icon(
                                Icons.Filled.Image,
                                "图像生成图标",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(20.dp))
                            Text(
                                "图像生成",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize
                            )
                        }
                    }
                }
            }

            // --- "聊天" 列表标题 ---
            Column {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (isImageGenerationMode) "图像生成历史" else "聊天",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- 列表显示区域 ---
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoadingHistoryData -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "正在加载历史记录...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    historicalConversations.isEmpty() && !isLoadingHistoryData -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无聊天记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

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

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // 添加性能优化配置
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = filteredItems,
                                key = { item -> if (isImageGenerationMode) "image_conversation_${item.originalIndex}" else "text_conversation_${item.originalIndex}" }, // 跨模式唯一key，避免复用
                                contentType = { "conversation_item" } // 添加内容类型以优化回收
                            ) { itemData ->
                                // --- 用 Box 包裹并设置最小高度 ---
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = LIST_ITEM_MIN_HEIGHT)
                                ) {
                                    DrawerConversationListItem(
                                        itemData = itemData,
                                        isSearchActive = isSearchActive,
                                        currentSearchQuery = currentSearchQuery,
                                        loadedHistoryIndex = loadedHistoryIndex,
                                        getPreviewForIndex = getPreviewForIndex,
                                        onConversationClick = { index ->
                                            selectedSet.clear() // 清空之前的选择
                                            // 关键修复：根据当前模式调用正确的加载方法
                                            if (isImageGenerationMode) {
                                                onImageGenerationConversationClick(index)
                                            } else {
                                                onConversationClick(index)
                                            }
                                        },
                                        onRenameRequest = { index ->
                                            renamingIndex = index
                                        },
                                        onDeleteTriggered = { index ->
                                            if (!selectedSet.contains(index)) selectedSet.add(index)
                                            else if (selectedSet.isEmpty() && expandedItemIndex == index) selectedSet.add(
                                                index
                                            )
                                            showDeleteConfirm = true
                                        },
                                        expandedItemIndex = expandedItemIndex,
                                        onExpandItem = { index, position ->
                                            expandedItemIndex =
                                                if (expandedItemIndex == index) null else index
                                            if (expandedItemIndex != null) { // 只有当要展开时才记录位置
                                                longPressPosition = position
                                            }
                                        },
                                        onCollapseMenu = {
                                            expandedItemIndex = null
                                        },
                                        longPressPositionForMenu = longPressPosition
                                    )
                                }
                            }
                            
                            // 添加加载更多项（如果有更多数据）
                            if (!isSearchActive && historicalConversations.size > 50) {
                                item(key = "load_more_indicator") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "显示前50条对话，共${historicalConversations.size}条",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp)) // Add some space before the button
            Button(
                onClick = { onAboutClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(40.dp), // Slightly shorter height
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.onSurface
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
                    Icon(
                        Icons.Filled.Info,
                        "关于图标",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "关于",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
 
             // --- 对话框 ---
             DeleteConfirmationDialog(
                 showDialog = showDeleteConfirm,
                selectedItemCount = selectedSet.size,
                onDismiss = {
                    showDeleteConfirm = false
                    selectedSet.clear()
                },
                onConfirm = {
                    val indicesToDelete = selectedSet.toList()
                    showDeleteConfirm = false // 关闭对话框
                    selectedSet.clear()
                    expandedItemIndex = null // 如果有菜单打开，也关闭它
                    // 从后往前删除，避免索引错位
                    indicesToDelete.sortedDescending().forEach(onDeleteRequest)
                }
            )

            ClearAllConfirmationDialog(
                showDialog = showClearAllConfirm,
                onDismiss = { showClearAllConfirm = false },
                onConfirm = {
                    showClearAllConfirm = false // 关闭对话框
                    onClearAllConversationsRequest()
                    selectedSet.clear()
                    expandedItemIndex = null
                }
            )

           ClearImageHistoryConfirmationDialog(
               showDialog = showClearImageHistoryDialog,
               onDismiss = onDismissClearImageHistoryDialog,
               onConfirm = {
                   onClearAllImageGenerationConversationsRequest()
                   onDismissClearImageHistoryDialog()
               }
           )

            renamingIndex?.let { index ->
                var newName by remember(index) { mutableStateOf(getPreviewForIndex(index)) }
                AlertDialog(
                    onDismissRequest = { renamingIndex = null },
                    title = { Text("重命名会话") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onRenameRequest(index, newName)
                                renamingIndex = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { renamingIndex = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Text("取消")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}