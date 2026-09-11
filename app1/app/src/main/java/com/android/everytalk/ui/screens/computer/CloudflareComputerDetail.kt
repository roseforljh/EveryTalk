package com.android.everytalk.ui.screens.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import android.content.Intent
import android.net.Uri
import com.android.everytalk.data.computer.CloudflareApiAccount
import com.android.everytalk.data.computer.CloudflareOAuthCallbackBus
import com.android.everytalk.data.computer.CloudflareOAuthConfig
import com.android.everytalk.data.computer.CloudflareOAuthLaunchCoordinator
import com.android.everytalk.data.computer.CloudflareSettingsOAuthFlow
import com.android.everytalk.data.computer.CloudflareSettingsOAuthStore
import com.android.everytalk.data.computer.CloudflareTokenExchangeResult
import com.android.everytalk.data.computer.CloudflareComputerManager
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerWorkspace
import com.android.everytalk.data.computer.TemporaryWorkerDeployment
import com.android.everytalk.data.computer.TemporaryWorkerStatus
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.cloudflareComputerDetails
import com.android.everytalk.statecontroller.listCloudflareComputerAccounts
import com.android.everytalk.statecontroller.switchCloudflareComputerAccount
import com.android.everytalk.statecontroller.setComputerPermissionMode
import com.android.everytalk.statecontroller.logoutCloudflareComputer
import com.android.everytalk.statecontroller.deleteLocalCloudflareComputer
import com.android.everytalk.statecontroller.reauthorizeCloudflareComputer
import com.android.everytalk.statecontroller.observeCloudflareDeployments
import com.android.everytalk.statecontroller.observeComputerWorkspaces
import com.android.everytalk.statecontroller.listTemporaryWorkers
import com.android.everytalk.statecontroller.createTemporaryWorkerFromWorkspace
import com.android.everytalk.statecontroller.beginTemporaryWorkerClaim
import com.android.everytalk.statecontroller.completeTemporaryWorkerClaim
import com.android.everytalk.statecontroller.cancelTemporaryWorkerClaim
import com.android.everytalk.statecontroller.temporaryWorkerEnabled
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.collectLatest
import org.koin.java.KoinJavaComponent
import io.ktor.client.HttpClient
import com.android.everytalk.BuildConfig

/** Cloudflare 专用详情，账号选择只能来自可信 API，页面不持有 Token、不调用 SSH 维护操作。 */
@Composable
internal fun CloudflareComputerDetail(
    viewModel: AppViewModel,
    navController: NavController,
    computer: Computer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val oauthBinding = "settings:${computer.id}"
    val scope = rememberCoroutineScope()
    var details by remember(computer.id) { mutableStateOf<CloudflareComputerManager.Details?>(null) }
    var accounts by remember(computer.id) { mutableStateOf<List<CloudflareApiAccount>?>(null) }
    var selectedAccount by remember(computer.id) { mutableStateOf<String?>(null) }
    var pendingAction by remember(computer.id) { mutableStateOf<String?>(null) }
    var pendingPermissionMode by remember(computer.id) { mutableStateOf<ComputerPermissionMode?>(null) }
    var busy by remember(computer.id) { mutableStateOf(false) }
    var error by remember(computer.id) { mutableStateOf<String?>(null) }
    val deploymentFlow = remember(computer.id) { viewModel.observeCloudflareDeployments(computer.id) }
    val deployments by deploymentFlow.collectAsState(initial = emptyList())
    val workspaceFlow = remember(computer.id) { viewModel.observeComputerWorkspaces(computer.id) }
    val workspaces by workspaceFlow.collectAsState(initial = emptyList())
    var temporaryWorkers by remember(computer.id) { mutableStateOf<List<TemporaryWorkerDeployment>>(emptyList()) }
    var selectedTemporaryWorkspace by remember(computer.id) { mutableStateOf<ComputerWorkspace?>(null) }
    var temporaryWorkspaceDialogVisible by remember(computer.id) { mutableStateOf(false) }
    var temporaryBusy by remember(computer.id) { mutableStateOf(false) }
    var temporaryError by remember(computer.id) { mutableStateOf<String?>(null) }
    val oauthFlow = remember(computer.id, viewModel) {
        CloudflareSettingsOAuthFlow(
            CloudflareOAuthConfig(BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID, BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI),
            viewModel.cloudflareOAuthStore, KoinJavaComponent.getKoin().get<HttpClient>(),
        )
    }

    suspend fun reload() {
        details = withContext(Dispatchers.IO) { viewModel.cloudflareComputerDetails(computer.id) }
        val workspaceIds = workspaces.map { it.id }.toSet()
        temporaryWorkers = withContext(Dispatchers.IO) {
            viewModel.listTemporaryWorkers().filter { it.sourceWorkspaceId in workspaceIds }
        }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { temporaryError = "无法打开链接：${it.message ?: "系统没有可用浏览器"}" }
    }

    fun temporaryAction(block: suspend () -> Unit) {
        if (temporaryBusy) return
        temporaryBusy = true
        temporaryError = null
        scope.launch {
            try {
                block()
                temporaryWorkers = withContext(Dispatchers.IO) {
                    val workspaceIds = workspaces.map { it.id }.toSet()
                    viewModel.listTemporaryWorkers().filter { it.sourceWorkspaceId in workspaceIds }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                temporaryError = failure.message ?: "临时 Worker 操作失败，请重试"
            } finally {
                temporaryBusy = false
            }
        }
    }

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Cloudflare 操作失败，请重试" }
            finally { busy = false }
        }
    }

    LaunchedEffect(computer.id, computer.status, workspaces) {
        try { reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取 Cloudflare 配置失败" }
    }
    LaunchedEffect(computer.id) {
        // consume 会更新同一个 StateFlow；collectLatest 会在这里取消尚未完成的重新授权。
        CloudflareOAuthCallbackBus.callbacks.collect { uri ->
            if (uri == null) return@collect
            if (!oauthFlow.ownsCallback(uri, oauthBinding)) return@collect
            CloudflareOAuthCallbackBus.consume(uri)
            perform {
                val result = oauthFlow.consume(uri, oauthBinding)
                try {
                    viewModel.reauthorizeCloudflareComputer(computer.id, CloudflareTokenExchangeResult(
                        result.accessToken.copyOf(), result.refreshToken?.copyOf(), result.scopes, result.expiresInSeconds,
                    ))
                    reload()
                } finally {
                    result.accessToken.fill('\u0000')
                    result.refreshToken?.fill('\u0000')
                }
            }
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        Column(
            Modifier.padding(insets).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = { navController.popBackStack() }) { Text("返回") }
            Text(computer.displayName, style = MaterialTheme.typography.headlineSmall)
            Text("Cloudflare Computer", style = MaterialTheme.typography.labelLarge)
            val authorization = details?.authorization
            val grantedScopeCount = remember(authorization?.grantedScopesJson) {
                runCatching { Json.decodeFromString<Set<String>>(authorization?.grantedScopesJson ?: "[]") }
                    .getOrDefault(emptySet()).size
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CloudflareInfoRow("连接状态", computer.status.name)
                    CloudflareInfoRow("授权", when {
                        authorization == null -> "需要登录"
                        authorization.revoked -> "已退出登录"
                        authorization.expiresAt?.let { it <= System.currentTimeMillis() } == true -> "已过期，需要重新授权"
                        else -> "已授权"
                    })
                    CloudflareInfoRow("登录身份", authorization?.identityDisplayName ?: "暂不可用")
                    details?.config?.accountName?.let { CloudflareInfoRow("Account", it) }
                    if (grantedScopeCount > 0) CloudflareInfoRow("授权范围", "已授予 $grantedScopeCount 项")
                    // 只有真的有部署或探测记录时才占版面，没记录不再铺一行“暂无”。
                    deployments.firstOrNull()?.status?.let { CloudflareInfoRow("最近部署", it) }
                    details?.latestHealth?.let { health ->
                        CloudflareInfoRow(
                            "运行时",
                            "${health.status}，HTTP ${health.httpStatus ?: "—"}，${health.latencyMs ?: "—"} ms",
                        )
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        runCatching { CloudflareOAuthLaunchCoordinator(context, oauthFlow).launch(oauthBinding).getOrThrow() }
                            .onFailure { error = it.message ?: "Cloudflare OAuth 配置错误" }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (authorization == null) "登录 Cloudflare" else "重新授权") }
                OutlinedButton(onClick = { perform { reload() } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("刷新") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        perform {
                            val available = withContext(Dispatchers.IO) { viewModel.listCloudflareComputerAccounts(computer.id) }
                            check(available.isNotEmpty()) { "当前身份没有可用 Account" }
                            selectedAccount = null
                            accounts = available
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("切换 Account") }
                TextButton(onClick = { pendingAction = "logout" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("退出登录") }
            }
            TextButton(
                onClick = { pendingAction = "delete" },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除本地 Computer", color = MaterialTheme.colorScheme.error) }
            ComputerPermissionSettingsCard(
                computer = computer,
                busyAction = if (busy) "permission-mode" else null,
                summary = cloudflarePermissionSummary(computer.permissionMode),
                onPermissionModeChange = { permissionMode ->
                    if (permissionMode == computer.permissionMode) return@ComputerPermissionSettingsCard
                    if (permissionMode == ComputerPermissionMode.FULL) {
                        pendingPermissionMode = permissionMode
                    } else {
                        perform { viewModel.setComputerPermissionMode(computer.id, permissionMode) }
                    }
                },
            )
            if (viewModel.temporaryWorkerEnabled) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Temporary Worker", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "把当前 Cloudflare Workspace 临时部署为可测试 URL；它不创建正式 Computer。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (workspaces.isEmpty()) {
                        Text("当前没有可用的本地 Workspace。先在会话中使用此 Cloudflare Computer 创建 Workspace。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(
                            onClick = {
                                selectedTemporaryWorkspace = null
                                temporaryWorkspaceDialogVisible = true
                                temporaryError = null
                            },
                            enabled = !temporaryBusy,
                        ) { Text("创建临时 Worker") }
                    }
                    temporaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    temporaryWorkers.forEach { deployment ->
                        TemporaryWorkerCard(
                            deployment = deployment,
                            workspace = workspaces.firstOrNull { it.id == deployment.sourceWorkspaceId },
                            busy = temporaryBusy,
                            onOpenWorker = { deployment.workerUrl?.let(::openExternalUrl) },
                            onStartClaim = {
                                temporaryAction {
                                    val pending = viewModel.beginTemporaryWorkerClaim(deployment.temporaryDeploymentId)
                                    pending.claimUrl?.let { openExternalUrl(it) }
                                }
                            },
                            onCompleteClaim = {
                                temporaryAction { viewModel.completeTemporaryWorkerClaim(deployment.temporaryDeploymentId) }
                            },
                            onCancelClaim = {
                                temporaryAction { viewModel.cancelTemporaryWorkerClaim(deployment.temporaryDeploymentId) }
                            },
                        )
                    }
                }
            }
            if (deployments.isNotEmpty()) {
                Text("最近部署", style = MaterialTheme.typography.titleMedium)
                deployments.forEach { deployment ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(deployment.workerName)
                            Text("状态：${deployment.status}")
                            Text(deployment.safeSummary ?: "无摘要", style = MaterialTheme.typography.bodySmall)
                            deployment.versionId?.let { Text("版本：$it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    accounts?.let { available ->
        AlertDialog(
            onDismissRequest = { if (!busy) accounts = null },
            shape = AppDialogShape, containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(), textContentColor = appDialogContentColor(),
            title = { Text("选择 Cloudflare Account") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    available.forEach { account ->
                        TextButton(onClick = { selectedAccount = account.id }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text((if (selectedAccount == account.id) "✓ " else "") + account.name)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val id = selectedAccount ?: return@Button
                    perform {
                        withContext(Dispatchers.IO) { viewModel.switchCloudflareComputerAccount(computer.id, id) }
                        reload()
                        accounts = null
                    }
                }, enabled = !busy && selectedAccount != null) { Text("确认切换") }
            },
            dismissButton = { TextButton(onClick = { accounts = null }, enabled = !busy) { Text("取消") } },
        )
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAction = null },
            shape = AppDialogShape, containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(), textContentColor = appDialogContentColor(),
            title = { Text(if (action == "logout") "退出 Cloudflare 登录" else "删除本地 Computer") },
            text = { Text(if (action == "logout") "清除本机授权，共用此授权的连接将需要重新登录。云端资源保留。" else "删除本机连接和绑定，云端资源保留。") },
            confirmButton = {
                Button(onClick = {
                    perform {
                        withContext(Dispatchers.IO) {
                            if (action == "logout") viewModel.logoutCloudflareComputer(computer.id)
                            else viewModel.deleteLocalCloudflareComputer(computer.id)
                        }
                        pendingAction = null
                        if (action == "delete") navController.popBackStack() else reload()
                    }
                }, enabled = !busy) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !busy) { Text("取消") } },
        )
    }

    ComputerFullApprovalWarningDialog(
        visible = pendingPermissionMode == ComputerPermissionMode.FULL,
        isBusy = busy,
        onDismiss = { if (!busy) pendingPermissionMode = null },
        onConfirm = {
            pendingPermissionMode = null
            perform { viewModel.setComputerPermissionMode(computer.id, ComputerPermissionMode.FULL) }
        },
    )

    if (temporaryWorkspaceDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!temporaryBusy) temporaryWorkspaceDialogVisible = false },
            shape = AppDialogShape,
            containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(),
            textContentColor = appDialogContentColor(),
            title = { Text("选择部署 Workspace") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("Temporary Worker 会读取所选 App 私有 Workspace，先执行入口、敏感文件和大小检查。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    workspaces.forEach { workspace ->
                        TextButton(
                            onClick = { selectedTemporaryWorkspace = workspace },
                            enabled = !temporaryBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text((if (selectedTemporaryWorkspace?.id == workspace.id) "✓ " else "") + workspace.id)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val workspace = selectedTemporaryWorkspace ?: return@Button
                        temporaryAction {
                            viewModel.createTemporaryWorkerFromWorkspace(workspace.id)
                            temporaryWorkspaceDialogVisible = false
                        }
                    },
                    enabled = !temporaryBusy && selectedTemporaryWorkspace != null,
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { temporaryWorkspaceDialogVisible = false }, enabled = !temporaryBusy) { Text("取消") }
            },
        )
    }
}

/** 信息行统一“浅色标签 + 常规值”，避免整屏同字号的纯文本墙。 */
@Composable
private fun CloudflareInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Cloudflare 没有容器和端口，权限模式说明要用云端写入来解释，不能复用 VPS 的容器文案。 */
private fun cloudflarePermissionSummary(mode: ComputerPermissionMode): String = when (mode) {
    ComputerPermissionMode.MANUAL -> "云端写操作（部署、删除、D1/KV/R2 写入等）会先请你确认；读取直接执行。"
    ComputerPermissionMode.SMART -> "由模型判断哪些云端写操作需要你确认；读取直接执行。"
    ComputerPermissionMode.FULL -> "所有合法操作直接执行，不再弹确认。"
}

/** Temporary Worker 的状态和可恢复操作集中显示，避免用户误以为它已经成为正式 Computer。 */
@Composable
private fun TemporaryWorkerCard(
    deployment: TemporaryWorkerDeployment,
    workspace: ComputerWorkspace?,
    busy: Boolean,
    onOpenWorker: () -> Unit,
    onStartClaim: () -> Unit,
    onCompleteClaim: () -> Unit,
    onCancelClaim: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${workspace?.id ?: deployment.sourceWorkspaceId} · ${deployment.claimStatus}")
            deployment.workerUrl?.let { url ->
                Text(url, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenWorker, enabled = !busy) { Text("打开 Worker URL") }
            }
            Text("过期时间：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(deployment.expiresAt))}", style = MaterialTheme.typography.bodySmall)
            when (deployment.claimStatus) {
                TemporaryWorkerStatus.ACTIVE -> {
                    Text("Claim 会打开外部登录页面；完成登录后返回此处点击完成 Claim。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onStartClaim, enabled = !busy) { Text("开始 Claim") }
                }
                TemporaryWorkerStatus.CLAIM_PENDING -> {
                    TextButton(onClick = onCompleteClaim, enabled = !busy) { Text("完成 Claim") }
                    TextButton(onClick = onCancelClaim, enabled = !busy) { Text("取消 Claim") }
                }
                TemporaryWorkerStatus.FAILED -> Text("创建失败，可重新选择 Workspace 重试。", style = MaterialTheme.typography.bodySmall)
                TemporaryWorkerStatus.EXPIRED -> Text("已过期，请重新创建。", style = MaterialTheme.typography.bodySmall)
                TemporaryWorkerStatus.CLAIMED -> Text("已 Claim；请在 Cloudflare Account 中继续管理该资源。", style = MaterialTheme.typography.bodySmall)
                TemporaryWorkerStatus.CREATING -> Text("正在创建……", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
