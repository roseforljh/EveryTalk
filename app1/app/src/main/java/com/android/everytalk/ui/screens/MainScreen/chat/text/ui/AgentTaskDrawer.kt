package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.android.everytalk.ui.components.floatingEdgeGradient
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.MessageQuoteReference
import com.android.everytalk.data.agent.AgentApprovalDecision
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.PendingComputerToolApproval
import com.android.everytalk.data.computer.hostConfirmationRequest
import com.android.everytalk.ui.components.selection.QuoteReferenceTagsRow
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningMarkdownBlock
import com.android.everytalk.ui.theme.chatColors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DrawerShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
private val HandleShape = RoundedCornerShape(topStart = 999.dp, bottomStart = 999.dp)
internal const val AgentTaskHandleIntroMillis = 2_400L

@Composable
internal fun AgentTaskOverlay(
    sessionKey: String,
    tasks: List<AgentTaskItem>,
    approvals: List<PendingComputerToolApproval>,
    topAvoidance: Dp,
    bottomAvoidance: Dp,
    allowSingleOrForkTask: Boolean = false,
    openRequestVersion: Int = 0,
    preferredTabId: String? = null,
    modelName: String? = null,
    onQuoteClick: ((MessageQuoteReference) -> Unit)? = null,
    /** Fork 页底部的输入框，由调用方放入和主会话相同的 ChatInputArea。 */
    forkComposer: (@Composable (Color) -> Unit)? = null,
    onComposerOpenProgress: (Float) -> Unit = {},
    onApproval: (PendingComputerToolApproval, AgentApprovalDecision, () -> Unit) -> Unit,
) {
    if (tasks.size < 2 && (!allowSingleOrForkTask || tasks.isEmpty())) return
    var selectedId by rememberSaveable(sessionKey) { mutableStateOf(tasks.first().computerId) }
    var submittingApprovalId by remember(sessionKey) { mutableStateOf<String?>(null) }
    var lastAutoOpenedApprovalId by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    var lastHandledOpenVersion by rememberSaveable(sessionKey) { mutableIntStateOf(0) }
    var showHandleHint by remember(sessionKey) { mutableStateOf(true) }
    val scrollStates = remember(sessionKey) { mutableStateMapOf<String, LazyListState>() }
    val expandedToolIds = remember(sessionKey) { mutableStateMapOf<String, Boolean>() }
    // fraction = 0f 为完全收起，1f 为完全展开；手势与动画读写同一连续进度。
    val openFraction = remember(sessionKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { onComposerOpenProgress(0f) } }

    fun snapTo(target: Float) {
        scope.launch {
            // 不用弹簧，避免末端抖动；时长按剩余距离折算，拖到一半松手不会再走完整时长。
            val distance = kotlin.math.abs(target - openFraction.value)
            val opening = target > 0.5f
            openFraction.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = ((if (opening) DrawerOpenMillis else DrawerCloseMillis) * distance)
                        .roundToInt().coerceAtLeast(DrawerMinSettleMillis),
                    // 两个方向都用强减速曲线：一开始就动，末段缓停，不回弹。
                    easing = DrawerPopEasing,
                ),
            )
        }
    }
    // 以下布尔值只在越过阈值时触发重组；逐帧进度只在 graphicsLayer/offset 的绘制阶段读取。
    val isDrawerVisible by remember { derivedStateOf { openFraction.value > 0.005f } }
    val isDrawerOpening by remember { derivedStateOf { openFraction.value > 0.01f } }
    val isHandleVisible by remember { derivedStateOf { openFraction.value < 0.995f } }

    LaunchedEffect(tasks) {
        if (tasks.none { it.computerId == selectedId }) selectedId = tasks.first().computerId
    }
    LaunchedEffect(openRequestVersion, preferredTabId) {
        if (openRequestVersion > lastHandledOpenVersion) {
            lastHandledOpenVersion = openRequestVersion
            if (preferredTabId != null && tasks.any { it.computerId == preferredTabId }) {
                selectedId = preferredTabId
            }
            snapTo(1f)
        }
    }
    LaunchedEffect(approvals) {
        if (submittingApprovalId != null && approvals.none { it.approvalRequestId == submittingApprovalId }) {
            submittingApprovalId = null
        }
        val first = approvals.firstOrNull() ?: return@LaunchedEffect
        if (first.approvalRequestId != lastAutoOpenedApprovalId) {
            lastAutoOpenedApprovalId = first.approvalRequestId
            selectedId = first.request.context.computerId
            snapTo(1f)
        }
    }
    LaunchedEffect(sessionKey, isDrawerOpening, approvals.size) {
        if (isDrawerOpening || approvals.isNotEmpty()) {
            showHandleHint = true
        } else {
            showHandleHint = true
            delay(AgentTaskHandleIntroMillis)
            showHandleHint = false
        }
    }

    val selected = tasks.firstOrNull { it.computerId == selectedId } ?: tasks.first()
    val ownsComposer = selected.isForkTask && forkComposer != null
    val latestComposerProgress by rememberUpdatedState(onComposerOpenProgress)
    // 主输入框和抽屉共用实际拖动进度，收起时逐帧恢复；用快照流推送，不让整个抽屉每帧重组。
    LaunchedEffect(ownsComposer) {
        if (!ownsComposer) {
            latestComposerProgress(0f)
            return@LaunchedEffect
        }
        snapshotFlow { openFraction.value.coerceIn(0f, 1f) }.collect { latestComposerProgress(it) }
    }
    val hasActive = tasks.any {
        it.phase in setOf(AgentTaskPhase.PLANNING, AgentTaskPhase.WAITING, AgentTaskPhase.RUNNING, AgentTaskPhase.REVIEWING)
    }
    val handleAlpha by animateFloatAsState(
        targetValue = when {
            isDrawerOpening -> 0f
            tasks.any { it.isForkTask } -> 0.65f
            approvals.isNotEmpty() || showHandleHint -> 0.86f
            hasActive -> 0.28f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "agentTaskHandleAlpha",
    )
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val effectiveBottomAvoidance = if (selected.isForkTask && forkComposer != null) {
        // 分支自己持有输入框，不需要给主输入框留空；键盘高度由子输入框处理一次。
        0.dp
    } else if (isDrawerOpening && imeBottom > 0.dp) {
        imeBottom + 8.dp
    } else {
        bottomAvoidance
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(3f)) {
        val drawerWidth = minOf(maxWidth * 0.90f, 412.dp)
        val drawerWidthPx = with(density) { drawerWidth.toPx().coerceAtLeast(1f) }
        val openDesc = stringResource(R.string.agent_tasks_open, tasks.size)
        // 抽屉、悬浮渐隐层和分支输入区共用同一颜色，不再混入主页面底色或额外 tonal 叠色。
        val drawerBackground = MaterialTheme.colorScheme.surfaceContainerHigh

        fun settleFromGesture(velocityPxPerSec: Float) {
            val target = when {
                velocityPxPerSec < -drawerWidthPx * 0.45f -> 1f
                velocityPxPerSec > drawerWidthPx * 0.45f -> 0f
                openFraction.value >= 0.26f -> 1f
                else -> 0f
            }
            snapTo(target)
        }

        if (isDrawerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = openFraction.value.coerceIn(0f, 1f) * 0.36f }
                    .background(Color.Black)
                    .testTag("agent-task-scrim")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { snapTo(0f) },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = topAvoidance, bottom = effectiveBottomAvoidance)
                    // 抽屉已经让开键盘和主输入框，通知内层输入框不要重复占用这段高度。
                    .consumeWindowInsets(PaddingValues(bottom = effectiveBottomAvoidance))
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // 从竖条处弹出：只滑 25% 宽度，主要靠 60%→100% 放大和淡入，
                        // 以右侧中点（竖条位置）为原点，收起时反向缩回竖条。
                        val fraction = openFraction.value.coerceIn(0f, 1f)
                        translationX = (1f - fraction) * drawerWidthPx * DrawerPopSlideFraction
                        val scale = DrawerPopStartScale + (1f - DrawerPopStartScale) * fraction
                        scaleX = scale
                        scaleY = scale
                        alpha = (fraction * 2.5f).coerceAtMost(1f)
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                openFraction.snapTo((openFraction.value - delta / drawerWidthPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragStopped = { velocity -> settleFromGesture(velocity) },
                    )
                    .testTag("agent-task-drawer"),
                shape = DrawerShape,
                color = drawerBackground,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 20.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            ) {
                // 正文铺满侧栏；标题和输入框仅作为渐隐背景的悬浮层，不再用灰色栏截断正文。
                var headerHeightPx by remember { mutableIntStateOf(0) }
                var composerHeightPx by remember { mutableIntStateOf(0) }
                Box(modifier = Modifier.fillMaxSize()) {
                    key(selected.computerId) {
                        val listState = scrollStates.getOrPut(selected.computerId) { LazyListState() }
                        TaskProcess(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 14.dp, end = 14.dp,
                                top = with(density) { headerHeightPx.toDp() } + 14.dp,
                                bottom = if (selected.isForkTask && forkComposer != null) {
                                    with(density) { composerHeightPx.toDp() } + 14.dp
                                } else 14.dp,
                            ),
                            task = selected,
                            approval = approvals.firstOrNull { it.request.context.computerId == selected.computerId },
                            listState = listState,
                            expandedToolIds = expandedToolIds,
                            submittingApprovalId = submittingApprovalId,
                            onApproval = { pending, decision ->
                                submittingApprovalId = pending.approvalRequestId
                                onApproval(pending, decision) { submittingApprovalId = null }
                            },
                            onQuoteClick = { quote ->
                                snapTo(0f)
                                onQuoteClick?.invoke(quote)
                            },
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .onSizeChanged { headerHeightPx = it.height },
                    ) {
                        Column(Modifier.fillMaxWidth().background(drawerBackground)) {
                            TaskHeader(
                                count = tasks.size,
                                phase = selected.phase,
                                isFork = selected.isForkTask,
                                modelName = modelName,
                                onClose = { snapTo(0f) },
                            )
                            if (tasks.size > 1) {
                                TaskTabs(
                                    tasks = tasks,
                                    selectedId = selected.computerId,
                                    approvals = approvals,
                                    onSelect = { selectedId = it },
                                )
                            }
                        }
                        Spacer(Modifier.fillMaxWidth().height(24.dp)
                            .floatingEdgeGradient(drawerBackground, fromTop = true))
                    }
                    if (selected.isForkTask && forkComposer != null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { composerHeightPx = it.height },
                        ) {
                            Spacer(Modifier.fillMaxWidth().height(24.dp)
                                .floatingEdgeGradient(drawerBackground, fromTop = false))
                            Box(Modifier.fillMaxWidth().background(drawerBackground)) { forkComposer(drawerBackground) }
                        }
                    }
                }
            }
        }

        if (isHandleVisible) {
            val handleColor = when {
                approvals.isNotEmpty() -> MaterialTheme.colorScheme.error
                hasActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = topAvoidance, bottom = bottomAvoidance)
                    .width(20.dp)
                    .height(92.dp)
                    // 仅竖条触控区域避让系统返回，让边缘横滑交给抽屉，其余屏幕保持原生返回。
                    .systemGestureExclusion()
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            showHandleHint = true
                            scope.launch {
                                openFraction.snapTo((openFraction.value - delta / drawerWidthPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragStopped = { velocity -> settleFromGesture(velocity) },
                    )
                    .pointerInput(sessionKey) {
                        var pressedAt = Offset.Zero
                        detectTapGestures(
                            onPress = { pressedAt = it },
                            onTap = { releasedAt ->
                                // 竖向滑动不打开侧栏，只有手指基本未移动的轻点才算点击。
                                if ((releasedAt - pressedAt).getDistance() <= viewConfiguration.touchSlop) {
                                    snapTo(1f)
                                }
                            },
                        )
                    }
                    .semantics {
                        contentDescription = if (tasks.any { it.isForkTask }) "打开分支与任务" else openDesc
                        onClick(label = openDesc) {
                            snapTo(1f)
                            true
                        }
                    }
                    .testTag("agent-task-edge"),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(44.dp)
                        .graphicsLayer { alpha = handleAlpha }
                        .clip(HandleShape)
                        .background(handleColor)
                        .testTag("agent-task-handle"),
                )
            }
        }
    }
}

@Composable
private fun TaskHeader(
    count: Int,
    phase: AgentTaskPhase,
    isFork: Boolean = false,
    modelName: String? = null,
    onClose: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)
            .testTag("agent-task-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isFork) "Fork 分支" else stringResource(R.string.agent_tasks),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!isFork) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (!modelName.isNullOrBlank()) {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = phaseColor(phase).copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusDot(phase)
                    Text(
                        text = phaseLabel(phase),
                        style = MaterialTheme.typography.labelSmall,
                        color = phaseColor(phase),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskTabs(
    tasks: List<AgentTaskItem>,
    selectedId: String,
    approvals: List<PendingComputerToolApproval>,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("agent-task-tabs"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tasks.forEach { task ->
            val selected = task.computerId == selectedId
            val needsApproval = approvals.any { it.request.context.computerId == task.computerId }
            val effectivePhase = if (needsApproval) AgentTaskPhase.APPROVAL else task.phase
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(selected = selected, role = Role.Tab) { onSelect(task.computerId) }
                    .testTag("agent-task-tab-${task.computerId}"),
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                border = BorderStroke(
                    width = if (selected) 1.2.dp else 0.8.dp,
                    color = when {
                        needsApproval -> MaterialTheme.colorScheme.error
                        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = if (task.isForkTask) {
                            Icons.AutoMirrored.Outlined.CallSplit
                        } else {
                            Icons.Outlined.Storage
                        },
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = task.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    StatusDot(effectivePhase)
                }
            }
        }
    }
}

@Composable
private fun TaskProcess(
    modifier: Modifier,
    contentPadding: PaddingValues,
    task: AgentTaskItem,
    approval: PendingComputerToolApproval?,
    listState: LazyListState,
    expandedToolIds: MutableMap<String, Boolean>,
    submittingApprovalId: String?,
    onApproval: (PendingComputerToolApproval, AgentApprovalDecision) -> Unit,
    onQuoteClick: (MessageQuoteReference) -> Unit,
) {
    LaunchedEffect(approval?.approvalRequestId) {
        if (approval != null) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent-task-process-${task.computerId}"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        approval?.let { pending ->
            item(key = "approval:${pending.approvalRequestId}") {
                TaskApprovalCard(
                    pending = pending,
                    submitting = submittingApprovalId == pending.approvalRequestId,
                    onApproval = onApproval,
                )
            }
        }
        itemsIndexed(
            items = task.trace,
            key = { index, event -> "${task.computerId}:$index:${event.startedAtMillis ?: 0L}" },
        ) { index, event ->
            when (event) {
                is ExecutionTraceEvent.Reasoning -> {
                    if (event.text.isNotBlank()) {
                        TaskReasoningCard(text = event.text, index = index)
                    }
                }
                is ExecutionTraceEvent.Content -> {
                    if (event.text.isNotBlank()) {
                        TaskAiContentCard(text = event.text, index = index)
                    }
                }
                is ExecutionTraceEvent.Tool -> {
                    TaskToolCard(
                        step = event.step,
                        expanded = expandedToolIds[event.step.id] == true,
                        onToggle = {
                            expandedToolIds[event.step.id] = !(expandedToolIds[event.step.id] ?: false)
                        },
                    )
                }
                is ExecutionTraceEvent.UserMessageBoundary -> {
                    val userMessage = task.userMessages[event.messageId]
                    if (userMessage != null) {
                        TaskUserMessageBubble(
                            message = userMessage,
                            onQuoteClick = onQuoteClick,
                        )
                    }
                }
            }
        }
        if (task.trace.isEmpty() && approval == null) {
            item(key = "empty:${task.computerId}") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusDot(task.phase)
                        Text(
                            text = if (task.isForkTask) {
                                "在下方输入框围绕引用片段提问，分支回答将在此处独立呈现"
                            } else {
                                stringResource(R.string.agent_task_empty)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskUserMessageBubble(
    message: Message,
    onQuoteClick: (MessageQuoteReference) -> Unit,
) {
    val quotes = remember(message.contentParts) {
        message.contentParts.filterIsInstance<MessageContentPart.QuoteReference>().map { it.reference }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (quotes.isNotEmpty()) {
            QuoteReferenceTagsRow(
                quotes = quotes,
                onQuoteClick = onQuoteClick,
                onRemoveQuote = null,
            )
        }
        if (message.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 4.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp,
                ),
                color = MaterialTheme.chatColors.userBubble,
                contentColor = MaterialTheme.chatColors.userBubbleText,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.chatColors.userBubbleText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TaskReasoningCard(text: String, index: Int = 0) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.thinking_process),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.5.dp)
                            .heightIn(min = 18.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = text },
                    ) {
                        ReasoningMarkdownBlock(text, index, "task-main")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskAiContentCard(text: String, index: Int = 0) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.ai_reply_message),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ReasoningMarkdownBlock(text, index, "task-commentary")
        }
    }
}

@Composable
private fun TaskToolCard(
    step: ExecutionStep,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val summary = step.detail?.takeIf { it.isNotBlank() } ?: step.labels.joinToString(" · ", transform = com.android.everytalk.data.mcp.McpToolLabels::display)
    val hasDetails = summary.isNotBlank() || !step.resultPreview.isNullOrBlank()
    val statusColor = when {
        step.isError -> MaterialTheme.colorScheme.error
        step.completed -> Color(0xFF2E7D32)
        step.started -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
        border = BorderStroke(
            width = 0.8.dp,
            color = if (step.isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = when {
                            step.isError -> Icons.Outlined.ErrorOutline
                            step.completed -> Icons.Outlined.CheckCircle
                            else -> Icons.Outlined.Terminal
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = com.android.everytalk.ui.screens.BubbleMain.Main.localizedExecutionStatusText(step.title) ?: step.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    step.durationMs?.takeIf { it > 0 }?.let { duration ->
                        Text(
                            text = if (duration >= 1000L) String.format("%.1fs", duration / 1000f) else "${duration}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = when {
                                step.isError -> stringResource(R.string.agent_task_failed)
                                step.completed -> stringResource(R.string.agent_task_completed)
                                step.started -> stringResource(R.string.agent_task_running)
                                else -> stringResource(R.string.agent_task_waiting)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (hasDetails) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (summary.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = summary,
                        maxLines = if (expanded) 12 else 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (expanded && !step.resultPreview.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = step.resultPreview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 复用原审批的可信内容和决定入口；按钮只绑定这一条申请，不授权其他服务器或后续命令。 */
@Composable
private fun TaskApprovalCard(
    pending: PendingComputerToolApproval,
    submitting: Boolean,
    onApproval: (PendingComputerToolApproval, AgentApprovalDecision) -> Unit,
) {
    val confirmation = pending.hostConfirmationRequest() ?: return
    val unknown = pending.request is ComputerToolApprovalRequest.UnknownExecution
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.agent_task_approval), style = MaterialTheme.typography.titleSmall)
            Text(confirmation.computerName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer {
                Text(
                    confirmation.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                confirmation.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onApproval(
                            pending,
                            if (unknown) AgentApprovalDecision.KEEP_UNKNOWN else AgentApprovalDecision.REJECTED,
                        )
                    },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(stringResource(if (unknown) R.string.agent_unknown_keep else R.string.agent_task_reject))
                }
                Button(
                    onClick = {
                        onApproval(
                            pending,
                            if (unknown) AgentApprovalDecision.RETRY else AgentApprovalDecision.APPROVED,
                        )
                    },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(if (unknown) R.string.agent_unknown_retry else R.string.agent_task_allow))
                }
            }
        }
    }
}

/**
 * 抽屉底部的输入组件，保持与主会话一致的胶囊样式、引用文本标签栏（水平滚动、最大宽度省略号、单击跳转）及提问发送能力。
 */
@Composable
private fun StatusDot(phase: AgentTaskPhase) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(phaseColor(phase)),
    )
}

@Composable
private fun phaseColor(phase: AgentTaskPhase): Color = when (phase) {
    AgentTaskPhase.APPROVAL, AgentTaskPhase.FAILED -> MaterialTheme.colorScheme.error
    AgentTaskPhase.RUNNING, AgentTaskPhase.REVIEWING -> MaterialTheme.colorScheme.primary
    AgentTaskPhase.COMPLETED -> Color(0xFF2E7D32)
    AgentTaskPhase.PLANNING, AgentTaskPhase.WAITING, AgentTaskPhase.STOPPED, AgentTaskPhase.UNUSED ->
        MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun phaseLabel(phase: AgentTaskPhase): String = stringResource(
    when (phase) {
        AgentTaskPhase.PLANNING -> R.string.agent_task_analyzing
        AgentTaskPhase.WAITING -> R.string.agent_task_waiting
        AgentTaskPhase.RUNNING -> R.string.agent_task_running
        AgentTaskPhase.REVIEWING -> R.string.agent_task_reviewing
        AgentTaskPhase.APPROVAL -> R.string.agent_task_approval
        AgentTaskPhase.COMPLETED -> R.string.agent_task_completed
        AgentTaskPhase.FAILED -> R.string.agent_task_failed
        AgentTaskPhase.STOPPED -> R.string.agent_task_stopped
        AgentTaskPhase.UNUSED -> R.string.agent_task_unused
    },
)

/** 展开/收起完整时长；收起更短，松手立即缩回竖条。 */
private const val DrawerOpenMillis = 380f
private const val DrawerCloseMillis = 240f
/** Material 3 emphasized decelerate：起步快、收尾缓，没有过冲。 */
private val DrawerPopEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
/** 弹出起始缩放与横向滑动比例（相对抽屉宽度）。 */
private const val DrawerPopStartScale = 0.6f
private const val DrawerPopSlideFraction = 0.25f
/** 剩余距离很短时的最短动画时长，避免一闪而过。 */
private const val DrawerMinSettleMillis = 120
