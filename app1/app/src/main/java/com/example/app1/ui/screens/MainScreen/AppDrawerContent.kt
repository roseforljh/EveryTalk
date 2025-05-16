package com.example.app1.ui.screens.MainScreen

import android.util.Log
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
import com.example.app1.data.DataClass.Message // 假设 Message 和 FilteredConversationItem 在这里
import com.example.app1.ui.screens.MainScreen.drawer.* // 导入抽屉子包下的所有内容
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- 常量定义 ---
private val DEFAULT_DRAWER_WIDTH = 320.dp
private const val EXPAND_ANIMATION_DURATION_MS = 200
private const val CONTENT_CHANGE_ANIMATION_DURATION_MS = 200
private val SEARCH_BACKGROUND_COLOR = Color(0xFFF0F0F0)
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
    onNewChatClick: () -> Unit,
    onRenameRequest: (index: Int) -> Unit,
    onDeleteRequest: (index: Int) -> Unit,
    onClearAllConversationsRequest: () -> Unit,
    getPreviewForIndex: (Int) -> String,
    modifier: Modifier = Modifier
) {
    var expandedItemIndex by remember { mutableStateOf<Int?>(null) }
    val selectedSet = remember { mutableStateListOf<Int>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var longPressPosition by remember { mutableStateOf<Offset?>(null) } // 长按位置，用于定位弹出菜单

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

    val filteredItems = remember(currentSearchQuery, historicalConversations, isSearchActive) {
        if (!isSearchActive || currentSearchQuery.isBlank()) {
            historicalConversations.mapIndexed { index, conversation ->
                FilteredConversationItem(index, conversation)
            }
        } else {
            historicalConversations.mapIndexedNotNull { index, conversation ->
                val matches = conversation.any { message ->
                    message.text.contains(currentSearchQuery, ignoreCase = true)
                }
                if (matches) FilteredConversationItem(index, conversation) else null
            }
        }
    }

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
                        focusedContainerColor = SEARCH_BACKGROUND_COLOR,
                        unfocusedContainerColor = SEARCH_BACKGROUND_COLOR,
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
                    onClick = { onNewChatClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White, // Or MaterialTheme.colorScheme.primaryContainer
                        contentColor = Color.Black // Or MaterialTheme.colorScheme.onPrimaryContainer
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
                            tint = Color.Black
                        ) // Or MaterialTheme.colorScheme.onPrimaryContainer
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "新建会话",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black, // Or MaterialTheme.colorScheme.onPrimaryContainer
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
                        containerColor = Color.White, // Or MaterialTheme.colorScheme.secondaryContainer
                        contentColor = Color.Black // Or MaterialTheme.colorScheme.onSecondaryContainer
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
                            tint = Color.Black
                        ) // Or MaterialTheme.colorScheme.onSecondaryContainer
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "清空记录",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black, // Or MaterialTheme.colorScheme.onSecondaryContainer
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                }
            }

            // --- "聊天" 列表标题 ---
            Column {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "聊天",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- 列表显示区域 ---
            Box(modifier = Modifier.weight(1f)) {
                when {
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
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = filteredItems,
                                key = { item -> item.originalIndex }
                            ) { itemData ->
                                // --- 用 Box 包裹并设置最小高度 ---
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = LIST_ITEM_MIN_HEIGHT) // <--- 应用最小高度
                                ) {
                                    DrawerConversationListItem(
                                        itemData = itemData,
                                        isSearchActive = isSearchActive,
                                        currentSearchQuery = currentSearchQuery,
                                        loadedHistoryIndex = loadedHistoryIndex,
                                        getPreviewForIndex = getPreviewForIndex,
                                        onConversationClick = { index ->
                                            selectedSet.clear() // 清空之前的选择
                                            onConversationClick(index)
                                        },
                                        onRenameRequest = { index ->
                                            onRenameRequest(index)
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
                        }
                    }
                }
            }

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
        }
    }
}