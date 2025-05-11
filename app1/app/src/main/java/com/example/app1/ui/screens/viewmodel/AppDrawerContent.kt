package com.example.app1.ui.screens.viewmodel

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState // 重新引入
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.GestureCancellationException
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

// --- 常量定义 ---
private val searchBackgroundColor = Color(0xFFEAEAEA)
private val defaultDrawerWidth = 280.dp
private const val EXPAND_ANIMATION_DURATION_MS = 300
private const val CONTENT_CHANGE_ANIMATION_DURATION_MS = 200

private const val CUSTOM_RIPPLE_ANIMATION_DURATION_MS = 350 // 涟漪动画总时长
private val CUSTOM_RIPPLE_COLOR = Color.Black // 涟漪的基础颜色
private const val CUSTOM_RIPPLE_START_ALPHA = 0.12f // 涟漪开始时的 Alpha
private const val CUSTOM_RIPPLE_END_ALPHA = 0f    // 涟漪结束时的 Alpha (淡出到透明)

// --- 用于自定义涟漪的状态 ---
sealed class CustomRippleState {
    object Idle : CustomRippleState() // 静止状态
    data class Animating(val pressPosition: Offset) : CustomRippleState() // 动画进行中，存储按压位置
}

data class FilteredConversationItem(
    val originalIndex: Int,
    val conversation: List<Message>,
)

@Composable
private fun rememberGeneratedPreviewSnippet(
    messageText: String, query: String, contextChars: Int = 10
): AnnotatedString? {
    val highlightColor = MaterialTheme.colorScheme.primary
    return remember(messageText, query, highlightColor, contextChars) {
        if (query.isBlank()) return@remember null
        val queryLower = query.lowercase();
        val textLower = messageText.lowercase()
        val startIndex = textLower.indexOf(queryLower)
        if (startIndex == -1) return@remember null
        val snippetStart = maxOf(0, startIndex - contextChars)
        val snippetEnd = minOf(messageText.length, startIndex + query.length + contextChars)
        val prefix = if (snippetStart > 0) "..." else "";
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
    ExperimentalMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
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
                expandedItemIndex = null; changed = true
            }
            if (isSearchActive) {
                isSearchActive = false; changed = true
            }
            if (changed) Log.d("AppDrawerContent", "因 loadedHistoryIndex 变为 null，已清除状态。")
        }
    }
    LaunchedEffect(expandedItemIndex) {
        if (expandedItemIndex == null) {
            longPressPosition = null
            showPopupForIndex = null
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
            searchQuery = ""
        }
    }
    val filteredItems = remember(searchQuery, historicalConversations, isSearchActive) {
        if (!isSearchActive || searchQuery.isBlank()) {
            historicalConversations.mapIndexed { index, conversation ->
                FilteredConversationItem(index, conversation)
            }
        } else {
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
    BackHandler(enabled = isSearchActive) { isSearchActive = false }

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
                    isSearchActive = true
                }
            }

            // 搜索框区域
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
                        if (it.isNotBlank() && !isSearchActive) {
                            isSearchActive = true
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
                                Icon(
                                    Icons.Filled.Close,
                                    "清除搜索"
                                )
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
                    singleLine = true, interactionSource = textFieldInteractionSource,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
            }

            // --- “新建会话” 和 “清空记录” 按钮 ---
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
                        Icon(
                            Icons.Filled.AddCircleOutline,
                            "新建会话图标",
                            tint = Color.Black
                        ); Spacer(Modifier.width(20.dp))
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

            // --- "聊天" 标题 ---
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

                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = filteredItems,
                                key = { item -> item.originalIndex }) { itemData ->
                                val originalIndex = itemData.originalIndex
                                val conversation = itemData.conversation
                                val previewTextToShow: AnnotatedString? =
                                    if (isSearchActive && searchQuery.isNotBlank()) {
                                        conversation.firstNotNullOfOrNull { msg ->
                                            if (msg.text.contains(
                                                    searchQuery,
                                                    ignoreCase = true
                                                )
                                            ) rememberGeneratedPreviewSnippet(
                                                msg.text,
                                                searchQuery
                                            ) else null
                                        }
                                    } else {
                                        null
                                    }
                                val isActuallyActive = loadedHistoryIndex == originalIndex

                                var rippleState by remember {
                                    mutableStateOf<CustomRippleState>(
                                        CustomRippleState.Idle
                                    )
                                }
                                // Storing pressPosition separately because rippleState changes and would reset it for drawBehind.
                                var currentPressPosition by remember { mutableStateOf(Offset.Zero) }

                                val animationProgress by animateFloatAsState(
                                    targetValue = if (rippleState is CustomRippleState.Animating) 1f else 0f,
                                    animationSpec = tween(
                                        durationMillis = CUSTOM_RIPPLE_ANIMATION_DURATION_MS,
                                        easing = LinearEasing
                                    ),
                                    finishedListener = { progressValue ->
                                        // When the animation finishes (either expanding or contracting)
                                        if (progressValue == 0f && rippleState is CustomRippleState.Idle) {
                                            // Fully faded out, already Idle.
                                        } else if (progressValue == 1f && rippleState is CustomRippleState.Animating) {
                                            // Fully expanded. If no long press occurred, it should start fading out.
                                            // This is handled by the onPress job setting state to Idle.
                                        }
                                        // If the animation naturally reaches 0 (fades out), ensure state is Idle.
                                        if (rippleState !is CustomRippleState.Idle && progressValue == 0f) {
                                            // This can happen if state was Animating but targetValue changed to 0f.
                                            // To be safe, ensure it's Idle.
                                            rippleState = CustomRippleState.Idle
                                        }
                                    },
                                    label = "rippleAnimationProgress"
                                )

                                val scope = rememberCoroutineScope()
                                var pressAndHoldJob by remember { mutableStateOf<Job?>(null) }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clipToBounds()
                                        .pointerInput(originalIndex) { // Key by originalIndex if needed
                                            detectTapGestures(
                                                onPress = { offset ->
                                                    pressAndHoldJob?.cancel() // Cancel any existing job
                                                    currentPressPosition =
                                                        offset // Store for drawing
                                                    rippleState =
                                                        CustomRippleState.Animating(offset)

                                                    pressAndHoldJob = scope.launch {
                                                        try {
                                                            // `this` inside onPress is PressGestureScope
                                                            this@detectTapGestures.awaitRelease()
                                                            // If awaitRelease completes, means it was a tap or release, not a cancellation by long press.
                                                            // Start fade out by setting state to Idle.
                                                            // The animationProgress target will become 0f.
                                                            rippleState = CustomRippleState.Idle
                                                        } catch (e: GestureCancellationException) {
                                                            // Gesture was cancelled (e.g., by scroll, or long press starting)
                                                            rippleState = CustomRippleState.Idle
                                                        }
                                                    }
                                                },
                                                onTap = {
                                                    // onPress already handled starting the ripple.
                                                    // The ripple will fade out due to awaitRelease() completing.
                                                    val wasExpanded =
                                                        expandedItemIndex == originalIndex
                                                    expandedItemIndex =
                                                        null // Clear any long-press state
                                                    if (!wasExpanded) {
                                                        selectedSet.clear()
                                                        onConversationClick(originalIndex)
                                                    }
                                                },
                                                onLongPress = { offset ->
                                                    pressAndHoldJob?.cancel() // Cancel the press-and-hold job.
                                                    rippleState =
                                                        CustomRippleState.Idle // Immediately stop ripple animation.

                                                    longPressPosition = offset
                                                    expandedItemIndex = originalIndex
                                                    showPopupForIndex = originalIndex
                                                }
                                            )
                                        }
                                        .drawBehind {
                                            if (animationProgress > 0f) { // Only draw if animation is active
                                                val rippleRadius = max(
                                                    size.width,
                                                    size.height
                                                ) * animationProgress * 0.8f // Ripple expands
                                                val alpha =
                                                    CUSTOM_RIPPLE_START_ALPHA * (1f - animationProgress) // Fades out as it expands

                                                if (alpha > 0f) { // Only draw if visible
                                                    drawCircle(
                                                        color = CUSTOM_RIPPLE_COLOR,
                                                        radius = rippleRadius,
                                                        center = currentPressPosition, // Use the stored press position
                                                        alpha = alpha.coerceIn(
                                                            CUSTOM_RIPPLE_END_ALPHA,
                                                            CUSTOM_RIPPLE_START_ALPHA
                                                        ),
                                                        style = Fill
                                                    )
                                                }
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isActuallyActive) {
                                                Spacer(Modifier.width(16.dp)); Box(
                                                    modifier = Modifier
                                                        .size(
                                                            8.dp
                                                        )
                                                        .background(Color.Black, CircleShape)
                                                ); Spacer(Modifier.width(8.dp))
                                            } else {
                                                Spacer(Modifier.width(32.dp))
                                            }
                                            DrawerConversationItemContent(
                                                conversation = conversation,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 16.dp)
                                            )
                                        }

                                        if (previewTextToShow != null) {
                                            Spacer(Modifier.height(1.dp))
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
                                                    )
                                                    .fillMaxWidth(),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        // --- 长按弹窗 ---
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
                                                properties = PopupProperties(focusable = false)
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
                                                            ); Spacer(Modifier.width(12.dp)); Text(
                                                            "重命名",
                                                            color = if (isRenameEnabledForThisPopup) Color.Black else Color.Gray,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        }
                                                        Divider(color = Color.LightGray.copy(alpha = 0.5f))
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
                                                                        ); if (selectedSet.isEmpty() && expandedItemIndex != null) selectedSet.add(
                                                                        expandedItemIndex!!
                                                                    ); expandedItemIndex =
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
                                                            ); Spacer(Modifier.width(12.dp)); Text(
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
        }

        // --- AlertDialogs ---
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false; selectedSet.clear() },
                title = { Text(if (selectedSet.size > 1) "确定删除所有所选项？" else if (selectedSet.size == 1) "确定删除所选项？" else "确定删除此项？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val indicesToDelete =
                                selectedSet.toList(); selectedSet.clear(); expandedItemIndex =
                            null; showDeleteConfirm = false; indicesToDelete.sortedDescending()
                            .forEach(onDeleteRequest)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false; selectedSet.clear()
                    }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) {
                        Text(
                            "取消"
                        )
                    }
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
                    TextButton(
                        onClick = {
                            onClearAllConversationsRequest(); showClearAllConfirm =
                            false; selectedSet.clear(); expandedItemIndex = null
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

@Composable
private fun DrawerConversationItemContent(
    conversation: List<Message>, modifier: Modifier = Modifier
) {
    val firstUserMessage = conversation.firstOrNull { it.sender == Sender.User }?.text
    val previewText = firstUserMessage ?: conversation.firstOrNull()?.text ?: "空对话"
    Text(
        text = previewText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(vertical = 18.dp)
    )
}