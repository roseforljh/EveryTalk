package com.android.everytalk.ui.screens.MainScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.screens.MainScreen.drawer.* // 导入抽屉子包下的所有内容
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable

// Helper data class for processed items
internal data class ProcessedDrawerItems(
    val pinned: List<FilteredConversationItem>,
    val custom: Map<String, List<FilteredConversationItem>>,
    val ungrouped: List<FilteredConversationItem>
)

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
    getFullTextForIndex: (Int) -> String,
    onAboutClick: () -> Unit,
    onImageGenerationClick: () -> Unit,
    isLoadingHistoryData: Boolean = false, // 新增：历史数据加载状态
    isImageGenerationMode: Boolean,
    expandedItemIndex: Int?, // 新增：展开项状态
    onExpandItem: (index: Int?) -> Unit, // 新增：展开项回调
    pinnedIds: Set<String>, // 新增：置顶集合
    onTogglePin: (Int) -> Unit, // 新增：置顶切换回调
    conversationGroups: Map<String, List<String>>,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMoveConversationToGroup: (Int, String?, Boolean) -> Unit,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedSet = remember { mutableStateListOf<Int>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var longPressPosition by remember { mutableStateOf<Offset?>(null) } // 长按位置，用于定位弹出菜单
    var renamingIndex by remember { mutableStateOf<Int?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showMoveToGroupDialog by remember { mutableStateOf<Int?>(null) }
    var isAddGroupButtonVisible by remember { mutableStateOf(false) } // 控制"创建分组"按钮的可见性（默认隐藏）
    var isGroupSectionExpanded by remember { mutableStateOf(false) } // 控制分组区域的展开/收起（默认收起）

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    LaunchedEffect(loadedHistoryIndex) {
        if (loadedHistoryIndex == null) {
            selectedSet.clear()
            onExpandItem(null)
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

    // 解析会话稳定ID的辅助函数（与 AppViewModel 中的逻辑一致）
    fun resolveStableId(conversation: List<Message>): String? {
        return conversation.firstOrNull { it.sender == com.android.everytalk.data.DataClass.Sender.User }?.id
            ?: conversation.firstOrNull { it.sender == com.android.everytalk.data.DataClass.Sender.System && !it.isPlaceholderName }?.id
            ?: conversation.firstOrNull()?.id
    }
    
    val processedItems = remember(currentSearchQuery, historicalConversations, isSearchActive, pinnedIds, conversationGroups) {
        derivedStateOf {
            val baseItems = if (!isSearchActive || currentSearchQuery.isBlank()) {
                historicalConversations.mapIndexed { index, conversation ->
                    FilteredConversationItem(index, conversation)
                }
            } else {
                historicalConversations.mapIndexedNotNull { index, conversation ->
                    val searchableMessages = conversation.take(3)
                    val matches = searchableMessages.any { message ->
                        message.text.contains(currentSearchQuery, ignoreCase = true)
                    }
                    if (matches) FilteredConversationItem(index, conversation) else null
                }
            }

            val pinned = baseItems.filter {
                val stableId = resolveStableId(it.conversation)
                stableId != null && pinnedIds.contains(stableId)
            }

            val custom = mutableMapOf<String, MutableList<FilteredConversationItem>>()
            val ungrouped = mutableListOf<FilteredConversationItem>()

            baseItems.forEach { item ->
                val stableId = resolveStableId(item.conversation)
                val groupName = conversationGroups.entries.find { it.value.contains(stableId) }?.key
                
                if (groupName != null) {
                    custom.getOrPut(groupName) { mutableListOf() }.add(item)
                } else {
                    // 如果一个项目同时被置顶，它也会出现在“对话”中
                    if (!pinned.any { p -> p.originalIndex == item.originalIndex}) {
                        ungrouped.add(item)
                    }
                }
            }
            
            ProcessedDrawerItems(pinned, custom, ungrouped)
        }
    }.value

    val targetWidth = if (isSearchActive) screenWidth else DEFAULT_DRAWER_WIDTH
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = EXPAND_ANIMATION_DURATION_MS),
        label = "drawerWidthAnimation"
    )

    @Composable
    fun ConversationItem(itemData: FilteredConversationItem) {
        val stableId = resolveStableId(itemData.conversation)
        val isPinned = stableId != null && pinnedIds.contains(stableId)

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
                    selectedSet.clear()
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
                    else if (selectedSet.isEmpty() && expandedItemIndex == index) selectedSet.add(index)
                    showDeleteConfirm = true
                },
                onTogglePin = { index ->
                    onTogglePin(index)
                },
                isPinned = isPinned,
                expandedItemIndex = expandedItemIndex,
                onExpandItem = { index, position ->
                    val newIndex = if (expandedItemIndex == index) null else index
                    onExpandItem(newIndex)
                    if (newIndex != null) {
                        longPressPosition = position
                    }
                },
                onCollapseMenu = {
                    onExpandItem(null)
                },
                longPressPositionForMenu = longPressPosition,
                groups = conversationGroups.keys.toList(),
                onMoveToGroup = { index, group ->
                    onMoveConversationToGroup(index, group, isImageGenerationMode)
                },
                onMoveToGroupClick = { index ->
                    showMoveToGroupDialog = index
                }
            )
        }
    }

    BackHandler(enabled = isSearchActive) {
        onSearchActiveChange(false)
    }

    // Bug修复：当有条目展开时，优先处理返回事件为收起条目
    BackHandler(enabled = expandedItemIndex != null) {
        onExpandItem(null)
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
                        .focusRequester(focusRequester)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(50.dp),
                            spotColor = Color.Black.copy(alpha = 0.25f),
                            ambientColor = Color.Black.copy(alpha = 0.20f)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(50.dp)
                        ),
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
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
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

            // --- "分组" 标题行 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)
                    .clickable {
                        isAddGroupButtonVisible = !isAddGroupButtonVisible
                        isGroupSectionExpanded = !isGroupSectionExpanded
                    }, // 使整行可点击以切换按钮可见性和展开状态
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "分组",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 使用 animateFloatAsState 为 alpha 值添加动画，避免布局跳动
                val addGroupButtonAlpha by animateFloatAsState(
                    targetValue = if (isAddGroupButtonVisible) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "addGroupButtonAlpha"
                )
                IconButton(
                    onClick = {
                        // 只有在按钮可见时才响应点击
                        if (isAddGroupButtonVisible) {
                            showCreateGroupDialog = true
                        }
                    },
                    enabled = isAddGroupButtonVisible,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer {
                            alpha = addGroupButtonAlpha
                        }
                ) {
                    Icon(
                        Icons.Filled.Add,
                        "创建分组",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = addGroupButtonAlpha)
                    )
                }
            }

            // --- 分组列表显示区域 ---
            AnimatedVisibility(
                visible = isGroupSectionExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 200)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 200))
            ) {
                if (conversationGroups.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp), // 限制分组区域的最大高度
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        // 显示所有分组(包括空分组)
                        conversationGroups.keys.forEach { groupName ->
                            item(key = "group_header_$groupName") {
                                CollapsibleGroupHeader(
                                    groupName = groupName,
                                    isExpanded = expandedGroups.contains(groupName),
                                    onToggleExpand = { onToggleGroup(groupName) },
                                    onRename = { newName -> onRenameGroup(groupName, newName) },
                                    onDelete = { onDeleteGroup(groupName) }
                                )
                            }
                            if (expandedGroups.contains(groupName)) {
                                val groupItems = processedItems.custom[groupName] ?: emptyList()
                                if (groupItems.isNotEmpty()) {
                                    items(
                                        items = groupItems,
                                        key = { item -> "group_${groupName}_${item.originalIndex}" }
                                    ) { itemData ->
                                        ConversationItem(itemData)
                                    }
                                } else {
                                    // 空分组显示提示
                                    item(key = "group_empty_$groupName") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "暂无分组",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 如果没有分组，显示提示文本
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无分组",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // --- "会话" 标题行 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "会话",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- 会话列表显示区域 ---
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

                    isSearchActive && currentSearchQuery.isNotBlank() && processedItems.pinned.isEmpty() && processedItems.custom.isEmpty() && processedItems.ungrouped.isEmpty() -> {
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
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            // "已置顶" 分组
                            if (processedItems.pinned.isNotEmpty()) {
                                item(key = "pinned_header") {
                                    CollapsibleGroupHeader(
                                        groupName = "已置顶",
                                        isExpanded = expandedGroups.contains("pinned"),
                                        onToggleExpand = { onToggleGroup("pinned") },
                                        isPinnedGroup = true
                                    )
                                }
                                // 修复: 直接使用 items,不需要 AnimatedVisibility 包装
                                if (expandedGroups.contains("pinned")) {
                                    items(
                                        items = processedItems.pinned,
                                        key = { item -> "pinned_${item.originalIndex}" }
                                    ) { itemData ->
                                        ConversationItem(itemData)
                                    }
                                }
                            }

                            // "对话" (未分组和未置顶) - 始终显示,不需要展开/收起
                            if (processedItems.ungrouped.isNotEmpty()) {
                                items(
                                    items = processedItems.ungrouped,
                                    key = { item -> "ungrouped_${item.originalIndex}" }
                                ) { itemData ->
                                    ConversationItem(itemData)
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
                    onExpandItem(null) // 如果有菜单打开，也关闭它
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
                    onExpandItem(null)
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
                var newName by remember(index) { mutableStateOf(getFullTextForIndex(index)) }
                AlertDialog(
                    onDismissRequest = { renamingIndex = null },
                    title = { Text("重命名会话") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            shape = RoundedCornerShape(32.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onRenameRequest(index, newName)
                                renamingIndex = null
                            },
                            shape = RoundedCornerShape(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
                            )
                        ) {
                            Text("确定", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { renamingIndex = null },
                            shape = RoundedCornerShape(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
                            )
                        ) {
                            Text("取消", fontWeight = FontWeight.Bold)
                        }
                    },
                    shape = RoundedCornerShape(32.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
            
            if (showCreateGroupDialog) {
                CreateGroupDialog(
                    onDismiss = { showCreateGroupDialog = false },
                    onConfirm = { groupName ->
                        onCreateGroup(groupName)
                        showCreateGroupDialog = false
                        // 创建分组后自动展开分组区域
                        isGroupSectionExpanded = true
                        isAddGroupButtonVisible = true
                    }
                )
            }

            if (showMoveToGroupDialog != null) {
                val conversationIndex = showMoveToGroupDialog!!
                val conversation = historicalConversations.getOrNull(conversationIndex)
                val stableId = conversation?.let { resolveStableId(it) }
                val isCurrentlyGrouped = stableId?.let { id ->
                    conversationGroups.any { it.value.contains(id) }
                } ?: false

                MoveToGroupDialog(
                    groups = conversationGroups.keys.toList().filter { groupName ->
                        val members = conversationGroups[groupName]
                        !members.orEmpty().contains(stableId)
                    },
                    isCurrentlyGrouped = isCurrentlyGrouped,
                    onDismiss = { showMoveToGroupDialog = null },
                    onConfirm = { group ->
                        onMoveConversationToGroup(conversationIndex, group, isImageGenerationMode)
                        showMoveToGroupDialog = null
                    }
                )
            }
        }
    }
}

@Composable
fun CollapsibleGroupHeader(
    groupName: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    isPinnedGroup: Boolean = false
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(groupName) }
    var showMenu by remember { mutableStateOf(false) }

    // 为箭头图标添加旋转动画
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "arrowRotation"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        onClick = onToggleExpand,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        rotationZ = arrowRotation
                    }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = groupName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            
            // 只有非置顶分组才显示菜单按钮
            if (!isPinnedGroup && (onRename != null || onDelete != null)) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            "更多选项",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Edit, "重命名")
                                }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, "删除")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showRenameDialog && onRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    shape = RoundedCornerShape(8.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRename(newName)
                        }
                        showRenameDialog = false
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("重命名")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}
