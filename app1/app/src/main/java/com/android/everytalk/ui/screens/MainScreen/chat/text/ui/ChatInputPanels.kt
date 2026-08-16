package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*

import kotlin.math.max
import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.MoreOptionsType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.modifier.diffuseShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.ComputerHostCommandConfirmationRequest
import com.android.everytalk.data.computer.needsContainerRuntimeUpgrade
import com.android.everytalk.ui.screens.computer.ComputerCardAccentPalette
import com.android.everytalk.ui.screens.computer.computerCardAccentColorIndexes
import com.android.everytalk.ui.components.popup.AppFloatingCardScaffoldPopup
import com.android.everytalk.ui.components.popup.appFloatingCardContainerColor
import com.android.everytalk.ui.screens.mcp.McpServerListDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun FunctionPanelContent(
    isWebSearchEnabled: Boolean,
    isWebSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
    isCodeExecutionEnabled: Boolean,
    onToggleCodeExecution: () -> Unit,
    isGeminiChannel: Boolean,
    onToggleImagePanel: () -> Unit,
    onToggleMoreOptionsPanel: () -> Unit,
    hasContent: Boolean,
    onClearContent: () -> Unit,
    onDismiss: () -> Unit,
    isMcpEnabled: Boolean = false,
    onToggleMcp: () -> Unit = {},
    isAgentEnabled: Boolean = false,
    isAgentPreparing: Boolean = false,
    onToggleAgent: () -> Unit = {},
    onLongPressAgent: () -> Unit = {},
    onOpenFilePicker: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onOpenSystemPrompt: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 370.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        FunctionPanelRow(
            iconRes = R.drawable.ic_image_gallery,
            label = stringResource(R.string.chat_input_image),
            iconBg = iconBg,
            iconTint = iconTint,
            textColor = textColor,
            isChecked = false,
            onClick = { onOpenGallery(); onDismiss() }
        )
        FunctionPanelRow(
            iconRes = R.drawable.ic_camera,
            label = stringResource(R.string.chat_input_camera),
            iconBg = iconBg,
            iconTint = iconTint,
            textColor = textColor,
            isChecked = false,
            onClick = { onOpenCamera(); onDismiss() }
        )
        FunctionPanelRow(
            iconRes = R.drawable.ic_paperclip,
            label = stringResource(R.string.chat_input_attachment),
            iconBg = iconBg,
            iconTint = iconTint,
            textColor = textColor,
            isChecked = false,
            onClick = { onOpenFilePicker(); onDismiss() }
        )
        FunctionPanelRow(
            iconRes = R.drawable.ic_globe,
            label = stringResource(R.string.chat_input_web_search),
            iconBg = iconBg,
            iconTint = if (isWebSearchEnabled && isWebSearchAvailable) Color(0xFF66B5FF) else iconTint,
            textColor = textColor,
            isChecked = isWebSearchEnabled && isWebSearchAvailable,
            onClick = { onToggleWebSearch() }
        )
        FunctionPanelRow(
            iconRes = R.drawable.ic_hammer,
            label = "MCP",
            iconBg = iconBg,
            iconTint = if (isMcpEnabled) ChatMcpColor else iconTint,
            textColor = textColor,
            isChecked = isMcpEnabled,
            onClick = { onToggleMcp() }
        )
        FunctionPanelRow(
            iconRes = R.drawable.ic_gpt_terminal,
            label = stringResource(R.string.chat_input_agent),
            iconBg = iconBg,
            iconTint = if (isAgentEnabled || isAgentPreparing) ChatAgentColor else iconTint,
            textColor = textColor,
            isChecked = isAgentEnabled,
            isLoading = isAgentPreparing,
            onClick = onToggleAgent,
            onLongClick = onLongPressAgent,
        )
        FunctionPanelRow(
            iconRes = R.drawable.ic_prompt,
            label = stringResource(R.string.chat_input_prompt),
            iconBg = iconBg,
            iconTint = iconTint,
            textColor = textColor,
            isChecked = false,
            onClick = { onOpenSystemPrompt(); onDismiss() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FunctionPanelRow(
    iconRes: Int,
    label: String,
    iconBg: Color,
    iconTint: Color,
    textColor: Color,
    isChecked: Boolean = false,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(iconBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = ChatAgentColor,
                    strokeWidth = 2.dp,
                )
            } else if (isChecked) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** 长按 Agent 后显示的会话级服务器单选卡片。 */
@Composable
internal fun ComputerSelectionCard(
    computers: List<Computer>,
    selectedComputerId: String?,
    onSelect: (Computer) -> Unit,
    onUnavailable: (Computer) -> Unit,
    onAddComputer: () -> Unit = {},
) {
    val accentColorIndexes = remember(computers.map { it.id }) {
        computerCardAccentColorIndexes(computers)
    }
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .widthIn(max = 320.dp)
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (computers.isEmpty()) {
            Text(
                text = stringResource(R.string.agent_server_picker_empty),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddComputer, modifier = Modifier.fillMaxWidth()) {
                Text("添加服务器")
            }
        } else {
            computers.chunked(3).forEach { computerRow ->
                // 每行最多三个。宽度只设上限，短名称仍按内容收紧，长名称会在胶囊内省略。
                val maxItemWidth = when (computerRow.size) {
                    1 -> 184.dp
                    2 -> 146.dp
                    else -> 94.dp
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    computerRow.forEach { computer ->
                        val isReady = computer.status == ComputerStatus.READY
                        val isSelected = selectedComputerId == computer.id
                        val accentColor = ComputerCardAccentPalette[accentColorIndexes.getValue(computer.id)]
                        val shape = RoundedCornerShape(percent = 50)
                        Box(
                            modifier = Modifier
                                .widthIn(min = 72.dp, max = maxItemWidth)
                                .height(48.dp)
                                .background(
                                    color = if (isSelected) {
                                        accentColor.copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.11f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                                    },
                                    shape = shape,
                                )
                                .then(if (isSelected) Modifier.border(1.dp, accentColor, shape) else Modifier)
                                .clip(shape)
                                .combinedClickable(
                                    onClick = {
                                        if (isReady) onSelect(computer) else onUnavailable(computer)
                                    },
                                    onLongClick = onAddComputer,
                                )
                                .padding(horizontal = 11.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_gpt_terminal),
                                    contentDescription = null,
                                    tint = if (isReady) accentColor else accentColor.copy(alpha = 0.42f),
                                    modifier = Modifier.size(21.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = computer.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                if (isSelected) {
                                    Spacer(Modifier.width(7.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = stringResource(R.string.state_selected),
                                        tint = accentColor,
                                        modifier = Modifier.size(16.dp),
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

/**
 * 主机命令在输入框上方就地确认。卡片完整展示当前命令，按钮只批准这一个 requestId。
 */
@Composable
internal fun ComputerHostCommandConfirmationCard(
    request: ComputerHostCommandConfirmationRequest?,
    popupPositionProvider: PopupPositionProvider,
    onDecision: (requestId: String, approved: Boolean) -> Unit,
    onVisibilityChange: (Boolean) -> Unit = {},
) {
    var displayedRequest by remember { mutableStateOf(request) }
    val latestOnVisibilityChange by rememberUpdatedState(onVisibilityChange)
    LaunchedEffect(request) {
        if (request != null) {
            displayedRequest = request
            latestOnVisibilityChange(true)
        } else if (displayedRequest != null) {
            latestOnVisibilityChange(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose { latestOnVisibilityChange(false) }
    }
    val currentRequest = displayedRequest ?: return
    val isDark = isSystemInDarkTheme()
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val secondaryColor = contentColor.copy(alpha = 0.62f)
    val commandBackground = if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.045f)
    val buttonBackground = contentColor
    val buttonContent = if (isDark) Color(0xFF0D0D0D) else Color.White
    val cardBackground = appFloatingCardContainerColor()

    AppFloatingCardScaffoldPopup(
        visible = request != null,
        popupPositionProvider = popupPositionProvider,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        onExitAnimationFinished = {
            displayedRequest = null
            latestOnVisibilityChange(false)
        },
        header = {
            // 头部和底部直接使用统一卡片背景，中间命令内容单独滚动。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBackground)
                    .padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stringResource(
                        if (currentRequest.decisionMode == com.android.everytalk.data.computer.ComputerApprovalDecisionMode.RETRY_OR_KEEP_UNKNOWN) {
                            R.string.agent_unknown_title
                        } else {
                            R.string.agent_host_command_title
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = stringResource(R.string.agent_host_command_server, currentRequest.computerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                )
            }
        },
        content = {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = currentRequest.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = commandBackground,
                ) {
                    SelectionContainer {
                        Text(
                            text = currentRequest.command,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = contentColor,
                        )
                    }
                }
                if (currentRequest.cwd.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.agent_host_command_cwd, currentRequest.cwd),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                    )
                }
                if (currentRequest.requestsPrivilege) {
                    Text(
                        text = stringResource(R.string.agent_host_command_detail_privilege),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBackground)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onDecision(currentRequest.requestId, false) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentColor,
                    ),
                    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.18f)),
                ) {
                    Text(
                        if (currentRequest.decisionMode == com.android.everytalk.data.computer.ComputerApprovalDecisionMode.RETRY_OR_KEEP_UNKNOWN) {
                            stringResource(R.string.agent_unknown_keep)
                        } else {
                            stringResource(R.string.agent_host_command_reject)
                        }
                    )
                }
                Button(
                    onClick = { onDecision(currentRequest.requestId, true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonBackground,
                        contentColor = buttonContent,
                    ),
                ) {
                    Text(
                        if (currentRequest.decisionMode == com.android.everytalk.data.computer.ComputerApprovalDecisionMode.RETRY_OR_KEEP_UNKNOWN) {
                            stringResource(R.string.agent_unknown_retry)
                        } else {
                            stringResource(R.string.agent_host_command_allow_once)
                        }
                    )
                }
            }
        },
    )
}

internal fun computerStatusLabelRes(computer: Computer): Int =
    if (computer.needsContainerRuntimeUpgrade()) {
        R.string.agent_server_upgrade_required
    } else {
        computerStatusLabelRes(computer.status)
    }

internal fun computerStatusLabelRes(status: ComputerStatus): Int = when (status) {
    ComputerStatus.READY -> R.string.agent_server_ready
    ComputerStatus.OFFLINE, ComputerStatus.DISCONNECTED -> R.string.agent_server_offline
    ComputerStatus.ACTION_REQUIRED, ComputerStatus.HOST_KEY_CHANGED,
    ComputerStatus.CONFIGURATION_REQUIRED -> R.string.agent_server_action_required
    else -> R.string.agent_server_unavailable
}
