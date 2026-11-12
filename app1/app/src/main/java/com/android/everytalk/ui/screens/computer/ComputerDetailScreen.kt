package com.android.everytalk.ui.screens.computer

import com.android.everytalk.ui.components.dialog.AppOutlinedButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuditEvent
import com.android.everytalk.data.computer.ComputerCredentialState
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerErrorCodes
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerDiagnostics
import com.android.everytalk.data.computer.ComputerFailureStage
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.confirmComputerReplacementHostKey
import com.android.everytalk.statecontroller.deleteComputer
import com.android.everytalk.statecontroller.disconnectComputer
import com.android.everytalk.statecontroller.getConversationFullText
import com.android.everytalk.statecontroller.observeComputerAuditEvents
import com.android.everytalk.statecontroller.observeComputerActiveTaskCount
import com.android.everytalk.statecontroller.observeComputerWorkspaces
import com.android.everytalk.statecontroller.probeUpdatedComputerHostKey
import com.android.everytalk.statecontroller.probeComputerReplacementHostKey
import com.android.everytalk.statecontroller.refreshComputer
import com.android.everytalk.statecontroller.setComputerPermissionMode
import com.android.everytalk.statecontroller.showSnackbar
import com.android.everytalk.statecontroller.updateComputer
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogActionContent
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.computerStatusLabelRes
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ComputerDetailScreen(
    viewModel: AppViewModel,
    navController: NavController,
    computerId: String,
    modifier: Modifier = Modifier,
) {
    val computers by viewModel.computers.collectAsState()
    val computer = computers.firstOrNull { it.id == computerId }
    if (computer?.provider == com.android.everytalk.data.computer.ComputerProvider.CLOUDFLARE) {
        CloudflareComputerDetail(viewModel, navController, computer, modifier)
        return
    }
    val context = LocalContext.current
    val workspacesFlow = remember(computerId) { viewModel.observeComputerWorkspaces(computerId) }
    val activeTaskCountFlow = remember(computerId) { viewModel.observeComputerActiveTaskCount(computerId) }
    val auditFlow = remember(computerId) { viewModel.observeComputerAuditEvents(computerId) }
    val workspaces by workspacesFlow.collectAsState(initial = emptyList())
    val activeTaskCount by activeTaskCountFlow.collectAsState(initial = 0)
    val audits by auditFlow.collectAsState(initial = emptyList())
    val screenOpenedAt = remember(computerId) { System.currentTimeMillis() }
    var lastShownHostKeyAuditId by remember(computerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(audits) {
        val latestHostKeyUpdate = audits.firstOrNull { it.eventType == "HOST_KEY_AUTO_ACCEPTED" }
        if (
            latestHostKeyUpdate != null &&
            latestHostKeyUpdate.createdAt >= screenOpenedAt &&
            latestHostKeyUpdate.id != lastShownHostKeyAuditId
        ) {
            lastShownHostKeyAuditId = latestHostKeyUpdate.id
            viewModel.showSnackbar(context.getString(R.string.computer_host_key_auto_updated))
        }
    }
    val historicalConversations by viewModel.historicalConversations.collectAsState()
    // 会话项目直接显示用户熟悉的会话名称，避免把内部 ID 暴露到界面。
    val conversationNamesById = remember(historicalConversations) {
        historicalConversations.mapIndexedNotNull { index, conversation ->
            ConversationNameHelper.resolveStableId(conversation)?.let { conversationId ->
                conversationId to viewModel.getConversationFullText(index)
            }
        }.toMap()
    }
    val scope = rememberCoroutineScope()
    var busyAction by remember { mutableStateOf<String?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var actionGeneration by remember { mutableStateOf(0) }
    var editDialogVisible by remember { mutableStateOf(false) }
    var editForm by remember(computer?.id) { mutableStateOf(computer?.toEditFormState() ?: ComputerAddFormState()) }
    var editPrepared by remember { mutableStateOf<PreparedComputerUpdate?>(null) }
    var editHostKey by remember { mutableStateOf<HostKeyProbeResult?>(null) }
    var editProgressText by remember { mutableStateOf<String?>(null) }
    var editErrorText by remember { mutableStateOf<String?>(null) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var replacementHostKey by remember { mutableStateOf<HostKeyProbeResult?>(null) }
    var moreSettingsExpanded by remember(computer?.id) { mutableStateOf(false) }
    var pendingPermissionMode by remember(computer?.id) { mutableStateOf<ComputerPermissionMode?>(null) }
    val genericFailure = stringResource(R.string.computer_action_failed)
    val latestEditPrepared by rememberUpdatedState(editPrepared)
    val latestActionJob by rememberUpdatedState(actionJob)
    ComputerSecureWindowEffect(editDialogVisible)

    DisposableEffect(Unit) {
        onDispose {
            val prepared = latestEditPrepared
            val job = latestActionJob
            if (job?.isActive == true) {
                job.cancel()
            } else {
                prepared?.clear()
            }
        }
    }

    fun launchAction(
        action: String,
        onCompletion: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (busyAction != null) return
        val generation = ++actionGeneration
        busyAction = action
        actionJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ComputerDiagnostics.logFailure(ComputerFailureStage.SERVER_DETAIL_ACTION, error)
                viewModel.showSnackbar(error.message ?: genericFailure)
            } finally {
                onCompletion()
                if (actionGeneration == generation) {
                    busyAction = null
                    actionJob = null
                }
            }
        }
    }

    fun cancelActiveAction() {
        actionGeneration += 1
        val cancellingJob = actionJob
        if (cancellingJob == null) {
            busyAction = null
            return
        }
        busyAction = "cancelling"
        cancellingJob.invokeOnCompletion {
            scope.launch {
                if (actionJob === cancellingJob) {
                    actionJob = null
                    busyAction = null
                }
            }
        }
        cancellingJob.cancel()
    }

    fun navigateBack() {
        if (busyAction != null) cancelActiveAction()
        navController.popBackStack()
    }

    // 仅在存在活动任务需要取消时拦截，空闲状态下交给 NavHost 驱动预测式侧滑返回预览。
    BackHandler(enabled = busyAction != null, onBack = ::navigateBack)

    fun validationMessage(error: ComputerAddFormError): String = context.getString(
        when (error) {
            ComputerAddFormError.HOST_REQUIRED -> R.string.computer_validation_host
            ComputerAddFormError.PORT_INVALID -> R.string.computer_validation_port
            ComputerAddFormError.USERNAME_REQUIRED -> R.string.computer_validation_username
            ComputerAddFormError.PASSWORD_REQUIRED -> R.string.computer_validation_password
            ComputerAddFormError.PRIVATE_KEY_REQUIRED -> R.string.computer_validation_private_key
        },
    )

    fun closeEditDialog() {
        val editingInProgress = busyAction == "edit-probe" || busyAction == "edit-save"
        if (editingInProgress) cancelActiveAction() else editPrepared?.clear()
        editPrepared = null
        editHostKey = null
        editProgressText = null
        editErrorText = null
        editForm = computer?.toEditFormState() ?: ComputerAddFormState()
        editDialogVisible = false
    }

    fun startEditHostKeyProbe() {
        if (busyAction != null) return
        val currentComputer = computer ?: return
        val validationError = editForm.validationError(currentComputer.authKind)
        if (validationError != null) {
            editErrorText = validationMessage(validationError)
            return
        }
        editPrepared?.clear()
        val current = editForm.prepareUpdate(currentComputer)
        editPrepared = current
        editErrorText = null
        editProgressText = context.getString(R.string.computer_progress_reading_key)
        var probeSucceeded = false
        launchAction(
            action = "edit-probe",
            onCompletion = { if (!probeSucceeded) current.clear() },
        ) {
            try {
                val result = viewModel.probeUpdatedComputerHostKey(current.request)
                withContext(Dispatchers.Main) {
                    editHostKey = result
                    probeSucceeded = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    if (editPrepared === current) editPrepared = null
                    editErrorText = error.message ?: genericFailure
                }
            } finally {
                withContext(Dispatchers.Main) { editProgressText = null }
            }
        }
    }

    fun confirmEditHostKey() {
        val current = editPrepared ?: return
        val confirmed = editHostKey ?: return
        editHostKey = null
        editProgressText = context.getString(R.string.computer_progress_authenticating)
        launchAction(
            action = "edit-save",
            onCompletion = current::clear,
        ) {
            try {
                viewModel.updateComputer(
                    request = current.request,
                    confirmedHostKey = confirmed,
                    sudoPassword = current.sudoPassword,
                    replaceSudoPassword = current.replaceSudoPassword,
                )
                withContext(Dispatchers.Main) {
                    editPrepared = null
                    editProgressText = null
                    editForm = ComputerAddFormState()
                    editDialogVisible = false
                    viewModel.showSnackbar(context.getString(R.string.computer_edit_success))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    editPrepared = null
                    editProgressText = null
                    // 密码栏留空时本次使用的是本地旧凭据；认证失败不能误导用户以为刚输入的密码有误。
                    editErrorText = if (
                        current.request.credential == null &&
                        editForm.authKind == ComputerAuthKind.PASSWORD &&
                        error is ComputerException &&
                        error.code == ComputerErrorCodes.AUTH_FAILED
                    ) {
                        context.getString(R.string.computer_edit_saved_password_rejected)
                    } else {
                        error.message ?: genericFailure
                    }
                }
            }
        }
    }

    val topButtonSize = 46.dp
    val screenBackground = MaterialTheme.colorScheme.background
    val isDarkTheme = isSystemInDarkTheme()
    val topButtonBackground = if (isDarkTheme) Color(0xFF303030) else Color.White
    val topButtonContentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val topContentPadding =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topButtonSize + 24.dp
    val bottomContentPadding =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = screenBackground,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (computer == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topContentPadding, bottom = bottomContentPadding)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.computer_detail_missing))
                    AppOutlinedButton(onClick = { navController.popBackStack() }) {
                        Text(stringResource(R.string.navigation_back))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = topContentPadding,
                        end = 16.dp,
                        bottom = bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        ComputerOverviewCard(
                            computer = computer,
                            busyAction = busyAction,
                            onRefresh = {
                                launchAction("refresh") {
                                    viewModel.refreshComputer(computer.id)
                                    withContext(Dispatchers.Main) {
                                        viewModel.showSnackbar(genericSuccessMessage(viewModel, R.string.computer_refresh_success))
                                    }
                                }
                            },
                            onEdit = {
                                editForm = computer.toEditFormState()
                                editErrorText = null
                                editDialogVisible = true
                            },
                            onDisconnect = {
                                launchAction("disconnect") {
                                    viewModel.disconnectComputer(computer.id)
                                }
                            },
                            onReplaceHostKey = {
                                launchAction("host-key-probe") {
                                    val result = viewModel.probeComputerReplacementHostKey(computer.id)
                                    withContext(Dispatchers.Main) { replacementHostKey = result }
                                }
                            },
                        )
                    }
                    item {
                        ComputerPermissionSettingsCard(
                            computer = computer,
                            busyAction = busyAction,
                            summary = computerPermissionSummary(computer),
                            onPermissionModeChange = { permissionMode ->
                                if (permissionMode == computer.permissionMode) return@ComputerPermissionSettingsCard
                                if (permissionMode == ComputerPermissionMode.FULL) {
                                    pendingPermissionMode = permissionMode
                                } else {
                                    launchAction("permission-mode") {
                                        viewModel.setComputerPermissionMode(computer.id, permissionMode)
                                    }
                                }
                            },
                        )
                    }
                    if (workspaces.isNotEmpty()) {
                        item {
                            ComputerSectionTitle(
                                title = stringResource(R.string.computer_workspace_section),
                                detail = workspaces.size.toString(),
                            )
                        }
                        items(workspaces, key = { it.id }) { workspace ->
                            ComputerWorkspaceCard(
                                viewModel = viewModel,
                                computer = computer,
                                workspace = workspace,
                                displayName = conversationNamesById[workspace.conversationId]
                                    ?: stringResource(R.string.computer_workspace_item_title),
                                onMessage = viewModel::showSnackbar,
                            )
                        }
                    }
                    item {
                        ComputerMoreSettingsCard(
                            computer = computer,
                            audits = audits,
                            expanded = moreSettingsExpanded,
                            busyAction = busyAction,
                            onExpandedChange = { moreSettingsExpanded = it },
                            onReplaceHostKey = {
                                launchAction("host-key-probe") {
                                    val result = viewModel.probeComputerReplacementHostKey(computer.id)
                                    withContext(Dispatchers.Main) { replacementHostKey = result }
                                }
                            },
                            onDelete = { deleteDialogVisible = true },
                        )
                    }
                }
            }

            // 底部渐隐层只负责让内容自然融入手势区，系统栏背景由页面本身绘制。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomContentPadding)
                    .floatingEdgeGradient(screenBackground, fromTop = false),
            )

            // 与配置页一致的浮动顶栏，内容可在其后方滚动，避免出现整块白色 TopAppBar。
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .floatingEdgeGradient(screenBackground, fromTop = true)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopCircleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = stringResource(R.string.navigation_back),
                    modifier = Modifier,
                    onClick = ::navigateBack,
                )
                Box(
                    modifier = Modifier
                        .height(topButtonSize)
                        .widthIn(max = 220.dp)
                        .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(topButtonBackground)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = computer?.displayName ?: stringResource(R.string.computer_detail_title),
                        color = topButtonContentColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (computer == null) return

    if (editDialogVisible) {
        ComputerAddCard(
            form = editForm,
            isBusy = busyAction != null,
            progressText = editProgressText,
            progressDetailText = null,
            errorText = editErrorText,
            onFormChange = { editForm = it; editErrorText = null },
            onSubmit = ::startEditHostKeyProbe,
            onDismiss = ::closeEditDialog,
            title = stringResource(R.string.computer_edit_title),
            submitLabel = stringResource(R.string.computer_edit_save),
            keepCredentialHint = computer.credentialState != ComputerCredentialState.MISSING,
        )
    }

    ComputerHostKeyDialog(
        hostKey = editHostKey,
        onConfirm = ::confirmEditHostKey,
        onDismiss = {
            editHostKey = null
            editPrepared?.clear()
            editPrepared = null
        },
    )

    ComputerReplacementHostKeyDialog(
        computer = computer,
        replacement = replacementHostKey,
        isBusy = busyAction == "host-key-confirm",
        onDismiss = { if (busyAction == null) replacementHostKey = null },
        onConfirm = { replacement ->
            launchAction("host-key-confirm") {
                viewModel.confirmComputerReplacementHostKey(computerId, replacement)
                withContext(Dispatchers.Main) { replacementHostKey = null }
            }
        },
    )

    ComputerDeleteDialog(
        computer = computer.takeIf { deleteDialogVisible },
        activeTaskCount = activeTaskCount,
        isBusy = busyAction == "delete",
        onDismiss = { if (busyAction == null) deleteDialogVisible = false },
        onDelete = {
            launchAction("delete") {
                val result = viewModel.deleteComputer(computerId)
                withContext(Dispatchers.Main) {
                    val message = if (result.remoteKeyRemoved) {
                        R.string.computer_delete_success
                    } else {
                        R.string.computer_delete_key_warning
                    }
                    viewModel.showSnackbar(genericSuccessMessage(viewModel, message))
                    navController.popBackStack()
                }
            }
        },
    )

    ComputerFullApprovalWarningDialog(
        visible = pendingPermissionMode == ComputerPermissionMode.FULL,
        isBusy = busyAction == "permission-mode",
        onDismiss = { if (busyAction == null) pendingPermissionMode = null },
        onConfirm = {
            launchAction(
                action = "permission-mode",
                onCompletion = { pendingPermissionMode = null },
            ) {
                viewModel.setComputerPermissionMode(computer.id, ComputerPermissionMode.FULL)
            }
        },
    )
}

/** AppViewModel 持有 Application，可安全读取当前语言资源。 */
private fun genericSuccessMessage(viewModel: AppViewModel, resourceId: Int): String =
    viewModel.getApplication<android.app.Application>().getString(resourceId)

/**
 * 服务器顶部总览与连接操作卡片。
 * 将状态徽章、连接地址、硬件资源指标以及检查连接/编辑信息/断开连接统一收拢在同一卡片内，
 * 避免连接状态在顶部而连接动作散落在底部的割裂感。
 */
@Composable
private fun ComputerOverviewCard(
    computer: Computer,
    busyAction: String?,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onReplaceHostKey: () -> Unit,
) {
    val isRefreshing = busyAction == "refresh"
    val isDisconnecting = busyAction == "disconnect"
    val isCheckingStatus = isRefreshing ||
        computer.status == ComputerStatus.PROBING ||
        computer.status == ComputerStatus.PROVISIONING ||
        computer.status == ComputerStatus.VERIFYING

    ComputerSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_gpt_terminal),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${computer.username}@${computer.host}:${computer.port}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        when (computer.credentialState) {
                            ComputerCredentialState.DEDICATED_KEY -> R.string.computer_credential_dedicated
                            ComputerCredentialState.ORIGINAL_ENCRYPTED -> R.string.computer_credential_original
                            ComputerCredentialState.MISSING -> R.string.computer_credential_missing
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ComputerStatusBadge(
                computer = computer,
                isChecking = isCheckingStatus,
            )
        }

        computer.capabilities?.let { capabilities ->
            val resourceItems = buildList {
                capabilities.cpuCount?.let { add(stringResource(R.string.computer_resource_cpu_short, it)) }
                capabilities.memoryBytes?.let { add(stringResource(R.string.computer_resource_memory_short, formatComputerBytes(it))) }
                capabilities.diskAvailableBytes?.let { add(stringResource(R.string.computer_resource_disk_short, formatComputerBytes(it))) }
            }
            if (resourceItems.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    resourceItems.forEach { label ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }

        if (computer.lastErrorCode != null || computer.status == ComputerStatus.HOST_KEY_CHANGED) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    computer.lastErrorCode?.let { errorCode ->
                        Text(
                            text = stringResource(R.string.computer_detail_error_short, errorCode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (computer.status == ComputerStatus.HOST_KEY_CHANGED) {
                        OutlinedButton(
                            onClick = onReplaceHostKey,
                            enabled = busyAction == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            shape = AppDialogButtonShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        ) {
                            Text(
                                text = stringResource(R.string.computer_action_host_key),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onRefresh,
                enabled = busyAction == null,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = AppDialogButtonShape,
            ) {
                AppDialogActionContent(
                    label = stringResource(R.string.computer_action_reconnect_probe),
                    isLoading = isRefreshing,
                    loadingContentDescription = stringResource(R.string.computer_action_working),
                )
            }
            OutlinedButton(
                onClick = onEdit,
                enabled = busyAction == null,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = AppDialogButtonShape,
            ) {
                Text(stringResource(R.string.computer_action_edit))
            }
        }

        if (computer.status == ComputerStatus.READY || isDisconnecting) {
            OutlinedButton(
                onClick = onDisconnect,
                enabled = busyAction == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(44.dp),
                shape = AppDialogButtonShape,
            ) {
                AppDialogActionContent(
                    label = stringResource(R.string.computer_action_disconnect),
                    isLoading = isDisconnecting,
                    loadingContentDescription = stringResource(R.string.computer_action_working),
                )
            }
        }
    }
}

/**
 * 语义化连接状态胶囊：
 * - 已就绪：绿色圆点 + 柔和底色
 * - 已断开：中性灰圆点，不再误标为错误红
 * - 检查中：旋转加载指示器
 * - 离线/异常：错误红圆点与警示底色
 */
@Composable
private fun ComputerStatusBadge(
    computer: Computer,
    isChecking: Boolean,
) {
    val isErrorState = computer.status == ComputerStatus.OFFLINE ||
        computer.status == ComputerStatus.ERROR ||
        computer.status == ComputerStatus.ACTION_REQUIRED ||
        computer.status == ComputerStatus.HOST_KEY_CHANGED ||
        computer.status == ComputerStatus.CONFIGURATION_REQUIRED
    val dotColor = when {
        computer.status == ComputerStatus.READY -> Color(0xFF2EAF6D)
        isErrorState -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val containerColor = when {
        computer.status == ComputerStatus.READY -> Color(0xFF2EAF6D).copy(alpha = 0.12f)
        isErrorState -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    val textColor = when {
        computer.status == ComputerStatus.READY -> Color(0xFF2EAF6D)
        isErrorState -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                text = stringResource(computerStatusLabelRes(computer)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

@Composable
internal fun ComputerPermissionSettingsCard(
    computer: Computer,
    busyAction: String?,
    summary: String,
    onPermissionModeChange: (ComputerPermissionMode) -> Unit,
) {
    ComputerSectionCard {
        Text(
            text = stringResource(R.string.computer_permission_mode_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.computer_permission_mode_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        ComputerPermissionModeSelector(
            selected = computer.permissionMode,
            enabled = busyAction == null,
            onSelect = onPermissionModeChange,
        )
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (computer.username == "root") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ComputerMoreSettingsCard(
    computer: Computer,
    audits: List<ComputerAuditEvent>,
    expanded: Boolean,
    busyAction: String?,
    onExpandedChange: (Boolean) -> Unit,
    onReplaceHostKey: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Surface(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.computer_more_settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.computer_more_settings_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busyAction != null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_gpt_chevron_right),
                            contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(if (expanded) 90f else 0f),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                if (expanded) {
                    Column {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ComputerSettingsGroup(stringResource(R.string.computer_settings_security)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ComputerDetailValue(
                                            stringResource(R.string.computer_detail_last_connected),
                                            formatComputerDate(computer.lastConnectedAt),
                                        )
                                        ComputerDetailValue(
                                            stringResource(R.string.computer_detail_credential),
                                            stringResource(
                                                when (computer.credentialState) {
                                                    ComputerCredentialState.DEDICATED_KEY -> R.string.computer_credential_dedicated
                                                    ComputerCredentialState.ORIGINAL_ENCRYPTED -> R.string.computer_credential_original
                                                    ComputerCredentialState.MISSING -> R.string.computer_credential_missing
                                                },
                                            ),
                                        )
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = stringResource(R.string.computer_detail_host_key),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(0.38f),
                                            )
                                            Text(
                                                text = computer.hostKeyFingerprint ?: "—",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.weight(0.62f),
                                            )
                                        }
                                    }
                                }
                                if (computer.status == ComputerStatus.HOST_KEY_CHANGED) {
                                    OutlinedButton(
                                        onClick = onReplaceHostKey,
                                        enabled = busyAction == null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp),
                                        shape = AppDialogButtonShape,
                                    ) {
                                        Text(stringResource(R.string.computer_action_host_key))
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.computer_model_data_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            HorizontalDivider()
                            ComputerSettingsGroup(stringResource(R.string.computer_audit_title)) {
                                ComputerAuditContent(audits)
                            }

                            HorizontalDivider()
                            OutlinedButton(
                                onClick = onDelete,
                                enabled = busyAction == null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = AppDialogButtonShape,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.computer_action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputerSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
internal fun ComputerPermissionModeSelector(
    selected: ComputerPermissionMode,
    enabled: Boolean,
    onSelect: (ComputerPermissionMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ComputerPermissionMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val shape = RoundedCornerShape(18.dp)
            Surface(
                onClick = { onSelect(mode) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                } else {
                    Color.Transparent
                },
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    // 选中态加深边框与浅底色，未选中保持清晰可见的细边框。
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (isSelected) 0.65f else 0.18f,
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(mode.titleResource()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(mode.bodyResource()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isSelected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = stringResource(R.string.state_selected),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun ComputerPermissionMode.titleResource(): Int = when (this) {
    ComputerPermissionMode.MANUAL -> R.string.computer_permission_manual
    ComputerPermissionMode.SMART -> R.string.computer_permission_smart
    ComputerPermissionMode.FULL -> R.string.computer_permission_full
}

private fun ComputerPermissionMode.bodyResource(): Int = when (this) {
    ComputerPermissionMode.MANUAL -> R.string.computer_permission_manual_body
    ComputerPermissionMode.SMART -> R.string.computer_permission_smart_body
    ComputerPermissionMode.FULL -> R.string.computer_permission_full_body
}

/**
 * 权限选项卡片自身已经展示对应说明，仅在 root + 每次批准时需要额外提示高权限风险。
 */
@Composable
private fun computerPermissionSummary(computer: Computer): String =
    if (computer.permissionMode == ComputerPermissionMode.MANUAL && computer.username == "root") {
        stringResource(R.string.computer_permission_direct_root)
    } else {
        ""
    }

@Composable
internal fun ComputerSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ComputerSectionTitle(title: String, detail: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComputerAuditContent(audits: List<ComputerAuditEvent>) {
    var showAllAudits by remember { mutableStateOf(false) }
    val previewLimit = 8
    val visibleAudits = if (showAllAudits) audits.take(50) else audits.take(previewLimit)

    Column {
        Text(
            text = stringResource(R.string.computer_audit_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        if (audits.isEmpty()) {
            Text(
                text = stringResource(R.string.computer_audit_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    visibleAudits.forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(auditEventLabel(event.eventType), style = MaterialTheme.typography.labelLarge)
                                auditSafeSummary(event)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(auditOutcomeLabel(event.outcome), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    formatComputerDate(event.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (audits.size > previewLimit) {
                AppOutlinedButton(
                    onClick = { showAllAudits = !showAllAudits },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    shape = AppDialogButtonShape,
                ) {
                    Text(
                        text = stringResource(
                            if (showAllAudits) R.string.action_collapse else R.string.action_expand,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun auditEventLabel(eventType: String): String = stringResource(
    when (eventType) {
        "COMPUTER_ADDED" -> R.string.computer_audit_added
        "COMPUTER_UPDATED" -> R.string.computer_audit_updated
        "DEDICATED_KEY_INSTALLED" -> R.string.computer_audit_key_installed
        "CONTAINER_PROVISION" -> R.string.computer_audit_container
        "CREDENTIAL_REPLACED" -> R.string.computer_audit_credential
        "HOST_KEY_REPLACED" -> R.string.computer_audit_host_key
        "HOST_KEY_AUTO_ACCEPTED" -> R.string.computer_audit_host_key_auto
        "DEDICATED_KEY_FALLBACK" -> R.string.computer_audit_dedicated_key_fallback
        "DISCONNECT" -> R.string.computer_audit_disconnect
        "PRIVATE_NETWORK" -> R.string.computer_audit_network
        "PERMISSION_MODE" -> R.string.computer_audit_permission_mode
        "WORKSPACE_SECRET_SAVED" -> R.string.computer_audit_secret_saved
        "WORKSPACE_SECRET_DELETED" -> R.string.computer_audit_secret_deleted
        "PRIVATE_PREVIEW_OPENED" -> R.string.computer_audit_private_preview
        "PUBLIC_PREVIEW_OPENED" -> R.string.computer_audit_public_preview
        "PREVIEW_EXPIRED" -> R.string.computer_audit_preview_expired
        "PREVIEW_STOPPED" -> R.string.computer_audit_preview_stopped
        "PREVIEW_REVOKED" -> R.string.computer_audit_preview_revoked
        "WORKSPACE_DELETED" -> R.string.computer_audit_workspace_deleted
        else -> R.string.computer_audit_other
    },
)

@Composable
private fun auditOutcomeLabel(outcome: String): String {
    val resource = when (outcome) {
        "SUCCESS" -> R.string.computer_audit_outcome_success
        "FAILED" -> R.string.computer_audit_outcome_failed
        "CONFIRMED" -> R.string.computer_audit_outcome_confirmed
        "FALLBACK" -> R.string.computer_audit_outcome_fallback
        "AUTO_ACCEPTED" -> R.string.computer_audit_outcome_auto_accepted
        else -> null
    }
    return resource?.let { stringResource(it) } ?: outcome
}

/**
 * Room 只保存稳定代码或非敏感动态值，界面在展示时按当前语言补全说明。
 * 同时兼容早期版本已经落库的中文安全摘要。
 */
@Composable
private fun auditSafeSummary(event: ComputerAuditEvent): String? {
    val summary = event.safeSummary ?: return null
    return when {
        event.eventType == "WORKSPACE_SECRET_SAVED" || event.eventType == "WORKSPACE_SECRET_DELETED" ->
            stringResource(R.string.computer_audit_summary_secret, summary)
        event.eventType == "PRIVATE_PREVIEW_OPENED" || event.eventType == "PUBLIC_PREVIEW_OPENED" ->
            stringResource(R.string.computer_audit_summary_port, summary.removePrefix("端口 "))
        event.eventType == "PRIVATE_NETWORK" && summary in setOf("ALLOWED", "已允许") ->
            stringResource(R.string.computer_audit_summary_private_allowed)
        event.eventType == "PRIVATE_NETWORK" && summary in setOf("BLOCKED", "已阻止") ->
            stringResource(R.string.computer_audit_summary_private_blocked)
        event.eventType == "PERMISSION_MODE" && summary == ComputerPermissionMode.MANUAL.name ->
            stringResource(R.string.computer_permission_manual)
        event.eventType == "PERMISSION_MODE" && summary == ComputerPermissionMode.SMART.name ->
            stringResource(R.string.computer_permission_smart)
        event.eventType == "PERMISSION_MODE" && summary == ComputerPermissionMode.FULL.name ->
            stringResource(R.string.computer_permission_full)
        summary == "ORIGINAL_CREDENTIAL_RETAINED" || summary == "保留本地加密的原始凭据" ->
            stringResource(R.string.computer_audit_summary_credential_retained)
        summary == "REMOTE_CLEANUP_PENDING" || summary == "远端清理待重试" ->
            stringResource(R.string.computer_audit_summary_remote_cleanup)
        summary == "SSH 登录和本地能力探测成功" ->
            stringResource(R.string.computer_audit_summary_probe_ready)
        summary == "Container 环境配置完成" ->
            stringResource(R.string.computer_audit_summary_container_ready)
        else -> summary
    }
}

@Composable
internal fun ComputerFullApprovalWarningDialog(
    visible: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = { Text(stringResource(R.string.computer_permission_full_warning_title)) },
        text = {
            Text(
                text = stringResource(R.string.computer_permission_full_warning_body),
                color = MaterialTheme.colorScheme.error,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isBusy,
                shape = AppDialogButtonShape,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = appDialogContentColor(),
                    contentColor = appDialogContainerColor(),
                ),
            ) {
                AppDialogActionContent(
                    label = stringResource(R.string.computer_permission_full_warning_confirm),
                    isLoading = isBusy,
                    loadingContentDescription = stringResource(R.string.computer_action_working),
                )
            }
        },
        dismissButton = {
            AppOutlinedButton(
                onClick = onDismiss,
                enabled = !isBusy,
                shape = AppDialogButtonShape,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = appDialogContentColor(),
                    disabledContentColor = appDialogContentColor().copy(alpha = 0.38f),
                ),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ComputerReplacementHostKeyDialog(
    computer: Computer,
    replacement: HostKeyProbeResult?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (HostKeyProbeResult) -> Unit,
) {
    if (replacement == null) return
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = { Text(stringResource(R.string.computer_host_key_changed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.computer_host_key_changed_body),
                    color = MaterialTheme.colorScheme.error,
                )
                ComputerDetailValue(
                    stringResource(R.string.computer_host_key_old),
                    computer.hostKeyFingerprint ?: "—",
                )
                ComputerDetailValue(stringResource(R.string.computer_host_key_new), replacement.fingerprint)
                ComputerDetailValue(stringResource(R.string.computer_detail_host_key_algorithm), replacement.algorithm)
                ComputerDetailValue(stringResource(R.string.computer_host_key_address_label), replacement.resolvedAddress)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(replacement) },
                enabled = !isBusy,
                modifier = Modifier.height(48.dp),
                shape = AppDialogButtonShape,
            ) {
                AppDialogActionContent(
                    label = stringResource(R.string.computer_host_key_accept_new),
                    isLoading = isBusy,
                    loadingContentDescription = stringResource(R.string.computer_action_working),
                )
            }
        },
        dismissButton = {
            AppOutlinedButton(
                onClick = onDismiss,
                enabled = !isBusy,
                modifier = Modifier.height(48.dp),
                shape = AppDialogButtonShape,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ComputerDeleteDialog(
    computer: Computer?,
    activeTaskCount: Int = 0,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    if (computer == null) return
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = { Text(stringResource(R.string.computer_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.computer_delete_body))
                if (activeTaskCount > 0) {
                    Text(
                        text = stringResource(R.string.computer_delete_active_tasks_warning, activeTaskCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.computer_delete_remote_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                enabled = !isBusy,
                modifier = Modifier.height(48.dp),
                shape = AppDialogButtonShape,
            ) {
                AppDialogActionContent(
                    label = stringResource(R.string.computer_action_delete),
                    isLoading = isBusy,
                    loadingContentDescription = stringResource(R.string.computer_action_working),
                )
            }
        },
        dismissButton = {
            AppOutlinedButton(
                onClick = onDismiss,
                enabled = !isBusy,
                modifier = Modifier.height(48.dp),
                shape = AppDialogButtonShape,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
