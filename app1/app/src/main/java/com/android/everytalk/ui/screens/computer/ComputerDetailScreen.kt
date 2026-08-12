package com.android.everytalk.ui.screens.computer

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuditEvent
import com.android.everytalk.data.computer.ComputerCredentialState
import com.android.everytalk.data.computer.ComputerDiagnostics
import com.android.everytalk.data.computer.ComputerFailureStage
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerSetupStage
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.cancelComputerOperation
import com.android.everytalk.statecontroller.confirmComputerReplacementHostKey
import com.android.everytalk.statecontroller.deleteComputer
import com.android.everytalk.statecontroller.disconnectComputer
import com.android.everytalk.statecontroller.getConversationFullText
import com.android.everytalk.statecontroller.observeComputerAuditEvents
import com.android.everytalk.statecontroller.observeComputerWorkspaces
import com.android.everytalk.statecontroller.probeUpdatedComputerHostKey
import com.android.everytalk.statecontroller.probeComputerReplacementHostKey
import com.android.everytalk.statecontroller.provisionComputerContainer
import com.android.everytalk.statecontroller.refreshComputer
import com.android.everytalk.statecontroller.setComputerPrivateNetworkAllowed
import com.android.everytalk.statecontroller.setComputerPermissionMode
import com.android.everytalk.statecontroller.showSnackbar
import com.android.everytalk.statecontroller.updateComputer
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.components.EveryTalkTimedLoadingStatus
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
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
    val context = LocalContext.current
    val workspacesFlow = remember(computerId) { viewModel.observeComputerWorkspaces(computerId) }
    val auditFlow = remember(computerId) { viewModel.observeComputerAuditEvents(computerId) }
    val workspaces by workspacesFlow.collectAsState(initial = emptyList())
    val audits by auditFlow.collectAsState(initial = emptyList())
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
    var repairDialogVisible by remember { mutableStateOf(false) }
    var repairSetupStage by remember { mutableStateOf<ComputerSetupStage?>(null) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var replacementHostKey by remember { mutableStateOf<HostKeyProbeResult?>(null) }
    var moreSettingsExpanded by remember(computer?.id) { mutableStateOf(false) }
    var pendingPermissionMode by remember(computer?.id) { mutableStateOf<ComputerPermissionMode?>(null) }
    val genericFailure = stringResource(R.string.computer_action_failed)
    val latestEditPrepared by rememberUpdatedState(editPrepared)
    val latestActionJob by rememberUpdatedState(actionJob)
    val latestBusyAction by rememberUpdatedState(busyAction)
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
            if (latestBusyAction == "repair") viewModel.cancelComputerOperation(computerId)
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
        if (busyAction == "repair") {
            repairSetupStage = null
            viewModel.cancelComputerOperation(computerId)
        }
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
        repairDialogVisible = false
    }

    fun navigateBack() {
        if (busyAction != null) cancelActiveAction()
        navController.popBackStack()
    }

    BackHandler(onBack = ::navigateBack)

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
                    editErrorText = error.message ?: genericFailure
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
                    TextButton(onClick = { navController.popBackStack() }) {
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
                        ComputerOverviewCard(computer)
                    }
                    item {
                        ComputerAgentUseCard(computer)
                    }
                    item {
                        ComputerSectionTitle(
                            title = stringResource(R.string.computer_workspace_section),
                        )
                    }
                    if (workspaces.isEmpty()) {
                        item {
                            ComputerEmptySection(stringResource(R.string.computer_workspace_empty))
                        }
                    } else {
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
                            onRefresh = {
                                launchAction("refresh") {
                                    viewModel.refreshComputer(computer.id)
                                    withContext(Dispatchers.Main) {
                                        viewModel.showSnackbar(genericSuccessMessage(viewModel, R.string.computer_refresh_success))
                                    }
                                }
                            },
                            onRepair = {
                                repairSetupStage = null
                                repairDialogVisible = true
                            },
                            onEdit = {
                                editForm = computer.toEditFormState()
                                editErrorText = null
                                editDialogVisible = true
                            },
                            onReplaceHostKey = {
                                launchAction("host-key-probe") {
                                    val result = viewModel.probeComputerReplacementHostKey(computer.id)
                                    withContext(Dispatchers.Main) { replacementHostKey = result }
                                }
                            },
                            onNetworkChange = { allowed ->
                                launchAction("network") {
                                    viewModel.setComputerPrivateNetworkAllowed(computer.id, allowed)
                                }
                            },
                            onPermissionModeChange = { permissionMode ->
                                if (permissionMode == computer.permissionMode) return@ComputerMoreSettingsCard
                                if (permissionMode == ComputerPermissionMode.FULL) {
                                    pendingPermissionMode = permissionMode
                                } else {
                                    launchAction("permission-mode") {
                                        viewModel.setComputerPermissionMode(computer.id, permissionMode)
                                    }
                                }
                            },
                            onDisconnect = {
                                launchAction("disconnect") {
                                    viewModel.disconnectComputer(computer.id)
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
            isBusy = busyAction == "edit-probe" || busyAction == "edit-save",
            progressText = editProgressText,
            progressDetailText = null,
            errorText = editErrorText,
            onFormChange = { editForm = it; editErrorText = null },
            onSubmit = ::startEditHostKeyProbe,
            onDismiss = ::closeEditDialog,
            title = stringResource(R.string.computer_edit_title),
            submitLabel = stringResource(R.string.computer_edit_save),
            keepCredentialHint = true,
            allowBusyDismiss = true,
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

    ComputerContainerRepairDialog(
        visible = repairDialogVisible,
        isBusy = busyAction == "repair",
        setupStage = repairSetupStage,
        onDismiss = {
            if (busyAction == "repair") {
                cancelActiveAction()
            } else {
                repairSetupStage = null
                repairDialogVisible = false
            }
        },
        onRepair = {
            repairSetupStage = ComputerSetupStage.AUTHENTICATING
            launchAction(
                action = "repair",
                onCompletion = { repairSetupStage = null },
            ) {
                viewModel.provisionComputerContainer(
                    computerId,
                    onProgress = { stage ->
                        withContext(Dispatchers.Main) { repairSetupStage = stage }
                    },
                )
                withContext(Dispatchers.Main) { repairDialogVisible = false }
            }
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
        workspacePaths = workspaces.map { it.hostPath },
        isBusy = busyAction == "delete",
        onDismiss = { if (busyAction == null) deleteDialogVisible = false },
        onDelete = { cleanupContainers, deleteFiles ->
            launchAction("delete") {
                val result = viewModel.deleteComputer(computerId, cleanupContainers, deleteFiles)
                withContext(Dispatchers.Main) {
                    val message = when {
                        !result.remoteKeyRemoved -> R.string.computer_delete_key_warning
                        !result.remoteWorkspaceCleanupSucceeded -> R.string.computer_delete_workspace_warning
                        else -> R.string.computer_delete_success
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

@Composable
private fun ComputerOverviewCard(computer: Computer) {
    ComputerSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_gpt_terminal),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(
                    text = stringResource(computerStatusLabelRes(computer.status)),
                    color = if (computer.status == ComputerStatus.READY) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${computer.username}@${computer.host}:${computer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        computer.capabilities?.let { capabilities ->
            val resources = buildList {
                capabilities.cpuCount?.let { add(stringResource(R.string.computer_resource_cpu_short, it)) }
                capabilities.memoryBytes?.let { add(stringResource(R.string.computer_resource_memory_short, formatComputerBytes(it))) }
                capabilities.diskAvailableBytes?.let { add(stringResource(R.string.computer_resource_disk_short, formatComputerBytes(it))) }
            }.joinToString("  ·  ")
            if (resources.isNotBlank()) {
                Text(
                    text = resources,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        computer.lastErrorCode?.let { errorCode ->
            Text(
                text = stringResource(R.string.computer_detail_error_short, errorCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ComputerAgentUseCard(computer: Computer) {
    ComputerSectionCard {
        Text(stringResource(R.string.computer_agent_use_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.computer_agent_use_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ComputerMoreSettingsCard(
    computer: Computer,
    audits: List<ComputerAuditEvent>,
    expanded: Boolean,
    busyAction: String?,
    onExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRepair: () -> Unit,
    onEdit: () -> Unit,
    onReplaceHostKey: () -> Unit,
    onNetworkChange: (Boolean) -> Unit,
    onPermissionModeChange: (ComputerPermissionMode) -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.computer_more_settings), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.computer_more_settings_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busyAction != null && busyAction != "repair") {
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

            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ComputerSettingsGroup(stringResource(R.string.computer_settings_connection)) {
                        ComputerDetailValue(stringResource(R.string.computer_detail_address), "${computer.host}:${computer.port}")
                        ComputerDetailValue(stringResource(R.string.computer_detail_username), computer.username)
                        ComputerDetailValue(
                            stringResource(R.string.computer_detail_last_connected),
                            formatComputerDate(computer.lastConnectedAt),
                        )
                        OutlinedButton(onClick = onEdit, enabled = busyAction == null) {
                            Text(stringResource(R.string.computer_action_edit))
                        }
                    }

                    HorizontalDivider()
                    ComputerSettingsGroup(stringResource(R.string.computer_settings_security)) {
                        Text(
                            text = stringResource(R.string.computer_permission_mode_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ComputerPermissionModeSelector(
                            selected = computer.permissionMode,
                            enabled = busyAction == null,
                            onSelect = onPermissionModeChange,
                        )
                        Text(
                            text = computerPermissionSummary(computer),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (computer.username == "root") {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
                        ComputerDetailValue(
                            stringResource(R.string.computer_detail_host_key),
                            computer.hostKeyFingerprint ?: "—",
                        )
                        if (computer.status == ComputerStatus.HOST_KEY_CHANGED) {
                            OutlinedButton(onClick = onReplaceHostKey, enabled = busyAction == null) {
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
                    ComputerSettingsGroup(stringResource(R.string.computer_settings_maintenance)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Button(onClick = onRefresh, enabled = busyAction == null) {
                                Text(stringResource(R.string.computer_action_reconnect_probe))
                            }
                            if (computer.runMode == ComputerRunMode.CONTAINER) {
                                OutlinedButton(onClick = onRepair, enabled = busyAction == null) {
                                    Text(stringResource(R.string.computer_action_repair))
                                }
                            }
                            OutlinedButton(onClick = onDisconnect, enabled = busyAction == null) {
                                Text(stringResource(R.string.computer_action_disconnect))
                            }
                        }
                        if (computer.runMode == ComputerRunMode.CONTAINER) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.computer_private_network_title))
                                    Text(
                                        text = stringResource(R.string.computer_private_network_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = computer.allowPrivateNetwork,
                                    onCheckedChange = onNetworkChange,
                                    enabled = busyAction == null && computer.status == ComputerStatus.READY,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    ComputerSettingsGroup(stringResource(R.string.computer_audit_title)) {
                        ComputerAuditContent(audits)
                    }

                    HorizontalDivider()
                    TextButton(onClick = onDelete, enabled = busyAction == null) {
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
private fun ComputerPermissionModeSelector(
    selected: ComputerPermissionMode,
    enabled: Boolean,
    onSelect: (ComputerPermissionMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ComputerPermissionMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val shape = RoundedCornerShape(18.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        else Color.Transparent,
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isSelected) 0.18f else 0.08f,
                        ),
                        shape = shape,
                    )
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(mode.titleResource()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
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

@Composable
private fun computerPermissionSummary(computer: Computer): String = when (computer.permissionMode) {
    ComputerPermissionMode.MANUAL -> if (computer.username == "root") {
        stringResource(R.string.computer_permission_direct_root)
    } else {
        stringResource(R.string.computer_permission_container)
    }
    ComputerPermissionMode.SMART -> stringResource(R.string.computer_permission_smart_body)
    ComputerPermissionMode.FULL -> stringResource(R.string.computer_permission_full_body)
}

@Composable
private fun ComputerSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ComputerSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@Composable
private fun ComputerEmptySection(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComputerAuditContent(audits: List<ComputerAuditEvent>) {
    Column {
        Text(
            text = stringResource(R.string.computer_audit_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        if (audits.isEmpty()) {
            Text(stringResource(R.string.computer_audit_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            audits.take(50).forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row {
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
private fun ComputerContainerRepairDialog(
    visible: Boolean,
    isBusy: Boolean,
    setupStage: ComputerSetupStage?,
    onDismiss: () -> Unit,
    onRepair: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = { Text(stringResource(R.string.computer_repair_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.computer_repair_body))
                if (isBusy && setupStage != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EveryTalkTimedLoadingStatus(
                            text = stringResource(setupStage.labelRes()),
                            size = 20.dp,
                            showIndicator = false,
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(setupStage.detailRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onRepair, enabled = !isBusy) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.computer_action_repair),
                        modifier = Modifier.alpha(if (isBusy) 0f else 1f),
                    )
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (isBusy) R.string.action_stop else R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ComputerFullApprovalWarningDialog(
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
                Text(stringResource(R.string.computer_permission_full_warning_confirm))
            }
        },
        dismissButton = {
            TextButton(
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
            Button(onClick = { onConfirm(replacement) }, enabled = !isBusy) {
                Text(stringResource(R.string.computer_host_key_accept_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ComputerDeleteDialog(
    computer: Computer?,
    workspacePaths: List<String>,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onDelete: (cleanupContainers: Boolean, deleteFiles: Boolean) -> Unit,
) {
    if (computer == null) return
    var cleanupContainers by remember(computer.id) { mutableStateOf(false) }
    var deleteFiles by remember(computer.id) { mutableStateOf(false) }
    var confirmFiles by remember(computer.id) { mutableStateOf(false) }
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
                if (computer.runMode == ComputerRunMode.CONTAINER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = cleanupContainers,
                            onCheckedChange = { cleanupContainers = it },
                            enabled = !isBusy,
                        )
                        Text(stringResource(R.string.computer_delete_containers))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = deleteFiles,
                        onCheckedChange = {
                            deleteFiles = it
                            if (!it) confirmFiles = false
                            if (it && computer.runMode == ComputerRunMode.CONTAINER) cleanupContainers = true
                        },
                        enabled = !isBusy,
                    )
                    Text(stringResource(R.string.computer_delete_files))
                }
                if (deleteFiles) {
                    workspacePaths.forEach { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = confirmFiles,
                            onCheckedChange = { confirmFiles = it },
                            enabled = !isBusy,
                        )
                        Text(
                            text = stringResource(R.string.computer_delete_files_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                onClick = { onDelete(cleanupContainers, deleteFiles) },
                enabled = !isBusy && (!deleteFiles || confirmFiles),
            ) {
                Text(stringResource(R.string.computer_action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
