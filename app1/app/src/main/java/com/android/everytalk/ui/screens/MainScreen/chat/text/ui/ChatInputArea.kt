package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*
import kotlin.math.max
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.android.everytalk.util.image.validateUserImageForSelection
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.core.content.ContextCompat
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerDisclosureKind
import com.android.everytalk.data.computer.ComputerDisclosureStore
import com.android.everytalk.data.computer.ComputerHostCommandConfirmationRequest
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.MoreOptionsType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.modifier.diffuseShadow
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
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
import com.android.everytalk.ui.screens.mcp.McpServerListDialog
import java.io.File
import java.util.UUID
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.agent.PendingAgentEnableApproval
import com.android.everytalk.data.agent.PendingSkillSecretApproval
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.SkillTagVisualTransformation
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.buildSkillContentParts
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.displaySkillEditorText
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.findSkillSlashQuery
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.insertSkillReference
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.normalizeSkillEdit
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.rankSkillCandidates

private data class PendingAgentAction(
    val computer: Computer,
    val conversationId: String,
    val selectComputer: Boolean,
    val enableAgentAfterSelection: Boolean,
    val requiresDisclosure: Boolean,
    val onCompleted: (() -> Unit)? = null,
    val onFailed: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessageRequest: (messageText: String, isKeyboardVisible: Boolean, attachments: List<SelectedMediaItem>, mimeType: String?, contentParts: List<MessageContentPart>) -> Unit,
    selectedMediaItems: List<SelectedMediaItem>,
    onAddMediaItem: (SelectedMediaItem) -> Unit,
    onRemoveMediaItemAtIndex: (Int) -> Unit,
    onClearMediaItems: () -> Unit,
    isApiCalling: Boolean,
    isRemoteCancellationPending: Boolean = false,
    isWebSearchEnabled: Boolean,
    isWebSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
    isCodeExecutionEnabled: Boolean = false,
    onToggleCodeExecution: () -> Unit = {},
    onStopApiCall: () -> Unit,
    focusRequester: FocusRequester,
    selectedApiConfig: ApiConfig? = null,
    onShowSnackbar: (String) -> Unit,
    imeInsets: WindowInsets,
    density: Density,
    keyboardController: SoftwareKeyboardController? = null,
    onFocusChange: (isFocused: Boolean) -> Unit,
    onSendMessage: (messageText: String, isFromRegeneration: Boolean, attachments: List<SelectedMediaItem>, audioBase64: String?, mimeType: String?) -> Unit,
    viewModel: com.android.everytalk.statecontroller.AppViewModel,
    onShowVoiceInput: () -> Unit,
    onHeightChange: (Int) -> Unit = {},
    hostCommandConfirmationRequest: ComputerHostCommandConfirmationRequest? = null,
    agentEnableApprovalRequest: PendingAgentEnableApproval? = null,
    skillSecretApprovalRequest: PendingSkillSecretApproval? = null,
    onOpenComputerSettings: () -> Unit = {},
    onHostCommandCardVisibilityChange: (Boolean) -> Unit = {},
    // MCP 相关参数
    mcpServerStates: Map<String, McpServerState> = emptyMap(),
    onAddMcpServer: (McpServerConfig) -> Unit = {},
    onRemoveMcpServer: (String) -> Unit = {},
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val skillRepository = remember(context) { SkillRepository(context) }
    val installedSkills by skillRepository.observeAll().collectAsState(initial = emptyList())

    var pendingMessageTextForSend by remember { mutableStateOf<String?>(null) }
    var showImageSelectionPanel by remember { mutableStateOf(false) }
    var showMoreOptionsPanel by remember { mutableStateOf(false) }
    // 记录由外点关闭触发的时间戳，用于忽略紧随其后的按钮抬起点击，避免"先关后开"
    var lastImagePanelDismissAt by remember { mutableLongStateOf(0L) }
    var lastMorePanelDismissAt by remember { mutableLongStateOf(0L) }
    var showMcpServerListDialog by remember { mutableStateOf(false) }
    var showComputerSelectionPopup by remember { mutableStateOf(false) }
    var enableAgentAfterComputerSelection by remember { mutableStateOf(false) }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val isMcpEnabled by viewModel.stateHolder._isMcpEnabledForNextRequest.collectAsState()
    val isAgentEnabled by viewModel.isAgentEnabled.collectAsState()
    val isAgentPreparing by viewModel.isAgentPreparing.collectAsState()
    val computers by viewModel.computers.collectAsState()
    val computerSelections by viewModel.computerSelections.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val selectedComputerId = computerSelections[currentConversationId]
    val disclosureStore = remember(context) { ComputerDisclosureStore(context) }
    var pendingAgentAction by remember { mutableStateOf<PendingAgentAction?>(null) }
    var pendingAgentDisclosures by remember { mutableStateOf<Set<ComputerDisclosureKind>>(emptySet()) }
    var pendingApprovalForComputerSelection by remember { mutableStateOf<PendingAgentEnableApproval?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun requestAgentNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun executeAgentAction(action: PendingAgentAction) {
        if (action.conversationId != currentConversationId) {
            onShowSnackbar(context.getString(R.string.agent_conversation_changed))
            return
        }
        if (action.enableAgentAfterSelection) requestAgentNotificationPermission()
        if (action.selectComputer) {
            viewModel.selectComputerForCurrentConversation(
                computerId = action.computer.id,
                enableAgentAfterSelection = action.enableAgentAfterSelection,
                onReady = action.onCompleted,
                onFailure = action.onFailed,
            )
        } else {
            viewModel.setAgentEnabled(true)
            if (viewModel.isAgentEnabled.value) action.onCompleted?.invoke() else action.onFailed?.invoke()
        }
    }

    fun requestAgentAction(action: PendingAgentAction) {
        val missing = if (action.requiresDisclosure) {
            disclosureStore.missingFor(action.computer)
        } else {
            emptySet()
        }
        if (missing.isEmpty()) {
            executeAgentAction(action)
        } else {
            pendingAgentAction = action
            pendingAgentDisclosures = missing
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    uris.forEach { uri ->
                        val (fileName, resolvedMimeType, _) = getFileDetailsFromUri(context, uri)
                        val mimeType = resolvedMimeType ?: "image/*"

                        val isFileSizeValid = if (mimeType.startsWith("video/")) {
                            checkAttachmentFileSizeAndShowError(context, uri, fileName, onShowSnackbar)
                        } else {
                            validateUserImageForSelection(context, uri, fileName, onShowSnackbar)
                        }
                        if (isFileSizeValid) {
                            withContext(Dispatchers.Main) {
                                if (mimeType.startsWith("video/")) {
                                    onAddMediaItem(SelectedMediaItem.GenericFile(
                                        uri = uri,
                                        id = UUID.randomUUID().toString(),
                                        displayName = fileName,
                                        mimeType = mimeType,
                                        filePath = null
                                    ))
                                } else {
                                    onAddMediaItem(SelectedMediaItem.ImageFromUri(uri, UUID.randomUUID().toString(), mimeType))
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("PhotoPicker", "处理选择的图片时发生错误", e)
                    withContext(Dispatchers.Main) {
                        onShowSnackbar(context.getString(R.string.chat_input_image_selection_error))
                    }
                }
            }
        } else {
            Log.d("PhotoPicker", "用户取消了图片选择")
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val currentUri = tempCameraImageUri
        tempCameraImageUri = null
        if (success && currentUri != null) {
            coroutineScope.launch {
                try {
                    if (validateUserImageForSelection(context, currentUri, onShowError = onShowSnackbar)) {
                        withContext(Dispatchers.Main.immediate) {
                            onAddMediaItem(
                                SelectedMediaItem.ImageFromUri(
                                    currentUri,
                                    UUID.randomUUID().toString(),
                                    "image/jpeg",
                                ),
                            )
                        }
                    } else {
                        safeDeleteTempFile(context, currentUri)
                    }
                } catch (e: CancellationException) {
                    safeDeleteTempFile(context, currentUri)
                    throw e
                } catch (e: Exception) {
                    Log.e("CameraLauncher", "处理相机照片时发生错误", e)
                    withContext(Dispatchers.Main.immediate) {
                        onShowSnackbar(context.getString(R.string.chat_input_camera_capture_error))
                    }
                    safeDeleteTempFile(context, currentUri)
                }
            }
        } else {
            Log.w("CameraLauncher", "相机拍照失败或被取消")
            if (currentUri != null) safeDeleteTempFile(context, currentUri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val newUri = createImageFileUri(context)
                tempCameraImageUri = newUri
                cameraLauncher.launch(newUri)
            } catch (e: Exception) {
                Log.e("CameraPermission", "创建相机文件 URI 时发生错误", e)
                onShowSnackbar(context.getString(R.string.chat_input_camera_start_error))
            }
        } else {
            Log.w("CameraPermission", "相机权限被拒绝")
            onShowSnackbar(context.getString(R.string.chat_input_camera_permission_required))
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        uris.forEach { uri ->
                            val (displayName, mimeType, _) = getFileDetailsFromUri(context, uri)
                            Log.d(
                                "OpenDocument",
                                "Selected Document: $displayName, URI: $uri, MIME: $mimeType"
                            )
                            
                            // 检查文件大小
                            val isFileSizeValid = checkAttachmentFileSizeAndShowError(
                                context,
                                uri,
                                displayName,
                                onShowSnackbar,
                            )
                            if (isFileSizeValid) {
                                withContext(Dispatchers.Main) {
                                    onAddMediaItem(
                                        SelectedMediaItem.GenericFile(
                                            uri = uri,
                                            id = UUID.randomUUID().toString(),
                                            displayName = displayName,
                                            mimeType = mimeType ?: "*/*",
                                            filePath = null
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("OpenDocument", "处理选择的文件时发生错误", e)
                        withContext(Dispatchers.Main) {
                            onShowSnackbar(context.getString(R.string.chat_input_file_processing_error))
                        }
                    }
                }
            } else {
                Log.d("OpenDocument", "用户取消了文件选择")
            }
        }
    )

    // 🎯 性能优化：使用本地状态管理输入文本，避免每次按键都触发 ViewModel 更新
    // 这样可以大幅减少 ChatScreen 的重组次数，解决长文本输入卡顿问题
    // 🔧 修复：使用 TextFieldValue 替代 String，以更好地兼容华为小艺输入法等 IME 的剪贴板粘贴行为
    var localTextFieldValue by remember {
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var skillReferences by remember { mutableStateOf<List<MessageSkillReference>>(emptyList()) }
    
    // 防抖同步 Job，用于取消上一次未完成的同步
    var syncJob by remember { mutableStateOf<Job?>(null) }
    
    // 当外部 text 变化时（如清空、恢复草稿），同步到本地状态
    // 使用 key 来区分外部变化和本地变化
    var lastExternalText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != lastExternalText) {
            lastExternalText = text
            // 更新 TextFieldValue，保持光标在末尾
            localTextFieldValue = TextFieldValue(text, TextRange(text.length))
            skillReferences = emptyList()
        }
    }
    
    // 防抖同步到 ViewModel（使用 PerformanceConfig 中定义的延迟）
    val localText = localTextFieldValue.text
    val slashQuery = findSkillSlashQuery(localTextFieldValue)
    var dismissedSlashSignature by remember { mutableStateOf<String?>(null) }
    val slashSignature = slashQuery?.let { "${it.start}:${it.end}:${it.query}" }
    val activeSlashQuery = slashQuery?.takeUnless { slashSignature == dismissedSlashSignature }
    val skillCandidates = remember(installedSkills, activeSlashQuery?.query) {
        activeSlashQuery?.let { rankSkillCandidates(installedSkills, it.query) }.orEmpty()
    }
    var selectedSkillCandidateIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(slashSignature, skillCandidates.size) {
        selectedSkillCandidateIndex = selectedSkillCandidateIndex.coerceIn(0, (skillCandidates.size - 1).coerceAtLeast(0))
    }

    fun selectSkillCandidate(index: Int) {
        val query = activeSlashQuery ?: return
        val skill = skillCandidates.getOrNull(index) ?: return
        val reference = MessageSkillReference(
            skillId = skill.skillId,
            displayName = skill.name,
            sourceType = runCatching { SkillSourceType.valueOf(skill.sourceType) }.getOrDefault(SkillSourceType.LOCAL_IMPORT),
            sourceRepository = skill.sourceRepository,
            sourcePath = skill.sourcePath,
            contentHash = skill.currentHash,
        )
        val next = insertSkillReference(localTextFieldValue, skillReferences, query, reference)
        localTextFieldValue = next.value
        skillReferences = next.references
        dismissedSlashSignature = null
    }

    BackHandler(enabled = activeSlashQuery != null) {
        dismissedSlashSignature = slashSignature
    }
    LaunchedEffect(localText) {
        // 取消上一次的同步任务
        syncJob?.cancel()
        syncJob = coroutineScope.launch {
            delay(PerformanceConfig.STATE_DEBOUNCE_DELAY_MS)
            if (localText != text) {
                onTextChange(localText)
                lastExternalText = localText
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible }
            .collect { _ ->
                pendingMessageTextForSend = null
            }
    }

    var chatInputContentHeightPx by remember { mutableIntStateOf(0) }

    val onToggleImagePanel = {
        if (showMoreOptionsPanel) showMoreOptionsPanel = false
        val now = android.os.SystemClock.uptimeMillis()
        if (!showImageSelectionPanel && now - lastImagePanelDismissAt < 200L) {
            // 忽略由外点关闭触发后紧随的按钮抬起 reopen
        } else {
            showImageSelectionPanel = !showImageSelectionPanel
        }
    }
    val onToggleMoreOptionsPanel = {
        if (showImageSelectionPanel) showImageSelectionPanel = false
        val now = android.os.SystemClock.uptimeMillis()
        if (!showMoreOptionsPanel && now - lastMorePanelDismissAt < 200L) {
            // 忽略由外点关闭触发后紧随的按钮抬起 reopen
        } else {
            showMoreOptionsPanel = !showMoreOptionsPanel
        }
    }

    val onClearContent = remember {
        {
            onTextChange("")
            onClearMediaItems()
            Unit
        }
    }

    // 🎯 性能优化：发送时使用本地文本，确保发送最新内容
    val onSendClick =
        remember(isApiCalling, isRemoteCancellationPending, localText, selectedMediaItems, selectedApiConfig, imeInsets, density) {
            {
                try {
                    if (isRemoteCancellationPending) {
                        // 远端取消尚未确认，固定按钮只展示加载，不重复发起取消或新消息。
                    } else if (isApiCalling) {
                        onStopApiCall()
                    } else if (localText.isBlank() && selectedMediaItems.isEmpty()) {
                        onShowVoiceInput()
                    } else if (selectedApiConfig != null) {
                        val audioItem = selectedMediaItems.firstOrNull { it is SelectedMediaItem.Audio } as? SelectedMediaItem.Audio
                        val mimeType = audioItem?.mimeType
                        // 使用本地文本发送消息
                        val contentParts = buildSkillContentParts(localText, skillReferences)
                        onSendMessageRequest(
                            displaySkillEditorText(localText, skillReferences),
                            false,
                            selectedMediaItems.toList(),
                            mimeType,
                            contentParts,
                        )
                        // 同时清空本地状态和 ViewModel 状态
                        localTextFieldValue = TextFieldValue("", TextRange(0))
                        skillReferences = emptyList()
                        lastExternalText = ""
                        onTextChange("")
                        onClearMediaItems()
                        // 取消待处理的同步任务
                        syncJob?.cancel()
                        
                        if (imeInsets.getBottom(density) > 0) {
                            keyboardController?.hide()
                        }
                    } else {
                        Log.w("SendMessage", "请先选择 API 配置")
                        onShowSnackbar(context.getString(R.string.chat_input_select_api_configuration))
                    }
                } catch (e: Exception) {
                    Log.e("SendMessage", "发送消息时发生错误", e)
                    onShowSnackbar(context.getString(R.string.chat_input_send_failed))
                }
                Unit
            }
        }

    val inputBackgroundColor = MaterialTheme.colorScheme.background
    
    // 使用 WindowInsets 组合逻辑来统一处理底部间距，消除手动计算带来的动画抖动
    val navInsets = WindowInsets.navigationBarsIgnoringVisibility
    val baseInsets = navInsets.add(WindowInsets(bottom = 12.dp))
    val targetInsets = WindowInsets.ime.union(baseInsets)
    val hostCommandPopupPositionProvider = remember(chatInputContentHeightPx, density) {
        chatInputPopupPositionProvider(chatInputContentHeightPx, density)
    }

    Box(modifier = Modifier
        .fillMaxWidth()
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    inputBackgroundColor.copy(alpha = 0f),
                    inputBackgroundColor
                )
            )
        )
        // 统一按 ime ∪ (navigationBars + 24dp) 处理，交由系统 Layout 阶段平滑过渡
        .onSizeChanged { intSize -> 
            chatInputContentHeightPx = intSize.height 
            onHeightChange(intSize.height)
        }
        .windowInsetsPadding(targetInsets)
    ) {
        // 权限卡使用 Popup 覆盖在输入区上方，不参与输入区高度和聊天列表测量。
        ComputerHostCommandConfirmationCard(
            request = hostCommandConfirmationRequest,
            popupPositionProvider = hostCommandPopupPositionProvider,
            onDecision = { requestId, approved ->
                viewModel.respondToComputerHostCommand(requestId, approved)
            },
            onVisibilityChange = onHostCommandCardVisibilityChange,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(1f)
                .align(Alignment.BottomCenter)
                .padding(start = 6.dp, end = 6.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                // 普通输入附件继续沿用输入框的水平留白，权限卡片单独占满统一悬浮层宽度。
                // 使用优化的组件。只给普通附件自身保留输入区的水平留白。
                OptimizedMediaItemsList(
                    selectedMediaItems = selectedMediaItems,
                    onRemoveMediaItemAtIndex = onRemoveMediaItemAtIndex,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )

                val hasContent = localText.isNotEmpty() || selectedMediaItems.isNotEmpty()
                val isDarkTheme = isSystemInDarkTheme()
                var isFocused by remember { mutableStateOf(false) }
                var showFunctionPanel by remember { mutableStateOf(false) }
                var lastFunctionPanelDismissAt by remember { mutableLongStateOf(0L) }

                BackHandler(enabled = showFunctionPanel) {
                    lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                    showFunctionPanel = false
                }
                BackHandler(enabled = showComputerSelectionPopup) {
                    showComputerSelectionPopup = false
                    pendingApprovalForComputerSelection = null
                }

                fun openComputerSelection(enableAgentAfterSelection: Boolean) {
                    enableAgentAfterComputerSelection = enableAgentAfterSelection
                    showFunctionPanel = false
                    showComputerSelectionPopup = true
                }

                fun toggleAgent() {
                    val selectedComputer = computers.firstOrNull { it.id == selectedComputerId }
                    when (
                        resolveAgentToggleAction(
                            isEnabled = isAgentEnabled,
                            isPreparing = isAgentPreparing,
                            hasSelectedComputer = selectedComputer != null,
                        )
                    ) {
                        AgentToggleAction.DISABLE -> viewModel.setAgentEnabled(false)
                        AgentToggleAction.OPEN_SERVER_PICKER ->
                            openComputerSelection(enableAgentAfterSelection = true)
                        AgentToggleAction.ENABLE_SELECTED -> {
                            val computer = requireNotNull(selectedComputer)
                            if (computer.status != ComputerStatus.READY) {
                                val status = context.getString(computerStatusLabelRes(computer))
                                onShowSnackbar(context.getString(R.string.agent_server_cannot_select, status))
                                return
                            }
                            requestAgentAction(
                                PendingAgentAction(
                                    computer = computer,
                                    conversationId = currentConversationId,
                                    selectComputer = false,
                                    enableAgentAfterSelection = true,
                                    requiresDisclosure = true,
                                ),
                            )
                        }
                    }
                }

                fun selectComputer(computer: Computer) {
                    if (computer.status != ComputerStatus.READY) {
                        val status = context.getString(computerStatusLabelRes(computer))
                        onShowSnackbar(context.getString(R.string.agent_server_cannot_select, status))
                        return
                    }
                    val enableAfterSelection = enableAgentAfterComputerSelection
                    val approval = pendingApprovalForComputerSelection
                    showComputerSelectionPopup = false
                    requestAgentAction(
                        PendingAgentAction(
                            computer = computer,
                            conversationId = currentConversationId,
                            selectComputer = true,
                            enableAgentAfterSelection = enableAfterSelection,
                            requiresDisclosure = enableAfterSelection || isAgentEnabled,
                            onCompleted = approval?.let { pending ->
                                {
                                    pendingApprovalForComputerSelection = null
                                    viewModel.respondToAgentEnableApproval(
                                        pending.runId,
                                        pending.approvalRequestId,
                                        approved = true,
                                    )
                                }
                            },
                            onFailed = approval?.let { { pendingApprovalForComputerSelection = null } },
                        ),
                    )
                }

                // 输入法收起/展开进度直接跟随 imeInsets，避免等 isImeVisible 布尔值最后一刻才切换
                val imeBottomPx = imeInsets.getBottom(density)
                var maxImeBottomPx by remember { mutableIntStateOf(0) }
                if (imeBottomPx > maxImeBottomPx) {
                    maxImeBottomPx = imeBottomPx
                }
                val imeProgress = if (maxImeBottomPx > 0) {
                    (imeBottomPx.toFloat() / maxImeBottomPx.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val isImeVisible = imeProgress > 0.01f
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                LaunchedEffect(isImeVisible, localText) {
                    if (!isImeVisible && !showFunctionPanel && localText.isEmpty()) {
                        focusManager.clearFocus()
                    }
                }
                LaunchedEffect(localText, isFocused) {
                    if (localText.isNotEmpty() && !isFocused) {
                        focusRequester.requestFocus()
                    }
                }

                // 增强 Gemini 渠道检测
                val isGeminiChannel = selectedApiConfig?.let { config ->
                    com.android.everytalk.data.network.WebSearchSupport.isGeminiNativeSearch(config)
                } == true
                val supportsNativeWebSearch = selectedApiConfig?.let { config ->
                    com.android.everytalk.data.network.WebSearchSupport.supportsNativeWebSearch(config)
                } == true
                val effectiveWebSearchAvailable = isWebSearchAvailable || supportsNativeWebSearch
                val activeTagCount =
                    (if (isWebSearchEnabled && effectiveWebSearchAvailable) 1 else 0) +
                        (if (isMcpEnabled) 1 else 0) +
                        (if (isAgentEnabled) 1 else 0)
                val hasActiveTags = activeTagCount > 0

                // 合并保护：只有输入框完全回到原始状态（无文本、无媒体、无标签）
                // 且输入框高度动画已完成后，才允许合并
                val hasAnyContent = localText.isNotEmpty() || selectedMediaItems.isNotEmpty() || hasActiveTags
                var inputHeightSettled by remember { mutableStateOf(true) }
                var prevHasAnyContent by remember { mutableStateOf(hasAnyContent) }

                // 当内容从有变无时，标记高度未稳定，等待 animateContentSize 完成
                LaunchedEffect(hasAnyContent) {
                    if (prevHasAnyContent && !hasAnyContent) {
                        // 内容刚清空，等输入框高度动画结束再允许合并
                        inputHeightSettled = false
                        delay(300L)
                        inputHeightSettled = true
                    }
                    prevHasAnyContent = hasAnyContent
                }

                val keepInputSeparated = hasAnyContent || !inputHeightSettled
                val separationTarget = if (keepInputSeparated) 1f else imeProgress
                val separationProgress by animateFloatAsState(
                    targetValue = separationTarget,
                    animationSpec = spring(
                        dampingRatio = 0.857f,
                        stiffness = 150f
                    ),
                    label = "separationProgress"
                )
                val sizeProgress = separationProgress
                val verticalPadding = ((4f - 1f * sizeProgress).coerceAtLeast(0f)).dp
                val inputMinHeight = ((48f - 4f * sizeProgress).coerceIn(44f, 48f)).dp

                val buttonBackgroundColor by animateColorAsState(
                    targetValue = if (isDarkTheme) Color.White else Color.Black,
                    animationSpec = tween(durationMillis = 200),
                    label = "SendButtonBackground"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (isDarkTheme) Color.Black else Color.White,
                    animationSpec = tween(durationMillis = 200),
                    label = "SendButtonIcon"
                )

                val inputBackground = if (isDarkTheme) Color(0xFF1F1F1F) else Color.White

                // 输入区域
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .graphicsLayer { clip = false },
                    contentAlignment = Alignment.Center
                ) {
                    val sep = separationProgress.coerceIn(0f, 1f)
                    val layoutProgress = sep * sep * (3f - 2f * sep)
                    val plusMotionProgress = layoutProgress
                    val textPaddingProgress = layoutProgress * layoutProgress
                    val expandedInputFieldWidth = (maxWidth * 0.82f).coerceAtMost(maxWidth - 48.dp)
                    val collapsedInputFieldWidth = expandedInputFieldWidth + 16.dp
                    val inputFieldWidth = collapsedInputFieldWidth + (expandedInputFieldWidth - collapsedInputFieldWidth) * layoutProgress
                    val plusBoxWidth = 64.dp

                    val plusStretchProgress = (plusMotionProgress * 2f).coerceIn(0f, 1f)
                    val plusRecoverProgress = ((plusMotionProgress - 0.5f) * 2f).coerceIn(0f, 1f)
                    val plusBorderInset = if (isDarkTheme) 1.dp * (1f - plusMotionProgress) else 0.dp
                    val plusWidth = (48f + 16f * plusStretchProgress - 20f * plusRecoverProgress).dp - plusBorderInset * 2
                    val plusOffset = plusBorderInset + (-20f * plusStretchProgress - 40f * plusRecoverProgress).dp
                    val groupLeft = if (plusOffset < 0.dp) plusOffset * layoutProgress else 0.dp
                    val groupWidth = inputFieldWidth - groupLeft
                    val plusHeight = inputMinHeight - plusBorderInset * 2
                    val plusCorner = plusHeight / 2
                    val plusShape = RoundedCornerShape(plusCorner)
                    val plusBg = inputBackground
                    val borderColor = if (isDarkTheme) Color(0xFF48474C) else Color(0xFFD6D6D6)
                    val separatedBorderAlpha = if (isDarkTheme) (((layoutProgress - 0.15f) / 0.35f).coerceIn(0f, 1f)) else 0f
                    val plusBorderAlpha = if (isDarkTheme && separationTarget > 0.5f) separatedBorderAlpha else 0f
                    val collapsedInputBorderAlpha = if (isDarkTheme) (((0.35f - layoutProgress) / 0.35f).coerceIn(0f, 1f)) else 0f
                    val inputBorderAlpha = kotlin.math.max(separatedBorderAlpha, collapsedInputBorderAlpha)

                    val inputShape = RoundedCornerShape(inputMinHeight / 2)
                    val textStartPadding = 48.dp - (48.dp - 16.dp) * textPaddingProgress

                    Box(
                        modifier = Modifier
                            .width(groupWidth)
                            .wrapContentHeight()
                            .graphicsLayer { clip = false },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // 加号按钮
                        Box(
                            modifier = Modifier
                                .offset(x = -groupLeft)
                                .zIndex(2f)
                                .graphicsLayer { clip = false }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(plusBoxWidth)
                                    .height(plusHeight)
                                    .wrapContentWidth(Alignment.Start)
                                    .graphicsLayer { clip = false }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = plusOffset)
                                        .width(plusWidth)
                                        .height(plusHeight)
                                        .diffuseShadow(
                                            color = Color.Black,
                                            alpha = 0.12f * layoutProgress,
                                            borderRadius = plusCorner,
                                            shadowRadius = 24.dp,
                                            offsetY = 0.dp,
                                            offsetX = 0.dp
                                        )
                                        .background(plusBg, plusShape)
                                        .then(
                                            if (isDarkTheme) {
                                                Modifier.border(1.dp, borderColor.copy(alpha = plusBorderAlpha), plusShape)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    IconButton(
                                        onClick = {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            if (!showFunctionPanel && now - lastFunctionPanelDismissAt < 200L) {
                                                return@IconButton
                                            }
                                            showFunctionPanel = !showFunctionPanel
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_plus),
                                            contentDescription = stringResource(
                                                if (showFunctionPanel) {
                                                    R.string.chat_input_collapse_functions
                                                } else {
                                                    R.string.chat_input_expand_functions
                                                }
                                            ),
                                            tint = if (isDarkTheme) Color.White else Color(0xFF0D0D0D),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            AppFloatingCardPopup(
                                visible = activeSlashQuery != null && skillCandidates.isNotEmpty(),
                                popupPositionProvider = hostCommandPopupPositionProvider,
                                onDismissRequest = { dismissedSlashSignature = slashSignature },
                                properties = PopupProperties(
                                    focusable = false,
                                    dismissOnBackPress = false,
                                    dismissOnClickOutside = true,
                                ),
                                modifier = Modifier.widthIn(min = 240.dp, max = 340.dp),
                            ) {
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    skillCandidates.forEachIndexed { index, skill ->
                                        val selected = index == selectedSkillCandidateIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else Color.Transparent,
                                                )
                                                .clickable { selectSkillCandidate(index) }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    skill.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                            Text("/", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }

                            AppFloatingCardPopup(
                                visible = showFunctionPanel,
                                popupPositionProvider = hostCommandPopupPositionProvider,
                                onDismissRequest = {
                                    lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                    if (showFunctionPanel) showFunctionPanel = false
                                },
                                properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true),
                                modifier = Modifier
                                    .widthIn(max = 320.dp)
                                    .wrapContentHeight(),
                            ) {
                                FunctionPanelContent(
                                    isWebSearchEnabled = isWebSearchEnabled,
                                    isWebSearchAvailable = effectiveWebSearchAvailable,
                                    onToggleWebSearch = onToggleWebSearch,
                                    isCodeExecutionEnabled = isCodeExecutionEnabled,
                                    onToggleCodeExecution = onToggleCodeExecution,
                                    isGeminiChannel = isGeminiChannel,
                                    onToggleImagePanel = onToggleImagePanel,
                                    onToggleMoreOptionsPanel = onToggleMoreOptionsPanel,
                                    hasContent = hasContent,
                                    onClearContent = {
                                        localTextFieldValue = TextFieldValue("", TextRange(0))
                                        skillReferences = emptyList()
                                        lastExternalText = ""
                                        onTextChange("")
                                        onClearMediaItems()
                                        syncJob?.cancel()
                                    },
                                    onDismiss = {
                                        lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                        showFunctionPanel = false
                                    },
                                    isMcpEnabled = isMcpEnabled,
                                    onToggleMcp = { viewModel.setMcpEnabledForNextRequest(!isMcpEnabled) },
                                    isAgentEnabled = isAgentEnabled,
                                    isAgentPreparing = isAgentPreparing,
                                    onToggleAgent = ::toggleAgent,
                                    onLongPressAgent = {
                                        openComputerSelection(enableAgentAfterSelection = false)
                                    },
                                    onOpenFilePicker = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    onOpenCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    onOpenGallery = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                        )
                                    },
                                    onOpenSystemPrompt = {
                                        viewModel.showSystemPromptDialog()
                                    }
                                )
                            }

                            AppFloatingCardPopup(
                                visible = showImageSelectionPanel,
                                alignment = Alignment.BottomStart,
                                offset = IntOffset(0, with(density) { (-56).dp.toPx().toInt() }),
                                onDismissRequest = {
                                    lastImagePanelDismissAt = android.os.SystemClock.uptimeMillis()
                                    if (showImageSelectionPanel) showImageSelectionPanel = false
                                },
                                properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
                            ) {
                                OptimizedImageSelectionPanel { selectedOption ->
                                    if (showImageSelectionPanel) showImageSelectionPanel = false
                                    when (selectedOption) {
                                        ImageSourceOption.ALBUM -> photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                        )
                                        ImageSourceOption.CAMERA -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            }

                            AppFloatingCardPopup(
                                visible = showComputerSelectionPopup,
                                popupPositionProvider = hostCommandPopupPositionProvider,
                                onDismissRequest = {
                                    showComputerSelectionPopup = false
                                    pendingApprovalForComputerSelection = null
                                },
                                properties = PopupProperties(
                                    focusable = true,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true,
                                ),
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .widthIn(max = 320.dp)
                                    .wrapContentHeight(),
                            ) {
                                ComputerSelectionCard(
                                    computers = computers,
                                    selectedComputerId = selectedComputerId,
                                    onSelect = ::selectComputer,
                                    onUnavailable = ::selectComputer,
                                    onAddComputer = {
                                        showComputerSelectionPopup = false
                                        pendingApprovalForComputerSelection = null
                                        onOpenComputerSettings()
                                    },
                                )
                            }

                            AppFloatingCardPopup(
                                visible = showMoreOptionsPanel,
                                alignment = Alignment.BottomStart,
                                offset = IntOffset(0, with(density) { (-56).dp.toPx().toInt() }),
                                onDismissRequest = {
                                    lastMorePanelDismissAt = android.os.SystemClock.uptimeMillis()
                                    if (showMoreOptionsPanel) showMoreOptionsPanel = false
                                },
                                properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
                            ) {
                                OptimizedMoreOptionsPanel(isMcpEnabled = isMcpEnabled) { selectedOption ->
                                    when (selectedOption) {
                                        MoreOptionsType.MCP -> {
                                            viewModel.setMcpEnabledForNextRequest(!isMcpEnabled)
                                        }
                                        else -> {
                                            if (showMoreOptionsPanel) showMoreOptionsPanel = false
                                            val mimeTypesArray = Array(selectedOption.mimeTypes.size) { index ->
                                                selectedOption.mimeTypes[index]
                                            }
                                            filePickerLauncher.launch(mimeTypesArray)
                                        }
                                    }
                                }
                            }
                        }

                        // 输入框
                        val inputModifier = Modifier
                            .offset(x = -groupLeft)
                            .width(inputFieldWidth)
                            .align(Alignment.CenterStart)
                            .zIndex(1f)
                            .graphicsLayer { clip = false }
                        
                        val shadowedInputModifier = if (!isDarkTheme) {
                            inputModifier.diffuseShadow(
                                color = Color.Black,
                                alpha = 0.12f,
                                borderRadius = inputMinHeight / 2,
                                shadowRadius = 24.dp,
                                offsetY = 0.dp,
                                offsetX = 0.dp
                            )
                        } else {
                            inputModifier
                        }

                        BasicTextField(
                            value = localTextFieldValue,
                            onValueChange = { newValue ->
                                val next = normalizeSkillEdit(localTextFieldValue, newValue, skillReferences)
                                localTextFieldValue = next.value
                                skillReferences = next.references
                                dismissedSlashSignature = null
                            },
                            visualTransformation = SkillTagVisualTransformation(
                                references = skillReferences,
                                textColor = if (isDarkTheme) Color(0xFF99CEFF) else Color(0xFF026FC2),
                                backgroundColor = if (isDarkTheme) Color(0xFF173B59) else Color(0xFFDDEEFF),
                            ),
                            modifier = shadowedInputModifier
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown || activeSlashQuery == null) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            selectedSkillCandidateIndex = (selectedSkillCandidateIndex + 1)
                                                .coerceAtMost((skillCandidates.size - 1).coerceAtLeast(0))
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            selectedSkillCandidateIndex = (selectedSkillCandidateIndex - 1).coerceAtLeast(0)
                                            true
                                        }
                                        Key.Enter, Key.NumPadEnter -> {
                                            selectSkillCandidate(selectedSkillCandidateIndex)
                                            true
                                        }
                                        Key.Escape -> {
                                            dismissedSlashSignature = slashSignature
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                    if (!focusState.isFocused) onFocusChange(false)
                                },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                if (isDarkTheme) Color(0xFF99CEFF) else Color(0xFF0285FF)
                            ),
                            maxLines = 5,
                            decorationBox = { innerTextField ->
                                val safeVerticalPadding = verticalPadding.coerceAtLeast(0.dp)
                                Column(
                                    modifier = Modifier
                                        .heightIn(min = inputMinHeight)
                                        .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                                        .background(inputBackground, inputShape)
                                        .border(1.dp, borderColor.copy(alpha = inputBorderAlpha), inputShape)
                                        .padding(start = textStartPadding, end = 5.dp, top = safeVerticalPadding, bottom = safeVerticalPadding)
                                ) {
                                    if (hasActiveTags) {
                                        FlowRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // 输入容器右侧只有 5dp，这里补齐到左侧的 16dp，保证标签首尾留白一致。
                                                .padding(top = 4.dp, end = 11.dp, bottom = 2.dp),
                                            maxItemsInEachRow = 3,
                                            horizontalArrangement = if (activeTagCount == 3) {
                                                Arrangement.SpaceBetween
                                            } else {
                                                Arrangement.spacedBy(2.dp)
                                            },
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            if (isWebSearchEnabled && effectiveWebSearchAvailable) {
                                                ActiveFunctionTag(
                                                    iconRes = R.drawable.ic_globe,
                                                    label = stringResource(R.string.chat_input_search_tag),
                                                    tint = Color(0xFF66B5FF),
                                                    lightBackground = Color(0xFFDDEEFF),
                                                    closeContentDescription = stringResource(R.string.chat_input_close_web_search),
                                                    onClick = onToggleWebSearch,
                                                )
                                            }
                                            if (isMcpEnabled) {
                                                ActiveFunctionTag(
                                                    iconRes = R.drawable.ic_hammer,
                                                    label = stringResource(R.string.chat_input_mcp),
                                                    tint = ChatMcpColor,
                                                    lightBackground = Color(0xFFFFECE5),
                                                    closeContentDescription = stringResource(R.string.chat_input_close_mcp),
                                                    onClick = { viewModel.setMcpEnabledForNextRequest(false) },
                                                )
                                            }
                                            if (isAgentEnabled) {
                                                ActiveFunctionTag(
                                                    iconRes = R.drawable.ic_gpt_terminal,
                                                    label = stringResource(R.string.chat_input_agent),
                                                    tint = ChatAgentColor,
                                                    lightBackground = Color(0xFFE0F2F1),
                                                    closeContentDescription = stringResource(R.string.chat_input_close_agent),
                                                    onClick = { viewModel.setAgentEnabled(false) },
                                                    onLongClick = {
                                                        openComputerSelection(enableAgentAfterSelection = false)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = (inputMinHeight - safeVerticalPadding * 2).coerceAtLeast(0.dp)),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (localText.isEmpty()) {
                                            Text(
                                                stringResource(
                                                    if (isWebSearchEnabled && effectiveWebSearchAvailable) {
                                                        R.string.chat_input_search_web_hint
                                                    } else {
                                                        R.string.chat_input_reply_hint
                                                    }
                                                ),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (isDarkTheme) Color(0xFFAFAFAF) else Color(0xFF8F8F8F)
                                            )
                                        }
                                        innerTextField()
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    val buttonState = when {
                                        isRemoteCancellationPending -> 3
                                        isApiCalling -> 2
                                        hasContent -> 1
                                        else -> 0
                                    }
                                    AnimatedContent(
                                        targetState = buttonState,
                                        transitionSpec = {
                                            (fadeIn(tween(220)) + scaleIn(
                                                tween(220),
                                                initialScale = 0.8f
                                            )).togetherWith(
                                                fadeOut(tween(150)) + scaleOut(
                                                    tween(150),
                                                    targetScale = 0.6f
                                                )
                                            )
                                        },
                                        label = "InputSendButton"
                                    ) { state ->
                                        FilledIconButton(
                                            onClick = onSendClick,
                                            shape = CircleShape,
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = buttonBackgroundColor,
                                                contentColor = iconColor
                                            ),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            if (state == 3) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    color = iconColor,
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Icon(
                                                    painter = when (state) {
                                                        2 -> painterResource(R.drawable.ic_stop)
                                                        1 -> painterResource(R.drawable.ic_arrow_up)
                                                        else -> painterResource(R.drawable.ic_voice_bold)
                                                    },
                                                    contentDescription = when (state) {
                                                        2 -> stringResource(R.string.chat_input_stop)
                                                        1 -> stringResource(R.string.chat_input_send)
                                                        else -> stringResource(R.string.chat_input_voice)
                                                    },
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                        )
                    }
                }
            }
            
            // 已由 Column 自身处理 navigationBars + ime 内边距，移除额外 spacer
        }
    }

    ChatInputDialogs(
        showMcpServerListDialog = showMcpServerListDialog,
        onShowMcpServerListDialogChange = { showMcpServerListDialog = it },
        viewModel = viewModel,
        mcpServerStates = mcpServerStates,
        onAddMcpServer = onAddMcpServer,
        onRemoveMcpServer = onRemoveMcpServer,
        onToggleMcpServer = onToggleMcpServer,
        tempCameraImageUri = tempCameraImageUri,
        context = context,
    )

    AgentDisclosureDialog(
        computer = pendingAgentAction?.computer,
        disclosures = pendingAgentDisclosures,
        onConfirm = {
            val action = pendingAgentAction
            if (action != null) {
                disclosureStore.accept(action.computer, pendingAgentDisclosures)
                pendingAgentAction = null
                pendingAgentDisclosures = emptySet()
                executeAgentAction(action)
            }
        },
        onDismiss = {
            if (pendingAgentAction?.onCompleted != null) pendingApprovalForComputerSelection = null
            pendingAgentAction = null
            pendingAgentDisclosures = emptySet()
        },
    )

    agentEnableApprovalRequest?.takeIf { pendingApprovalForComputerSelection == null }?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("开启 Agent？") },
            text = { Text(request.reason) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedComputer = computers.firstOrNull { it.id == selectedComputerId }
                            ?.takeIf { it.status == ComputerStatus.READY }
                        if (selectedComputer == null) {
                            pendingApprovalForComputerSelection = request
                            enableAgentAfterComputerSelection = true
                            showComputerSelectionPopup = true
                        } else {
                            requestAgentAction(
                                PendingAgentAction(
                                    computer = selectedComputer,
                                    conversationId = currentConversationId,
                                    selectComputer = false,
                                    enableAgentAfterSelection = true,
                                    requiresDisclosure = true,
                                    onCompleted = {
                                        viewModel.respondToAgentEnableApproval(
                                            request.runId,
                                            request.approvalRequestId,
                                            approved = true,
                                        )
                                    },
                                    onFailed = { pendingApprovalForComputerSelection = null },
                                ),
                            )
                        }
                    },
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingApprovalForComputerSelection = null
                        viewModel.respondToAgentEnableApproval(
                            request.runId,
                            request.approvalRequestId,
                            approved = false,
                        )
                    },
                ) { Text("拒绝") }
            },
        )
    }

    skillSecretApprovalRequest?.let { request ->
        var secret by remember(request.approvalRequestId) { mutableStateOf("") }
        var rememberSecret by remember(request.approvalRequestId) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("提供 Skill 密钥？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${request.skillName} 申请 ${request.name}")
                    Text(request.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(request.name) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberSecret, onCheckedChange = { rememberSecret = it })
                        Text("记住此密钥")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = secret.isNotEmpty(),
                    onClick = {
                        val chars = secret.toCharArray()
                        secret = ""
                        viewModel.respondToSkillSecretApproval(
                            request.runId,
                            request.approvalRequestId,
                            chars,
                            rememberSecret,
                        )
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = {
                    secret = ""
                    viewModel.respondToSkillSecretApproval(
                        request.runId,
                        request.approvalRequestId,
                        null,
                        false,
                    )
                }) { Text("拒绝") }
            },
        )
    }
}
