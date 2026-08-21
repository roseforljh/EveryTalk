package com.android.everytalk.ui.screens.MainScreen
import com.android.everytalk.statecontroller.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.screens.MainScreen.drawer.* // 导入抽屉子包下的所有内容
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
import com.android.everytalk.ui.components.floatingEdgeGradient
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun AppDrawerContent(
    historicalConversations: List<List<Message>>,
    loadedHistoryIndex: Int?,
    onConversationSearchClick: () -> Unit,
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
    onAppInfoClick: () -> Unit,
    onImageGenerationClick: () -> Unit,
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
    isGroupSectionExpanded: Boolean = false,
    onToggleGroupSection: () -> Unit = {},
    modifier: Modifier = Modifier,
    isLoadingHistoryData: Boolean = false, // 新增：历史数据加载状态
    onShareConversation: (Int) -> Unit = {}, // 新增：分享会话回调
) {
    val selectedSet = remember { mutableStateListOf<Int>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var longPressPosition by remember { mutableStateOf<Offset?>(null) } // 长按位置，用于定位弹出菜单
    var renamingIndex by remember { mutableStateOf<Int?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showMoveToGroupDialog by remember { mutableStateOf<Int?>(null) }
    var isAddGroupButtonVisible by remember(isGroupSectionExpanded) { mutableStateOf(isGroupSectionExpanded) } // 控制"创建分组"按钮的可见性（跟随展开状态）
    // Animation states for deletion
    val deletingGroups = remember { mutableStateListOf<String>() }
    val deletingItems = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
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
    // 解析会话稳定ID的辅助函数（与 AppViewModel 中的逻辑一致）
    fun resolveStableId(conversation: List<Message>): String? {
        return com.android.everytalk.util.ConversationNameHelper.resolveStableId(conversation)
    }
    val processedItems = remember(historicalConversations, pinnedIds, conversationGroups) {
        derivedStateOf {
            val baseItems = historicalConversations.mapIndexed { index, conversation ->
                val stableId = resolveStableId(conversation) ?: "unknown_$index"
                FilteredConversationItem(index, conversation, stableId)
            }
            val pinned = baseItems.filter { it.stableId in pinnedIds }
            val pinnedIndexes = pinned.mapTo(hashSetOf()) { it.originalIndex }
            val custom = mutableMapOf<String, MutableList<FilteredConversationItem>>()
            val ungrouped = mutableListOf<FilteredConversationItem>()
            val groupByConversationId = conversationGroups.entries
                .flatMap { (groupName, ids) -> ids.map { id -> id to groupName } }
                .toMap()
            baseItems.forEach { item ->
                val groupName = groupByConversationId[item.stableId]
                if (groupName != null) {
                    custom.getOrPut(groupName) { mutableListOf() }.add(item)
                } else {
                    // 如果一个项目同时被置顶，它也会出现在“对话”中
                    if (item.originalIndex !in pinnedIndexes) {
                        ungrouped.add(item)
                    }
                }
            }
            ProcessedDrawerItems(pinned, custom, ungrouped)
        }
    }.value
    @Composable
    fun ConversationItem(itemData: FilteredConversationItem, modifier: Modifier = Modifier) {
        val isPinned = itemData.stableId in pinnedIds
        Box(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = LIST_ITEM_MIN_HEIGHT)
        ) {
            DrawerConversationListItem(
                itemData = itemData,
                isSearchActive = false,
                currentSearchQuery = "",
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
                },
                onShareClick = { index ->
                    onShareConversation(index)
                },
            )
        }
    }
    // Bug修复：当有条目展开时，优先处理返回事件为收起条目
    BackHandler(enabled = expandedItemIndex != null) {
        onExpandItem(null)
    }
    val drawerBackground = MaterialTheme.colorScheme.background
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topChromeHeight = statusBarInset + 76.dp
    val bottomChromeHeight = navigationBarInset + 92.dp
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .width(DEFAULT_DRAWER_WIDTH)
            .shadow(
                elevation = 6.dp,
                clip = false,
                spotColor = Color.Black.copy(alpha = 0.50f),
                ambientColor = Color.Black.copy(alpha = 0.40f),
            ),
        // Sheet 自身覆盖系统栏 Insets，避免内部内容背景之外露出后层页面。
        drawerContainerColor = drawerBackground,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(drawerBackground)
                .animateContentSize(animationSpec = tween(durationMillis = CONTENT_CHANGE_ANIMATION_DURATION_MS))
        ) {
            DrawerHeader(
                onSearchClick = onConversationSearchClick,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .zIndex(2f),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topChromeHeight)
                    .floatingEdgeGradient(drawerBackground, fromTop = true)
                    .zIndex(1f),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f),
                contentPadding = PaddingValues(top = topChromeHeight, bottom = bottomChromeHeight),
            ) {
                item(key = "drawer_primary_actions") {
                    DrawerPrimaryActions(
                        isImageGenerationMode = isImageGenerationMode,
                        onNewChatClick = onNewChatClick,
                        onClearClick = {
                            if (isImageGenerationMode) {
                                onShowClearImageHistoryDialog()
                            } else {
                                showClearAllConfirm = true
                            }
                        },
                        onImageGenerationClick = onImageGenerationClick,
                    )
                }
                // --- "分组" 标题行 ---
                item(key = "drawer_group_section_header") {
                    Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)
                    .clickable {
                        isAddGroupButtonVisible = !isGroupSectionExpanded
                        onToggleGroupSection()
                    }, // 使整行可点击以切换按钮可见性和展开状态
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                Text(
                    text = stringResource(R.string.drawer_groups),
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
                        painter = painterResource(R.drawable.ic_plus),
                        stringResource(R.string.drawer_create_group),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = addGroupButtonAlpha)
                    )
                }
                    }
                }
                // 分组区域内容：采用动画控制高度与渐隐，保证收起时平滑过渡
                item(key = "drawer_groups_content") {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isGroupSectionExpanded,
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (conversationGroups.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        stringResource(R.string.drawer_no_groups),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            } else {
                                conversationGroups.keys.forEach { groupName ->
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = !deletingGroups.contains(groupName),
                                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            CollapsibleGroupHeader(
                                                groupName = groupName,
                                                isExpanded = expandedGroups.contains(groupName),
                                                onToggleExpand = { onToggleGroup(groupName) },
                                                onRename = { newName -> onRenameGroup(groupName, newName) },
                                                onDelete = {
                                                    deletingGroups.add(groupName)
                                                    scope.launch {
                                                        delay(300)
                                                        onDeleteGroup(groupName)
                                                        deletingGroups.remove(groupName)
                                                    }
                                                },
                                            )
                                            val isExpanded = expandedGroups.contains(groupName)
                                            val groupItems = processedItems.custom[groupName].orEmpty()
                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = isExpanded,
                                                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                                                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                                            ) {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    if (groupItems.isNotEmpty()) {
                                                        groupItems.forEach { itemData ->
                                                            androidx.compose.animation.AnimatedVisibility(
                                                                visible = !deletingItems.contains(itemData.stableId),
                                                                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                                                                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                                                            ) {
                                                                ConversationItem(itemData)
                                                            }
                                                        }
                                                    } else {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 12.dp),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Text(
                                                                stringResource(R.string.drawer_no_groups),
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.Bold,
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
                // --- "会话" 标题行 ---
                item(key = "drawer_conversation_section_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_conversations),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // --- 会话列表显示区域 ---
                when {
                    isLoadingHistoryData -> {
                        item(key = "history_loading") {
                            HistorySkeletonLoading()
                        }
                    }
                    historicalConversations.isEmpty() && !isLoadingHistoryData -> {
                        item(key = "history_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 160.dp)
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(R.string.drawer_no_chat_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> {
                        // "已置顶" 分组
                        if (processedItems.pinned.isNotEmpty()) {
                            item(key = "pinned_header") {
                                CollapsibleGroupHeader(
                                    groupName = stringResource(R.string.drawer_pinned_group),
                                    isExpanded = expandedGroups.contains("pinned"),
                                    onToggleExpand = { onToggleGroup("pinned") },
                                    isPinnedGroup = true,
                                    modifier = Modifier.animateItem(placementSpec = tween(300)),
                                )
                            }
                            item(key = "pinned_items_container") {
                                val isPinnedExpanded = expandedGroups.contains("pinned")
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isPinnedExpanded,
                                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                                    exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        processedItems.pinned.forEach { itemData ->
                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = !deletingItems.contains(itemData.stableId),
                                                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                                                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                                            ) {
                                                ConversationItem(itemData)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 未分组会话始终显示。
                        items(
                            items = processedItems.ungrouped,
                            key = { item -> "ungrouped_${item.stableId}_${isImageGenerationMode}" },
                        ) { itemData ->
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !deletingItems.contains(itemData.stableId),
                                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
                                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                                modifier = if (deletingItems.contains(itemData.stableId)) Modifier else Modifier.animateItem(placementSpec = tween(300)),
                            ) {
                                ConversationItem(
                                    itemData = itemData,
                                    modifier = Modifier,
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomChromeHeight)
                    .floatingEdgeGradient(drawerBackground, fromTop = false)
                    .zIndex(1f),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .zIndex(2f),
            ) {
                DrawerAppInfoButton(onClick = onAppInfoClick)
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
                    val indicesToDelete = selectedSet.toList().sortedDescending()
                    showDeleteConfirm = false // 关闭对话框
                    selectedSet.clear()
                    onExpandItem(null) // 如果有菜单打开，也关闭它
                    // 收集需要删除的项的ID以进行动画
                    val idsToAnimate = indicesToDelete.mapNotNull { index ->
                        historicalConversations.getOrNull(index)?.let { resolveStableId(it) }
                    }
                    deletingItems.addAll(idsToAnimate)
                    scope.launch {
                        delay(300) // 等待动画完成
                        // 从后往前删除，避免索引错位
                        indicesToDelete.forEach(onDeleteRequest)
                        deletingItems.removeAll(idsToAnimate)
                    }
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
                val dialogBg = appDialogContainerColor()
                val contentColor = appDialogContentColor()
                val cancelButtonColor = appDialogCancelColor()
                val confirmButtonColor = contentColor
                val confirmButtonTextColor = dialogBg
                AlertDialog(
                    modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
                    onDismissRequest = { renamingIndex = null },
                    title = { Text(stringResource(R.string.drawer_rename_conversation_title)) },
                    text = {
                        Column {
                            Text(
                                text = stringResource(R.string.drawer_conversation_name_label),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.drawer_conversation_name_hint)) },
                                shape = RoundedCornerShape(12.dp),
                                colors = appDialogTextFieldColors()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onRenameRequest(index, newName)
                                renamingIndex = null
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = confirmButtonColor,
                                contentColor = confirmButtonTextColor
                            )
                        ) {
                            Text(
                                stringResource(R.string.action_confirm),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { renamingIndex = null },
                            modifier = Modifier
                                .height(48.dp)
                                .padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = dialogBg,
                                contentColor = cancelButtonColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                        ) {
                            Text(
                                stringResource(R.string.action_cancel),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    },
                    shape = AppDialogShape,
                    containerColor = dialogBg,
                    titleContentColor = contentColor,
                    textContentColor = contentColor
                )
            }
            if (showCreateGroupDialog) {
                CreateGroupDialog(
                    onDismiss = { showCreateGroupDialog = false },
                    onConfirm = { groupName ->
                        onCreateGroup(groupName)
                        showCreateGroupDialog = false
                        // 创建分组后若未展开则自动展开分组区域
                        if (!isGroupSectionExpanded) {
                            onToggleGroupSection()
                        }
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
