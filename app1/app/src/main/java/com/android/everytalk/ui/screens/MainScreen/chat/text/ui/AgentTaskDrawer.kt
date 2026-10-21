package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.agent.AgentApprovalDecision
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.PendingComputerToolApproval
import com.android.everytalk.data.computer.hostConfirmationRequest
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningMarkdownBlock
import com.android.everytalk.ui.components.floatingEdgeGradient
import kotlin.math.roundToInt

/**
 * 任务入口默认收在聊天区域右侧中部，只露出小把手；左滑唤出、右滑收起，底部留给输入框和队列。
 * 抽屉通过独立模态窗口覆盖页面，滑入、退出完成后再移除窗口，避免关闭时动画被截断。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentTaskOverlay(
    sessionKey: String,
    tasks: List<AgentTaskItem>,
    approvals: List<PendingComputerToolApproval>,
    topAvoidance: Dp,
    bottomAvoidance: Dp,
    onApproval: (PendingComputerToolApproval, AgentApprovalDecision, () -> Unit) -> Unit,
) {
    if (tasks.size < 2) return
    var open by rememberSaveable(sessionKey) { mutableStateOf(false) }
    // false 收在边缘，true 展开。原生拖动状态负责速度、吸附及动画打断，避免松手后才突然出现。
    val entryTravelPx = with(LocalDensity.current) { 64.dp.toPx() }
    val entryDragState = remember(sessionKey, entryTravelPx) {
        AnchoredDraggableState(false, DraggableAnchors {
            false at 0f
            true at -entryTravelPx
        })
    }
    val entryProgress = (-entryDragState.requireOffset() / entryTravelPx).coerceIn(0f, 1f)
    var selectedId by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    val selected = tasks.firstOrNull { it.computerId == selectedId } ?: tasks.first()
    val taskStateHolder = rememberSaveableStateHolder()
    val transition = remember(sessionKey) { MutableTransitionState(false) }
    transition.targetState = open
    val needsAttention = tasks.any { it.phase == AgentTaskPhase.APPROVAL }
    val openDescription = stringResource(R.string.agent_tasks_open, tasks.size)
    val entryColor by animateColorAsState(
        ChatAgentColor.copy(alpha = if (needsAttention) 0.18f else 0.10f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        tween(220), label = "task-entry-color",
    )
    val entryFling = AnchoredDraggableDefaults.flingBehavior(
        state = entryDragState,
        positionalThreshold = { distance -> distance / 2f },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
    )
    val entryDrag = Modifier.anchoredDraggable(
        state = entryDragState, orientation = Orientation.Horizontal,
        reverseDirection = false, flingBehavior = entryFling,
    )
    Box(Modifier.fillMaxSize().padding(top = topAvoidance, bottom = bottomAvoidance).clipToBounds()) {
        // 仅此 112dp 高的边缘区域排除系统返回手势，其余右侧边缘仍保持系统行为。
        // 手势节点始终保留，拖到端点后反向移动也不会因为控件移除而中断。
        Box(Modifier.align(Alignment.CenterEnd).width(24.dp).height(112.dp)
            .systemGestureExclusion().then(entryDrag).semantics {
                if (entryProgress == 0f) {
                    contentDescription = openDescription
                    role = Role.Button
                    onClick { open = true; true }
                }
            }.testTag("agent-task-edge")) {
            // 可见把手贴住右边缘，手势区域仍保持较大尺寸，方便找到并向左滑出入口。
            if (entryProgress < 1f) {
                Box(Modifier.align(Alignment.CenterEnd).size(width = 6.dp, height = 40.dp)
                    .graphicsLayer { alpha = 1f - entryProgress }
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(ChatAgentColor).testTag("agent-task-handle"))
            }
        }
        // 保留同一个按钮随手指移出/移入边界；位置与透明度共用进度，不再分两段播放进出动画。
        Surface(
            onClick = { open = true },
            enabled = entryProgress == 1f,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                .offset { IntOffset((entryTravelPx + entryDragState.requireOffset()).coerceIn(0f, entryTravelPx).roundToInt(), 0) }
                .size(52.dp).graphicsLayer { alpha = entryProgress }
                .then(entryDrag).testTag("agent-task-entry"),
            shape = RoundedCornerShape(22.dp),
            color = entryColor,
            contentColor = ChatAgentColor,
            border = BorderStroke(1.dp, ChatAgentColor.copy(alpha = 0.24f)),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AccountTree,
                    contentDescription = openDescription,
                    modifier = Modifier.size(23.dp))
                Badge(Modifier.align(Alignment.TopEnd).padding(3.dp),
                    containerColor = if (needsAttention) MaterialTheme.colorScheme.tertiary else ChatAgentColor,
                    contentColor = Color.White) {
                    Text(tasks.size.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (transition.currentState || transition.targetState) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val scrimAlpha by animateFloatAsState(if (open) 0.32f else 0f, tween(260), label = "task-scrim")
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))) {
                Box(Modifier.fillMaxSize().clickable { open = false }.testTag("agent-task-scrim"))
                AnimatedVisibility(
                    visibleState = transition,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 440.dp).fillMaxWidth(0.9f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)).padding(vertical = 8.dp),
                    enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(220)),
                    exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180)),
                ) {
                    ModalDrawerSheet(
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.testTag("agent-task-drawer"),
                        drawerShape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets(0),
                    ) {
                        val density = LocalDensity.current
                        var headerHeightPx by remember { mutableIntStateOf(0) }
                        val headerHeight = with(density) { headerHeightPx.toDp() }
                        Box(Modifier.fillMaxSize()) {
                            AnimatedContent(
                                targetState = selected.computerId,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = { (fadeIn(tween(170)) + slideInHorizontally(tween(220)) { it / 14 }) togetherWith fadeOut(tween(100)) },
                                label = "task-switch",
                            ) { computerId ->
                                val task = tasks.firstOrNull { it.computerId == computerId } ?: tasks.first()
                                taskStateHolder.SaveableStateProvider(computerId) {
                                    TaskProcess(task, approvals.filter { it.request.context.computerId == computerId },
                                        headerHeight, onApproval)
                                }
                            }
                            // 复用公共渐变顶栏：标题和切换栏固定，过程从下方滑过并渐隐。
                            // 按实际高度留出列表首屏空间，适配字体缩放，避免第一条记录被顶栏遮住。
                            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height }
                                .floatingEdgeGradient(MaterialTheme.colorScheme.surface, fromTop = true)
                                .testTag("agent-task-header").padding(bottom = 24.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.agent_tasks), style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { open = false }) {
                                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.agent_tasks_close), Modifier.size(22.dp))
                                    }
                                }
                                LazyRow(Modifier.fillMaxWidth().testTag("agent-task-tabs"),
                                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(tasks, key = { it.computerId }) { task ->
                                        TaskTab(task, task.computerId == selected.computerId) { selectedId = task.computerId }
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

@Composable
private fun TaskTab(task: AgentTaskItem, active: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (active) ChatAgentColor.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceContainerHigh, tween(200), label = "task-tab-color")
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = background,
        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = active }
            .testTag("agent-task-tab-${task.computerId}"),
        border = if (active) BorderStroke(1.dp, ChatAgentColor.copy(alpha = 0.55f)) else null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatusDot(task.phase)
            Text(task.name,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp))
        }
    }
}

@Composable
private fun TaskStatusDot(phase: AgentTaskPhase) {
    val color = when (phase) {
        AgentTaskPhase.FAILED -> MaterialTheme.colorScheme.error
        AgentTaskPhase.APPROVAL -> MaterialTheme.colorScheme.tertiary
        AgentTaskPhase.RUNNING, AgentTaskPhase.PLANNING, AgentTaskPhase.COMPLETED -> ChatAgentColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    if (phase == AgentTaskPhase.RUNNING || phase == AgentTaskPhase.PLANNING) {
        CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
    } else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun taskPhaseText(phase: AgentTaskPhase): String = stringResource(when (phase) {
    AgentTaskPhase.PLANNING -> R.string.agent_task_analyzing
    AgentTaskPhase.WAITING -> R.string.agent_task_waiting
    AgentTaskPhase.RUNNING -> R.string.agent_task_running
    AgentTaskPhase.REVIEWING -> R.string.agent_task_reviewing
    AgentTaskPhase.APPROVAL -> R.string.agent_task_approval
    AgentTaskPhase.COMPLETED -> R.string.agent_task_completed
    AgentTaskPhase.FAILED -> R.string.agent_task_failed
    AgentTaskPhase.STOPPED -> R.string.agent_task_stopped
    AgentTaskPhase.UNUSED -> R.string.agent_task_unused
})

@Composable
private fun TaskProcess(
    task: AgentTaskItem,
    approvals: List<PendingComputerToolApproval>,
    topContentPadding: Dp,
    onApproval: (PendingComputerToolApproval, AgentApprovalDecision, () -> Unit) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag("agent-task-process-${task.computerId}"),
        contentPadding = PaddingValues(start = 16.dp, top = topContentPadding, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task.name,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskStatusDot(task.phase)
                    Text(taskPhaseText(task.phase), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(approvals, key = { "approval-${it.approvalRequestId}" }) { pending ->
            TaskApprovalCard(pending, onApproval)
        }
        if (task.trace.isEmpty()) item {
            Text(stringResource(R.string.agent_task_empty),
                modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(task.trace.size, key = { index ->
            (task.trace[index] as? ExecutionTraceEvent.Tool)?.step?.id ?: "text-$index"
        }) { index ->
            when (val event = task.trace[index]) {
                is ExecutionTraceEvent.Tool -> TaskToolCard(event.step)
                is ExecutionTraceEvent.Reasoning -> ReasoningMarkdownBlock(event.text, index, "task-main")
                is ExecutionTraceEvent.Content -> ReasoningMarkdownBlock(event.text, index, "task-commentary")
                is ExecutionTraceEvent.UserMessageBoundary -> Unit
            }
        }
    }
}

@Composable
private fun TaskToolCard(step: ExecutionStep) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(230))) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TaskStatusDot(when {
                    step.isError -> AgentTaskPhase.FAILED
                    step.completed -> AgentTaskPhase.COMPLETED
                    step.started -> AgentTaskPhase.RUNNING
                    else -> AgentTaskPhase.WAITING
                })
                Text(step.labels.firstOrNull() ?: step.title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_gpt_arrow_down), null, Modifier.size(16.dp))
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(100))) {
                SelectionContainer {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(step.detail ?: step.labels.joinToString("\n"), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                        step.resultPreview?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/** 复用原审批的可信内容和决定入口；按钮只绑定这一条申请，不授权其他服务器或后续命令。 */
@Composable
private fun TaskApprovalCard(
    pending: PendingComputerToolApproval,
    onApproval: (PendingComputerToolApproval, AgentApprovalDecision, () -> Unit) -> Unit,
) {
    val confirmation = pending.hostConfirmationRequest() ?: return
    val unknown = pending.request is ComputerToolApprovalRequest.UnknownExecution
    var submitted by remember(pending.approvalRequestId) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_task_approval), style = MaterialTheme.typography.titleSmall)
            Text(confirmation.computerName, style = MaterialTheme.typography.labelLarge, color = ChatAgentColor)
            SelectionContainer { Text(confirmation.command, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace) }
            Text(confirmation.reason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    submitted = true
                    onApproval(pending, if (unknown) AgentApprovalDecision.KEEP_UNKNOWN else AgentApprovalDecision.REJECTED) { submitted = false }
                }, enabled = !submitted, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) {
                    Text(stringResource(if (unknown) R.string.agent_unknown_keep else R.string.agent_task_reject))
                }
                Button(onClick = {
                    submitted = true
                    onApproval(pending, if (unknown) AgentApprovalDecision.RETRY else AgentApprovalDecision.APPROVED) { submitted = false }
                }, enabled = !submitted, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatAgentColor, contentColor = Color.White)) {
                    Text(stringResource(if (unknown) R.string.agent_unknown_retry else R.string.agent_task_allow))
                }
            }
        }
    }
}
