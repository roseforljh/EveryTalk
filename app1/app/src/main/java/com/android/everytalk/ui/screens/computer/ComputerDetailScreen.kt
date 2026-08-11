package com.android.everytalk.ui.screens.computer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuditEvent
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerCredential
import com.android.everytalk.data.computer.ComputerCredentialState
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.confirmComputerReplacementHostKey
import com.android.everytalk.statecontroller.deleteComputer
import com.android.everytalk.statecontroller.disconnectComputer
import com.android.everytalk.statecontroller.observeComputerAuditEvents
import com.android.everytalk.statecontroller.observeComputerWorkspaces
import com.android.everytalk.statecontroller.probeComputerReplacementHostKey
import com.android.everytalk.statecontroller.provisionComputerContainer
import com.android.everytalk.statecontroller.refreshComputer
import com.android.everytalk.statecontroller.replaceComputerCredential
import com.android.everytalk.statecontroller.setComputerPrivateNetworkAllowed
import com.android.everytalk.statecontroller.showSnackbar
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatAgentColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.computerStatusLabelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerDetailScreen(
    viewModel: AppViewModel,
    navController: NavController,
    computerId: String,
    modifier: Modifier = Modifier,
) {
    val computers by viewModel.computers.collectAsState()
    val computer = computers.firstOrNull { it.id == computerId }
    val workspacesFlow = remember(computerId) { viewModel.observeComputerWorkspaces(computerId) }
    val auditFlow = remember(computerId) { viewModel.observeComputerAuditEvents(computerId) }
    val workspaces by workspacesFlow.collectAsState(initial = emptyList())
    val audits by auditFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var busyAction by remember { mutableStateOf<String?>(null) }
    var credentialDialogVisible by remember { mutableStateOf(false) }
    var repairDialogVisible by remember { mutableStateOf(false) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var replacementHostKey by remember { mutableStateOf<HostKeyProbeResult?>(null) }
    val genericFailure = stringResource(R.string.computer_action_failed)

    fun launchAction(action: String, block: suspend () -> Unit) {
        if (busyAction != null) return
        busyAction = action
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: Throwable) {
                viewModel.showSnackbar(error.message ?: genericFailure)
            } finally {
                busyAction = null
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = computer?.displayName ?: stringResource(R.string.computer_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigation_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (computer == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.computer_detail_missing))
                TextButton(onClick = { navController.popBackStack() }) {
                    Text(stringResource(R.string.navigation_back))
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ComputerOverviewCard(computer)
            }
            item {
                ComputerSecurityCard(computer)
            }
            item {
                ComputerActionsCard(
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
                    onRepair = { repairDialogVisible = true },
                    onReplaceCredential = { credentialDialogVisible = true },
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
                    onDisconnect = {
                        launchAction("disconnect") {
                            viewModel.disconnectComputer(computer.id)
                        }
                    },
                    onDelete = { deleteDialogVisible = true },
                )
            }
            item {
                ComputerSectionTitle(
                    title = stringResource(R.string.computer_workspace_section),
                    body = stringResource(R.string.computer_workspace_section_body),
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
                        onMessage = viewModel::showSnackbar,
                    )
                }
            }
            item {
                ComputerAuditCard(audits)
            }
        }
    }

    if (computer == null) return

    ComputerCredentialReplaceDialog(
        visible = credentialDialogVisible,
        isBusy = busyAction == "credential",
        onDismiss = { if (busyAction == null) credentialDialogVisible = false },
        onSave = { credential ->
            launchAction("credential") {
                viewModel.replaceComputerCredential(computerId, credential)
                withContext(Dispatchers.Main) { credentialDialogVisible = false }
            }
        },
    )

    ComputerContainerRepairDialog(
        visible = repairDialogVisible,
        isBusy = busyAction == "repair",
        onDismiss = { if (busyAction == null) repairDialogVisible = false },
        onRepair = { sudoPassword ->
            launchAction("repair") {
                val password = sudoPassword.takeIf(String::isNotEmpty)?.toCharArray()
                try {
                    viewModel.provisionComputerContainer(computerId, password)
                } finally {
                    password?.fill('\u0000')
                }
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
                tint = if (computer.status == ComputerStatus.READY) ChatAgentColor
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(computer.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(computerStatusLabelRes(computer.status)),
                    color = if (computer.status == ComputerStatus.READY) ChatAgentColor
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        ComputerDetailValue(stringResource(R.string.computer_detail_address), "${computer.host}:${computer.port}")
        ComputerDetailValue(stringResource(R.string.computer_detail_username), computer.username)
        ComputerDetailValue(
            stringResource(R.string.computer_field_mode),
            stringResource(
                if (computer.runMode == ComputerRunMode.CONTAINER) R.string.computer_mode_container
                else R.string.computer_mode_direct,
            ),
        )
        ComputerDetailValue(
            stringResource(R.string.computer_detail_last_connected),
            formatComputerDate(computer.lastConnectedAt),
        )
        computer.lastErrorCode?.let {
            ComputerDetailValue(stringResource(R.string.computer_detail_last_error), it)
        }

        computer.capabilities?.let { capabilities ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.computer_detail_resources), style = MaterialTheme.typography.titleSmall)
            ComputerDetailValue(
                stringResource(R.string.computer_detail_system),
                listOf(capabilities.osId, capabilities.osVersion, capabilities.architecture)
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                    .ifBlank { "—" },
            )
            ComputerDetailValue(
                stringResource(R.string.computer_detail_cpu),
                capabilities.cpuCount?.toString() ?: "—",
            )
            ComputerDetailValue(
                stringResource(R.string.computer_detail_memory),
                capabilities.memoryBytes?.let(::formatComputerBytes) ?: "—",
            )
            ComputerDetailValue(
                stringResource(R.string.computer_detail_disk),
                capabilities.diskAvailableBytes?.let(::formatComputerBytes) ?: "—",
            )
            ComputerDetailValue(
                stringResource(R.string.computer_detail_load),
                capabilities.loadAverage ?: "—",
            )
        }
    }
}

@Composable
private fun ComputerSecurityCard(computer: Computer) {
    ComputerSectionCard {
        Text(stringResource(R.string.computer_detail_security), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                computer.runMode == ComputerRunMode.DIRECT && computer.username == "root" -> {
                    stringResource(R.string.computer_permission_direct_root)
                }
                computer.runMode == ComputerRunMode.DIRECT -> stringResource(R.string.computer_permission_direct)
                else -> stringResource(R.string.computer_permission_container)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (computer.runMode == ComputerRunMode.DIRECT && computer.username == "root") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(10.dp))
        ComputerDetailValue(
            stringResource(R.string.computer_detail_host_key),
            computer.hostKeyFingerprint ?: "—",
        )
        ComputerDetailValue(
            stringResource(R.string.computer_detail_host_key_algorithm),
            computer.hostKeyAlgorithm ?: "—",
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
        Text(
            text = stringResource(R.string.computer_model_data_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ComputerActionsCard(
    computer: Computer,
    busyAction: String?,
    onRefresh: () -> Unit,
    onRepair: () -> Unit,
    onReplaceCredential: () -> Unit,
    onReplaceHostKey: () -> Unit,
    onNetworkChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    ComputerSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.computer_detail_actions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (busyAction != null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = busyAction == null) {
                Text(stringResource(R.string.computer_action_reconnect_probe))
            }
            if (computer.runMode == ComputerRunMode.CONTAINER) {
                OutlinedButton(onClick = onRepair, enabled = busyAction == null) {
                    Text(stringResource(R.string.computer_action_repair))
                }
            }
            OutlinedButton(onClick = onReplaceCredential, enabled = busyAction == null) {
                Text(stringResource(R.string.computer_action_credential))
            }
            if (computer.status == ComputerStatus.HOST_KEY_CHANGED) {
                OutlinedButton(onClick = onReplaceHostKey, enabled = busyAction == null) {
                    Text(stringResource(R.string.computer_action_host_key))
                }
            }
            OutlinedButton(onClick = onDisconnect, enabled = busyAction == null) {
                Text(stringResource(R.string.computer_action_disconnect))
            }
            TextButton(onClick = onDelete, enabled = busyAction == null) {
                Text(
                    text = stringResource(R.string.computer_action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (computer.runMode == ComputerRunMode.CONTAINER) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
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
private fun ComputerSectionTitle(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun ComputerAuditCard(audits: List<ComputerAuditEvent>) {
    ComputerSectionCard {
        Text(stringResource(R.string.computer_audit_title), style = MaterialTheme.typography.titleMedium)
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
                        event.safeSummary?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(event.outcome, style = MaterialTheme.typography.labelSmall)
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
        "DEDICATED_KEY_INSTALLED" -> R.string.computer_audit_key_installed
        "CONTAINER_PROVISION" -> R.string.computer_audit_container
        "CREDENTIAL_REPLACED" -> R.string.computer_audit_credential
        "HOST_KEY_REPLACED" -> R.string.computer_audit_host_key
        "DISCONNECT" -> R.string.computer_audit_disconnect
        "PRIVATE_NETWORK" -> R.string.computer_audit_network
        "WORKSPACE_SECRET_SAVED" -> R.string.computer_audit_secret_saved
        "WORKSPACE_SECRET_DELETED" -> R.string.computer_audit_secret_deleted
        "PRIVATE_PREVIEW_OPENED" -> R.string.computer_audit_private_preview
        "PUBLIC_PREVIEW_OPENED" -> R.string.computer_audit_public_preview
        "PREVIEW_EXPIRED" -> R.string.computer_audit_preview_expired
        "PREVIEW_REVOKED" -> R.string.computer_audit_preview_revoked
        "WORKSPACE_DELETED" -> R.string.computer_audit_workspace_deleted
        else -> R.string.computer_audit_other
    },
)

@Composable
private fun ComputerCredentialReplaceDialog(
    visible: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ComputerCredential) -> Unit,
) {
    if (!visible) return
    var authKind by remember { mutableStateOf(ComputerAuthKind.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val invalidText = stringResource(R.string.computer_credential_input_required)
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        title = { Text(stringResource(R.string.computer_credential_replace_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.computer_credential_replace_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authKind == ComputerAuthKind.PASSWORD,
                        onClick = { authKind = ComputerAuthKind.PASSWORD; errorText = null },
                        label = { Text(stringResource(R.string.computer_auth_password)) },
                        enabled = !isBusy,
                    )
                    FilterChip(
                        selected = authKind == ComputerAuthKind.PRIVATE_KEY,
                        onClick = { authKind = ComputerAuthKind.PRIVATE_KEY; errorText = null },
                        label = { Text(stringResource(R.string.computer_auth_private_key)) },
                        enabled = !isBusy,
                    )
                }
                if (authKind == ComputerAuthKind.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorText = null },
                        label = { Text(stringResource(R.string.computer_field_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        enabled = !isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it; errorText = null },
                        label = { Text(stringResource(R.string.computer_field_private_key)) },
                        minLines = 4,
                        maxLines = 8,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.computer_field_private_key_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        enabled = !isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !isBusy,
                onClick = {
                    val credential = when (authKind) {
                        ComputerAuthKind.PASSWORD -> password.takeIf(String::isNotEmpty)?.let {
                            ComputerCredential.Password(it.toCharArray())
                        }
                        ComputerAuthKind.PRIVATE_KEY -> privateKey.takeIf(String::isNotBlank)?.let {
                            ComputerCredential.PrivateKey(
                                privateKey = it.toCharArray(),
                                passphrase = passphrase.takeIf(String::isNotEmpty)?.toCharArray(),
                            )
                        }
                    }
                    if (credential == null) errorText = invalidText else onSave(credential)
                },
            ) {
                Text(stringResource(R.string.action_save))
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
private fun ComputerContainerRepairDialog(
    visible: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onRepair: (String) -> Unit,
) {
    if (!visible) return
    var sudoPassword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        title = { Text(stringResource(R.string.computer_repair_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.computer_repair_body))
                OutlinedTextField(
                    value = sudoPassword,
                    onValueChange = { sudoPassword = it },
                    label = { Text(stringResource(R.string.computer_field_sudo_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onRepair(sudoPassword) }, enabled = !isBusy) {
                Text(stringResource(R.string.computer_action_repair))
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
