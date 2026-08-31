package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.ComputerAuditEventEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.android.everytalk.util.AppLogger
import java.io.Closeable
import java.util.Base64
import java.util.UUID

/**
 * 恢复查询允许 READY 以及曾经配置成功但暂时断线的服务器。
 * CONFIGURATION_REQUIRED、HOST_KEY_CHANGED 等状态必须先由用户修复，不能让后台
 * 对账绕过配置流程或重新接受一把未知 Host Key。
 */
internal fun ComputerStatus.canAttemptExecutionRecovery(): Boolean = this in setOf(
    ComputerStatus.READY,
    ComputerStatus.CONFIGURATION_REQUIRED,
    ComputerStatus.OFFLINE,
    ComputerStatus.DISCONNECTED,
)

/** 新工具调用只允许使用已就绪的服务器；旧任务恢复会显式使用 requireReady=false。 */
internal fun ComputerStatus.canUseSshTools(): Boolean = this == ComputerStatus.READY

/** 只有受管 Container 任务会被 Wrapper 升级后的容器重建影响。 */
internal fun hasActiveContainerExecution(executions: List<ComputerExecutionEntity>): Boolean =
    executions.any { execution ->
        execution.target == null || execution.target == ComputerExecTarget.CONTAINER.name
    }

/**
 * Computer 功能的本地统一入口。Room 保存非敏感状态，CredentialStore 保存加密凭据，SSH 直连用户 VPS。
 */
class ComputerRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val dao: ComputerDao = AppDatabase.getDatabase(applicationContext).computerDao()
    private val credentialStore = ComputerCredentialStore(applicationContext)
    private val sshClient = ComputerSshClient()
    private val connectionPool = ComputerConnectionPoolRegistry.get(sshClient, credentialStore)
    private val probe = ComputerProbe()
    private val dedicatedKeyManager = ComputerDedicatedKeyManager(sshClient)
    private val provisioner = ComputerProvisioner(applicationContext)
    private val runtimeEnvelope = ComputerRuntimeEnvelope(applicationContext)
    private val executionReconciler by lazy {
        ComputerExecutionReconciler(
            dao = dao,
            gateway = ComputerRemoteExecutionGateway { execution -> queryRemoteExecution(execution) },
        )
    }
    private val connectionStopListener = ComputerConnectionServiceController.addStopListener {
        ComputerConnectionPoolRegistry.closeAll("service_controller_stop")
    }

    fun observeComputers(): Flow<List<Computer>> = dao.observeComputers().map { entities ->
        entities.map { it.toModel(json) }
    }

    fun observeSelections(): Flow<Map<String, String>> = dao.observeSelections().map { selections ->
        selections.associate { it.conversationId to it.selectedComputerId }
    }

    fun observeWorkspaces(computerId: String): Flow<List<ComputerWorkspace>> =
        dao.observeWorkspaces(computerId).map { entities -> entities.map { it.toModel() } }

    fun observeActiveTaskCount(computerId: String): Flow<Int> =
        dao.observeActiveRemoteExecutionCountForComputer(computerId)

    fun observePreviews(workspaceId: String): Flow<List<ComputerPreview>> =
        dao.observePreviews(workspaceId).map { entities -> entities.map { it.toModel() } }

    fun observeAuditEvents(computerId: String): Flow<List<ComputerAuditEvent>> =
        dao.observeAuditEvents(computerId).map { entities -> entities.map { it.toModel() } }

    suspend fun getComputer(computerId: String): Computer? = dao.getComputer(computerId)?.toModel(json)

    suspend fun getWorkspace(workspaceId: String): ComputerWorkspace? =
        dao.getWorkspaceById(workspaceId)?.toModel()

    suspend fun getWorkspaces(computerId: String): List<ComputerWorkspace> =
        dao.getWorkspacesForComputer(computerId).map { it.toModel() }

    suspend fun getSelectedComputer(conversationId: String): Computer? {
        val computerId = dao.getSelectedComputerId(conversationId) ?: return null
        return getComputer(computerId)
    }

    /** 首次阶段只做 SSH Key Exchange，不读取或提交 request 中的凭据。 */
    suspend fun probeHostKey(request: AddComputerRequest): HostKeyProbeResult =
        sshClient.probeHostKey(request.host, request.port)

    /**
     * 用户确认指纹后才保存加密凭据并认证。完成后执行只读 Probe，Direct 可直接 READY。
     */
    suspend fun addConfirmedComputer(
        request: AddComputerRequest,
        confirmedHostKey: HostKeyProbeResult,
        sudoPassword: CharArray?,
        onProgress: suspend (ComputerSetupStage) -> Unit = {},
    ): Computer {
        val endpoint = ComputerEndpointValidator.validate(request.host, request.port, request.username)
        if (endpoint.host != confirmedHostKey.host || endpoint.port != confirmedHostKey.port) {
            request.credential.clear()
            sudoPassword?.fill('\u0000')
            throw ComputerException(ComputerErrorCodes.HOST_KEY_CHANGED, "确认期间服务器地址发生变化")
        }

        onProgress(ComputerSetupStage.AUTHENTICATING)
        val now = System.currentTimeMillis()
        var computer = Computer(
            id = request.id,
            displayName = request.displayName.trim().ifEmpty { endpoint.host },
            host = endpoint.host,
            port = endpoint.port,
            username = endpoint.username.orEmpty(),
            resolvedAddress = confirmedHostKey.resolvedAddress,
            hostKeyAlgorithm = confirmedHostKey.algorithm,
            hostKeyBlobBase64 = Base64.getEncoder().encodeToString(confirmedHostKey.keyBlob),
            hostKeyFingerprint = confirmedHostKey.fingerprint,
            authKind = request.credential.kind,
            credentialState = ComputerCredentialState.ORIGINAL_ENCRYPTED,
            runMode = request.runMode,
            status = ComputerStatus.AUTHENTICATING,
            createdAt = now,
            updatedAt = now,
        )

        val originalCredential = request.credential.copySecret()
        try {
            credentialStore.saveOriginalComputerCredential(computer.id, originalCredential)
            credentialStore.saveComputerSudoPassword(computer.id, sudoPassword)
            credentialStore.saveComputerCredential(computer.id, request.credential)
            dao.upsertComputer(computer.toEntity(json))
        } catch (error: Throwable) {
            credentialStore.deleteComputerCredential(computer.id)
            credentialStore.deleteOriginalComputerCredential(computer.id)
            credentialStore.deleteComputerSudoPassword(computer.id)
            request.credential.clear()
            sudoPassword?.fill('\u0000')
            throw error
        }

        return try {
            dao.updateComputerStatus(computer.id, ComputerStatus.PROBING.name, null)
            val capabilities = connectionPool.withConnection(computer) { connection ->
                onProgress(ComputerSetupStage.INSPECTING_VPS)
                probe.probe(connection, computer.port)
            }
            val status = if (
                computer.runMode == ComputerRunMode.CONTAINER &&
                (!capabilities.dockerAvailable || computer.bootstrapVersion != COMPUTER_BOOTSTRAP_VERSION)
            ) {
                ComputerStatus.CONFIGURATION_REQUIRED
            } else {
                ComputerStatus.READY
            }
            computer = computer.copy(
                status = status,
                capabilities = capabilities,
                lastConnectedAt = System.currentTimeMillis(),
                lastErrorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertComputer(computer.toEntity(json))
            onProgress(ComputerSetupStage.SECURING_CONNECTION)
            computer = tryUpgradeToDedicatedKey(computer)
            recordAudit(computer.id, "COMPUTER_ADDED", "SUCCESS", null)
            computer
        } catch (error: ComputerException) {
            val status = when (error.code) {
                ComputerErrorCodes.HOST_KEY_CHANGED -> ComputerStatus.HOST_KEY_CHANGED
                ComputerErrorCodes.AUTH_FAILED, ComputerErrorCodes.PRIVATE_KEY_INVALID -> ComputerStatus.ACTION_REQUIRED
                else -> ComputerStatus.ERROR
            }
            dao.updateComputerStatus(computer.id, status.name, error.code)
            recordAudit(computer.id, "COMPUTER_ADDED", "FAILED", error.code)
            throw error
        }
    }

    suspend fun refreshComputer(computerId: String): Computer {
        val current = requireComputer(computerId)
        // 用户触发“重连并探测”时必须建立新 Transport，确保再次核对固定 Host Key。
        connectionPool.disconnect(computerId)
        dao.updateComputerStatus(computerId, ComputerStatus.PROBING.name, null)
        return try {
            val capabilities = connectionPool.withConnection(current) { connection ->
                probe.probe(connection, current.port)
            }
            val refreshed = current.copy(
                status = if (
                    current.runMode == ComputerRunMode.CONTAINER &&
                    (!capabilities.dockerAvailable || current.bootstrapVersion != COMPUTER_BOOTSTRAP_VERSION)
                ) {
                    ComputerStatus.CONFIGURATION_REQUIRED
                } else {
                    ComputerStatus.READY
                },
                capabilities = capabilities,
                lastConnectedAt = System.currentTimeMillis(),
                lastErrorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertComputer(refreshed.toEntity(json))
            refreshed
        } catch (error: ComputerException) {
            val status = if (error.code == ComputerErrorCodes.HOST_KEY_CHANGED) {
                ComputerStatus.HOST_KEY_CHANGED
            } else {
                ComputerStatus.OFFLINE
            }
            dao.updateComputerStatus(computerId, status.name, error.code)
            throw error
        } catch (error: Throwable) {
            // 页面退出、任务取消和未分类本地异常都不代表 VPS 离线，恢复探测前状态。
            withContext(NonCancellable) {
                dao.updateComputerStatus(computerId, current.status.name, current.lastErrorCode)
            }
            throw error
        }
    }

    suspend fun provisionContainer(
        computerId: String,
        onProgress: suspend (ComputerSetupStage) -> Unit = {},
    ): Computer {
        val current = requireComputer(computerId)
        if (current.runMode != ComputerRunMode.CONTAINER) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器使用 Direct 模式")
        }
        if (hasActiveContainerExecution(dao.getActiveRemoteExecutionsForComputer(computerId))) {
            throw ComputerException(
                code = ComputerErrorCodes.COMPUTER_NOT_READY,
                message = "当前还有 Container 任务在运行，请等待任务完成后升级",
                retryable = true,
                action = "WAIT_FOR_CONTAINER_TASKS",
            )
        }
        val runtimeOnlyUpgrade = current.canUseRuntimeOnlyUpgrade()
        val sudoPassword = if (current.username == "root") {
            null
        } else {
            resolveComputerProvisionPassword(
                savedSudoPassword = credentialStore.loadComputerSudoPassword(computerId),
                originalCredential = credentialStore.loadOriginalComputerCredential(computerId)
                    ?: credentialStore.loadComputerCredential(computerId),
            )
        }
        val foregroundActivity = acquireForegroundActivity()
        try {
            dao.updateComputerStatus(computerId, ComputerStatus.PROVISIONING.name, null)
            return try {
                val result = withConnection(computerId, requireReady = false) { connection, computer ->
                    provisioner.provision(
                        connection = connection,
                        computer = computer,
                        sudoPassword = sudoPassword,
                        runtimeOnly = runtimeOnlyUpgrade,
                        onProgress = onProgress,
                    )
                }
                val configured = current.copy(
                    bootstrapVersion = result.bootstrapVersion,
                    sandboxImage = result.sandboxImage,
                    status = ComputerStatus.VERIFYING,
                    lastErrorCode = null,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.upsertComputer(configured.toEntity(json))
                dao.updateContainerWorkspaceImage(computerId, result.sandboxImage)
                recordAudit(computerId, "CONTAINER_PROVISION", "SUCCESS", null)
                onProgress(ComputerSetupStage.VERIFYING)
                refreshComputer(computerId)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    connectionPool.disconnect(computerId)
                    dao.updateComputerStatus(
                        computerId,
                        ComputerStatus.CONFIGURATION_REQUIRED.name,
                        current.lastErrorCode,
                    )
                }
                throw error
            } catch (error: ComputerException) {
                val reportedError = if (
                    error.code == ComputerErrorCodes.SUDO_REQUIRED &&
                    current.username != "root" &&
                    sudoPassword == null
                ) {
                    ComputerException(
                        code = ComputerErrorCodes.SUDO_REQUIRED,
                        message = "缺少可用的 sudo 密码，请先编辑服务器补充",
                        retryable = true,
                        action = "UPDATE_CREDENTIAL",
                        cause = error,
                    )
                } else {
                    error
                }
                dao.updateComputerStatus(computerId, ComputerStatus.CONFIGURATION_REQUIRED.name, reportedError.code)
                recordAudit(computerId, "CONTAINER_PROVISION", "FAILED", reportedError.code)
                throw reportedError
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    connectionPool.disconnect(computerId)
                    dao.updateComputerStatus(
                        computerId,
                        ComputerStatus.CONFIGURATION_REQUIRED.name,
                        current.lastErrorCode,
                    )
                }
                throw error
            }
        } finally {
            sudoPassword?.fill('\u0000')
            foregroundActivity.close()
        }
    }

    /** 修复页取消后立即关闭该服务器的 SSH Transport，让阻塞中的 Channel 尽快退出。 */
    suspend fun cancelComputerOperation(computerId: String) {
        val current = requireComputer(computerId)
        connectionPool.disconnect(computerId)
        if (current.status == ComputerStatus.PROVISIONING || current.status == ComputerStatus.VERIFYING) {
            dao.updateComputerStatus(
                computerId,
                ComputerStatus.CONFIGURATION_REQUIRED.name,
                current.lastErrorCode,
            )
        }
    }

    /**
     * 编辑服务器参数前只探测候选地址的 Host Key。
     * 用户确认后才会测试登录并替换现有记录，失败时旧参数与旧凭据保持不变。
     */
    suspend fun probeUpdatedComputerHostKey(request: UpdateComputerRequest): HostKeyProbeResult =
        sshClient.probeHostKey(request.host, request.port)

    suspend fun updateComputer(
        request: UpdateComputerRequest,
        confirmedHostKey: HostKeyProbeResult,
        sudoPassword: CharArray?,
        replaceSudoPassword: Boolean,
    ): Computer {
        var candidateCredential: ComputerCredential? = null
        var previousDedicatedCredential: ComputerCredential? = null
        try {
            val current = requireComputer(request.id)
            val endpoint = ComputerEndpointValidator.validate(request.host, request.port, request.username)
            if (endpoint.host != confirmedHostKey.host || endpoint.port != confirmedHostKey.port) {
                throw ComputerException(ComputerErrorCodes.HOST_KEY_CHANGED, "确认期间服务器地址发生变化")
            }

            val confirmedKeyBlob = Base64.getEncoder().encodeToString(confirmedHostKey.keyBlob)
            val endpointChanged = current.host != endpoint.host ||
                current.port != endpoint.port ||
                current.username != endpoint.username.orEmpty() ||
                current.hostKeyAlgorithm != confirmedHostKey.algorithm ||
                current.hostKeyBlobBase64 != confirmedKeyBlob
            val sameRemoteAccount = current.username == endpoint.username.orEmpty() &&
                current.resolvedAddress == confirmedHostKey.resolvedAddress &&
                current.hostKeyBlobBase64 == confirmedKeyBlob
            val remoteAccountChanged = endpointChanged && !sameRemoteAccount
            val suppliedCredential = request.credential != null
            val credential = request.credential ?: if (remoteAccountChanged) {
                credentialStore.loadOriginalComputerCredential(request.id)
                    ?: credentialStore.loadComputerCredential(request.id)
            } else {
                credentialStore.loadComputerCredential(request.id)
            }
            candidateCredential = credential

            val previousDedicatedKeyExpected =
                remoteAccountChanged &&
                    current.credentialState == ComputerCredentialState.DEDICATED_KEY
            if (previousDedicatedKeyExpected) {
                previousDedicatedCredential = runCatching {
                    credentialStore.loadComputerCredential(current.id)
                }.getOrNull()
            }

            val keepDedicatedConnection =
                current.credentialState == ComputerCredentialState.DEDICATED_KEY && !remoteAccountChanged
            val replaceActiveCredential = remoteAccountChanged || (suppliedCredential && !keepDedicatedConnection)
            val candidate = current.copy(
                displayName = request.displayName.trim().ifEmpty { endpoint.host },
                host = endpoint.host,
                port = endpoint.port,
                username = endpoint.username.orEmpty(),
                resolvedAddress = confirmedHostKey.resolvedAddress,
                hostKeyAlgorithm = confirmedHostKey.algorithm,
                hostKeyBlobBase64 = confirmedKeyBlob,
                hostKeyFingerprint = confirmedHostKey.fingerprint,
                authKind = if (suppliedCredential) credential.kind else current.authKind,
                credentialState = when {
                    keepDedicatedConnection -> ComputerCredentialState.DEDICATED_KEY
                    replaceActiveCredential -> ComputerCredentialState.ORIGINAL_ENCRYPTED
                    else -> current.credentialState
                },
                status = ComputerStatus.PROBING,
                capabilities = null,
                lastErrorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            val credentialForTest = credential.copySecret()
            val capabilities = sshClient.connect(candidate, credentialForTest).use { connection ->
                probe.probe(connection, candidate.port)
            }
            if (suppliedCredential) {
                credentialStore.saveOriginalComputerCredential(candidate.id, credential.copySecret())
            }
            if (replaceActiveCredential) {
                credentialStore.saveComputerCredential(candidate.id, credential.copySecret())
            }
            if (replaceSudoPassword) {
                credentialStore.saveComputerSudoPassword(candidate.id, sudoPassword)
            } else {
                sudoPassword?.fill('\u0000')
            }
            connectionPool.disconnect(candidate.id)

            var updated = candidate.copy(
                status = if (
                    remoteAccountChanged ||
                    !capabilities.dockerAvailable ||
                    current.bootstrapVersion != COMPUTER_BOOTSTRAP_VERSION
                ) {
                    ComputerStatus.CONFIGURATION_REQUIRED
                } else {
                    ComputerStatus.READY
                },
                capabilities = capabilities,
                bootstrapVersion = current.bootstrapVersion.takeUnless { remoteAccountChanged },
                sandboxImage = current.sandboxImage.takeUnless { remoteAccountChanged },
                lastConnectedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertComputer(updated.toEntity(json))
            if (remoteAccountChanged) dao.markComputerWorkspacesRecovering(updated.id)
            if (replaceActiveCredential) updated = tryUpgradeToDedicatedKey(updated)

            val oldDedicatedKeyRemoved = when {
                !previousDedicatedKeyExpected -> true
                previousDedicatedCredential == null -> false
                else -> runCatching {
                    sshClient.connect(current, previousDedicatedCredential).use { connection ->
                        dedicatedKeyManager.removeForComputer(connection, current.id)
                    }
                }.isSuccess
            }
            recordAudit(
                updated.id,
                "COMPUTER_UPDATED",
                if (oldDedicatedKeyRemoved) "SUCCESS" else "FALLBACK",
                if (oldDedicatedKeyRemoved) null else "REMOTE_CLEANUP_PENDING",
            )
            return updated
        } finally {
            candidateCredential?.clear()
            if (candidateCredential !== request.credential) request.credential?.clear()
            previousDedicatedCredential?.clear()
            sudoPassword?.fill('\u0000')
        }
    }

    /** 选择操作始终覆盖旧选择，因此服务器到期或性能不足时可以随时切换。 */
    suspend fun selectComputer(conversationId: String, computerId: String) {
        val computer = requireComputer(computerId)
        if (computer.status != ComputerStatus.READY) {
            throw ComputerException(
                ComputerErrorCodes.COMPUTER_NOT_READY,
                "当前服务器不可用",
                action = "SELECT_COMPUTER",
            )
        }
        dao.selectComputer(conversationId, computerId)
    }

    suspend fun probeReplacementHostKey(computerId: String): HostKeyProbeResult {
        val current = requireComputer(computerId)
        return sshClient.probeHostKey(current.host, current.port)
    }

    suspend fun confirmReplacementHostKey(computerId: String, replacement: HostKeyProbeResult): Computer {
        val current = requireComputer(computerId)
        val endpoint = ComputerEndpointValidator.validate(current.host, current.port, current.username)
        if (replacement.host != endpoint.host || replacement.port != endpoint.port) {
            throw ComputerException(ComputerErrorCodes.HOST_KEY_CHANGED, "待确认 Host Key 与当前服务器不匹配")
        }
        connectionPool.disconnect(computerId)
        val updated = current.copy(
            resolvedAddress = replacement.resolvedAddress,
            hostKeyAlgorithm = replacement.algorithm,
            hostKeyBlobBase64 = Base64.getEncoder().encodeToString(replacement.keyBlob),
            hostKeyFingerprint = replacement.fingerprint,
            status = ComputerStatus.OFFLINE,
            lastErrorCode = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertComputer(updated.toEntity(json))
        recordAudit(computerId, "HOST_KEY_REPLACED", "CONFIRMED", null)
        return refreshComputer(computerId)
    }

    suspend fun disconnect(computerId: String) {
        requireComputer(computerId)
        connectionPool.disconnect(computerId)
        dao.updateComputerStatus(computerId, ComputerStatus.DISCONNECTED.name, null)
        recordAudit(computerId, "DISCONNECT", "SUCCESS", null)
    }

    /** 权限模式只改变本地审批策略，不连接或修改 VPS。 */
    suspend fun setPermissionMode(
        computerId: String,
        permissionMode: ComputerPermissionMode,
    ): Computer {
        val current = requireComputer(computerId)
        dao.updatePermissionMode(computerId, permissionMode.name)
        recordAudit(computerId, "PERMISSION_MODE", "SUCCESS", permissionMode.name)
        return current.copy(
            permissionMode = permissionMode,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Container 模式允许用户在详情页显式调整是否访问 VPS 私有网络。 */
    suspend fun setPrivateNetworkAllowed(computerId: String, allowed: Boolean): Computer {
        val current = requireComputer(computerId)
        if (current.runMode != ComputerRunMode.CONTAINER) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "Direct SSH 模式沿用 SSH 账号的网络权限")
        }
        val helper = if (current.username == "root") {
            "/usr/local/libexec/everytalk-containerctl"
        } else {
            "sudo -n -- /usr/local/libexec/everytalk-containerctl"
        }
        val mode = if (allowed) "private" else "restricted"
        val result = withConnection(computerId) { connection, _ ->
            connection.execute(
                command = "$helper set-network $mode",
                timeoutMillis = 30_000,
                maxOutputBytes = 64 * 1024,
            )
        }
        if (result.timedOut || result.exitCode != 0) {
            throw ComputerException(
                ComputerErrorCodes.HELPER_INTEGRITY_FAILED,
                "更新 Container 网络权限失败",
                retryable = true,
            )
        }
        val updated = current.copy(
            allowPrivateNetwork = allowed,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertComputer(updated.toEntity(json))
        recordAudit(computerId, "PRIVATE_NETWORK", "SUCCESS", if (allowed) "ALLOWED" else "BLOCKED")
        return updated
    }

    /**
     * 删除本地服务器记录前尝试移除 EveryTalk 专用公钥。
     * 远端不可达时仍销毁本地凭据，并通过返回值让 UI 准确提示残留公钥。
     */
    suspend fun deleteComputer(computerId: String): ComputerDeleteResult {
        val computer = requireComputer(computerId)
        dao.updateComputerStatus(computerId, ComputerStatus.DELETING.name, null)
        val remoteKeyRemoved = if (computer.credentialState == ComputerCredentialState.DEDICATED_KEY) {
            runCatching {
                withConnection(computerId, requireReady = false) { connection, _ ->
                    dedicatedKeyManager.removeForComputer(connection, computerId)
                }
            }.isSuccess
        } else {
            true
        }
        connectionPool.disconnect(computerId)
        credentialStore.deleteComputerCredential(computerId)
        credentialStore.deleteOriginalComputerCredential(computerId)
        credentialStore.deleteComputerSudoPassword(computerId)
        dao.deleteComputer(computerId)
        return ComputerDeleteResult(remoteKeyRemoved = remoteKeyRemoved)
    }

    suspend fun recoverLocalState() {
        // 普通工具无法从 VPS 续接；exec 保留原状态交给 ExecutionReconciler 查询。
        dao.markInterruptedNonExecExecutionsUnknown()
        dao.markPrivatePreviewsStopped()
        dao.recoverInterruptedComputerOperations(COMPUTER_BOOTSTRAP_VERSION)
        dao.markOutdatedContainerConfiguration(COMPUTER_BOOTSTRAP_VERSION)
        connectionPool.closeIdle(maxIdleMillis = 0)
    }

    /** 供应用恢复入口再次刷新活动远端任务，查询失败只保留上次状态。 */
    suspend fun reconcileRemoteExecutions(
        conversationIds: Set<String> = emptySet(),
    ): List<ComputerExecutionReconciliation> =
        if (conversationIds.isEmpty()) {
            executionReconciler.reconcileActive()
        } else {
            executionReconciler.reconcileForegroundActiveForConversations(conversationIds)
        }

    /**
     * 读取当前 Workspace 的本地活动任务快照，供下一轮模型请求使用。
     * 这里禁止同步连接 SSH；远端状态由后台监听负责刷新，避免模型续写被网络对账卡住。
     */
    suspend fun getComputerSessionState(workspaceId: String): ComputerSessionState? {
        if (dao.getWorkspaceById(workspaceId) == null) return null
        val executions = dao.getRemoteExecutionsForWorkspace(workspaceId)
        val active = executions.filter { execution ->
            execution.shouldReconcileRemote() ||
                execution.remoteStatus == ComputerRemoteStatus.UNKNOWN.name
        }
        val now = System.currentTimeMillis()
        val tasks = active.take(8).map { execution ->
            ComputerSessionTask(
                executionId = execution.id,
                target = execution.target?.let { runCatching { ComputerExecTarget.valueOf(it) }.getOrNull() }
                    ?: ComputerExecTarget.CONTAINER,
                status = execution.remoteStatus?.let {
                    runCatching { ComputerRemoteStatus.valueOf(it) }.getOrNull()
                } ?: ComputerRemoteStatus.UNKNOWN,
                elapsedSeconds = ((now - (execution.startedAt ?: now)) / 1_000L).coerceAtLeast(0L),
            )
        }
        return ComputerSessionState(
            workspaceId = workspaceId,
            activeTasks = tasks,
            totalActiveTasks = active.size,
        )
    }

    /**
     * 为单个活动任务打开一次长轮询 Channel。
     * Transport 仍由连接池按 computerId 复用，返回后立即把游标写入 Room。
     */
    suspend fun watchRemoteExecution(executionId: String): ComputerRemoteExecutionWatchEvent {
        val execution = dao.getExecutionById(executionId)
            ?: throw ComputerException(ComputerErrorCodes.EXECUTION_NOT_FOUND, "远端任务不存在")
        val computer = dao.getComputer(execution.computerId)?.toModel(json)
            ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
        val workspace = dao.getWorkspaceById(execution.workspaceId)?.toModel()
            ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
        val target = execution.target?.let { runCatching { ComputerExecTarget.valueOf(it) }.getOrNull() }
            ?: ComputerExecTarget.CONTAINER
        val event = try {
            withConnection(computer.id, requireReady = false) { connection, currentComputer ->
                runtimeEnvelope.watchExecution(
                    connection = connection,
                    computer = currentComputer,
                    workspace = workspace,
                    executionId = execution.id,
                    stdoutCursor = execution.stdoutCursor,
                    stderrCursor = execution.stderrCursor,
                    target = target,
                    expectedProcessId = execution.remoteProcessId,
                    expectedRequestHash = execution.requestHash,
                )
            }
        } catch (error: ComputerRemoteExecutionProtocolException) {
            // 长监听不负责保存原始协议文本。转换成统一业务错误，交给服务对账收尾，
            // 禁止解析异常逃出 SupervisorJob 并结束整个 App 进程。
            throw ComputerException(
                code = error.protocolCode,
                message = error.message ?: "远端 Execution 状态无效",
                retryable = false,
                cause = error,
            )
        }

        val now = System.currentTimeMillis()
        dao.updateRemoteExecutionProgress(
            executionId = execution.id,
            stdoutCursor = event.stdoutCursor,
            stderrCursor = event.stderrCursor,
            eventAt = now,
            observedAt = now,
            remoteStatus = event.result.snapshot.status.name,
            remoteExitCode = event.result.snapshot.exitCode,
        )
        markComputerReadyAfterRecovery(computer)
        return event
    }

    /** 长轮询收到终态后只对账对应任务，避免重新扫描其他 VPS。 */
    suspend fun reconcileRemoteExecution(executionId: String): ComputerExecutionReconciliation? =
        executionReconciler.reconcile(executionId)

    /**
     * 取消一个仍在 VPS 上运行的受管 Execution。
     *
     * 停止按钮先取消远端进程，再由上层取消模型协程。这样 Android 协程结束后，VPS
     * 仍会收到一次带身份校验的 cancel 请求，不会留下“本地已停、远端继续跑”的假状态。
     */
    suspend fun cancelRemoteExecution(executionId: String): ComputerRemoteExecutionSnapshot? {
        val execution = dao.getExecutionById(executionId) ?: return null
        val computer = dao.getComputer(execution.computerId)?.toModel(json) ?: return null
        val workspace = dao.getWorkspaceById(execution.workspaceId)?.toModel() ?: return null
        if (!computer.status.canAttemptExecutionRecovery()) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器状态不允许取消远端任务")
        }
        val target = execution.target?.let { runCatching { ComputerExecTarget.valueOf(it) }.getOrNull() }
            ?: ComputerExecTarget.CONTAINER
        val snapshot = withConnection(computer.id, requireReady = false) { connection, currentComputer ->
            runtimeEnvelope.cancelExecution(
                connection = connection,
                computer = currentComputer,
                workspace = workspace,
                executionId = execution.id,
                target = target,
                expectedProcessId = execution.remoteProcessId,
                expectedRequestHash = execution.requestHash,
            )
        }
        markComputerReadyAfterRecovery(computer)
        val terminal = snapshot.status in setOf(
            ComputerRemoteStatus.SUCCEEDED,
            ComputerRemoteStatus.FAILED,
            ComputerRemoteStatus.TIMED_OUT,
            ComputerRemoteStatus.CANCELLED,
        )
        val remoteStillActive = snapshot.status in setOf(
            ComputerRemoteStatus.STARTING,
            ComputerRemoteStatus.RUNNING,
        )
        val backgroundHandle = execution.completionMode == ComputerExecutionCompletionMode.RETURN_HANDLE.name
        val localStatus = when {
            backgroundHandle && execution.status in setOf(
                ComputerExecutionStatus.SUCCEEDED.name,
                ComputerExecutionStatus.FAILED.name,
                ComputerExecutionStatus.TIMED_OUT.name,
                ComputerExecutionStatus.CANCELLED.name,
            ) -> null
            snapshot.status == ComputerRemoteStatus.SUCCEEDED -> ComputerExecutionStatus.SUCCEEDED.name
            snapshot.status == ComputerRemoteStatus.FAILED -> ComputerExecutionStatus.FAILED.name
            snapshot.status == ComputerRemoteStatus.TIMED_OUT -> ComputerExecutionStatus.TIMED_OUT.name
            snapshot.status == ComputerRemoteStatus.CANCELLED -> ComputerExecutionStatus.CANCELLED.name
            remoteStillActive && execution.completionMode != ComputerExecutionCompletionMode.RETURN_HANDLE.name ->
                ComputerExecutionStatus.CANCELLED.name
            else -> ComputerExecutionStatus.UNKNOWN.name
        }
        dao.updateRemoteExecutionObservation(
            executionId = execution.id,
            target = target.name,
            remoteProcessId = snapshot.processId,
            remoteStatus = snapshot.status.name,
            remoteExitCode = snapshot.exitCode,
            observedAt = System.currentTimeMillis(),
            localStatus = localStatus,
            finishedAt = if (terminal) System.currentTimeMillis() else null,
            localExitCode = if (terminal) snapshot.exitCode else null,
            errorCode = when (snapshot.status) {
                ComputerRemoteStatus.MISSING -> ComputerErrorCodes.EXECUTION_NOT_FOUND
                ComputerRemoteStatus.UNKNOWN,
                ComputerRemoteStatus.STOPPED,
                -> ComputerErrorCodes.EXECUTION_UNKNOWN
                ComputerRemoteStatus.STARTING,
                ComputerRemoteStatus.RUNNING,
                -> ComputerErrorCodes.EXECUTION_CANCEL_REQUESTED
                else -> null
            },
        )
        return snapshot
    }

    private suspend fun queryRemoteExecution(
        execution: ComputerExecutionEntity,
    ): ComputerRemoteExecutionQuery {
        val computer = dao.getComputer(execution.computerId)?.toModel(json)
            ?: return ComputerRemoteExecutionQuery.Missing
        val workspace = dao.getWorkspaceById(execution.workspaceId)?.toModel()
            ?: return ComputerRemoteExecutionQuery.Missing
        if (!computer.status.canAttemptExecutionRecovery()) {
            return ComputerRemoteExecutionQuery.Unavailable("当前服务器尚未处于可恢复连接状态")
        }
        val target = execution.target?.let { runCatching { ComputerExecTarget.valueOf(it) }.getOrNull() }
            ?: ComputerExecTarget.CONTAINER
        return try {
            // 恢复查询允许 OFFLINE/DISCONNECTED 服务器受控重连；真正连接失败由 Unavailable 保留原状态。
            val snapshot = withConnection(computer.id, requireReady = false) { connection, _ ->
                runtimeEnvelope.queryExecutionStatus(
                    connection = connection,
                    computer = computer,
                    workspace = workspace,
                    executionId = execution.id,
                    target = target,
                    expectedProcessId = execution.remoteProcessId,
                    expectedRequestHash = execution.requestHash,
                )
            }
            markComputerReadyAfterRecovery(computer)
            if (snapshot.status == ComputerRemoteStatus.MISSING) {
                ComputerRemoteExecutionQuery.Missing
            } else {
                ComputerRemoteExecutionQuery.State(
                    buildRemoteStatePayload(target, snapshot),
                )
            }
        } catch (error: ComputerRemoteExecutionProtocolException) {
            // 协议响应已到达但格式不可信，交给严格解析器写 UNKNOWN，不能伪装成网络暂时不可用。
            ComputerRemoteExecutionQuery.State(error.payload)
        } catch (error: ComputerException) {
            if (error.retryable) {
                invalidateRemoteQueryConnection(computer.id, error)
            }
            if (error.code == ComputerErrorCodes.EXECUTION_NOT_FOUND) {
                ComputerRemoteExecutionQuery.Missing
            } else if (error.code == ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT ||
                error.code == ComputerErrorCodes.EXECUTION_STATE_INVALID
            ) {
                ComputerRemoteExecutionQuery.Invalid(
                    message = error.message,
                    code = error.code,
                )
            } else {
                ComputerRemoteExecutionQuery.Unavailable(
                    message = error.message,
                    connectionFailure = error.code == ComputerErrorCodes.SSH_TIMEOUT ||
                        error.code == ComputerErrorCodes.HOST_RESOLUTION_FAILED,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            invalidateRemoteQueryConnection(computer.id, error)
            ComputerRemoteExecutionQuery.Unavailable(
                message = error.message,
                connectionFailure = isComputerConnectionFailure(error),
            )
        }
    }

    /**
     * Runtime 已经把网络异常转换成业务异常后，连接池看不到原始异常，无法自动清理坏连接。
     * 这里在返回 Unavailable 前主动丢弃当前 Transport，下一轮查询或用户命令才会重新认证。
     */
    private suspend fun invalidateRemoteQueryConnection(computerId: String, error: Throwable) {
        if (error is ComputerException && !error.retryable) return
        if (error is ComputerException || isComputerConnectionFailure(error)) {
            val code = (error as? ComputerException)?.code ?: error::class.java.simpleName
            AppLogger.warn(
                "ComputerSsh",
                "远端状态查询失败，丢弃 SSH Transport computer=$computerId code=$code message=${error.message}",
            )
            connectionPool.disconnect(computerId, reason = "remote_query_failed:$code")
        }
    }

    /** RuntimeEnvelope 已完成严格解析，这里补上对账器需要的原始身份字段。 */
    private fun buildRemoteStatePayload(
        target: ComputerExecTarget,
        snapshot: ComputerRemoteExecutionSnapshot,
    ): String = buildString {
        appendLine("protocol=2")
        appendLine("execution_id=${snapshot.executionId}")
        appendLine("process_id=${snapshot.processId}")
        // 这里必须保留 VPS 实际返回的身份字段，让严格解析器能够发现篡改或串任务，
        // 不能用本地值覆盖远端值后再进行“自证”。
        appendLine("request_hash=${snapshot.requestHash.orEmpty()}")
        appendLine("target=${snapshot.target?.name ?: target.name}")
        appendLine("pid=${snapshot.pid ?: 0}")
        appendLine("start_ticks=${snapshot.startTicks ?: 0}")
        appendLine("status=${snapshot.status.name}")
        appendLine("exit_code=${snapshot.exitCode ?: ""}")
        val startedAt = snapshot.startedAt ?: 0L
        appendLine("started_at=$startedAt")
        appendLine("updated_at=${maxOf(startedAt, snapshot.updatedAt ?: startedAt)}")
        appendLine("stdout_bytes=${snapshot.stdoutBytes}")
        appendLine("stderr_bytes=${snapshot.stderrBytes}")
    }

    /** 状态查询已经证明 SSH、Helper 和固定 Runtime 可用时，解除网络断开造成的 READY 锁定。 */
    private suspend fun markComputerReadyAfterRecovery(computer: Computer) {
        if (computer.status == ComputerStatus.OFFLINE || computer.status == ComputerStatus.DISCONNECTED) {
            dao.updateComputerStatus(computer.id, ComputerStatus.READY.name, null)
        }
    }

    /** 手机网络发生切换时丢弃旧 Transport，下一次操作会重新解析并验证固定 Host Key。 */
    suspend fun handleNetworkChanged() {
        connectionPool.closeWithReason("network_changed")
        dao.markPrivatePreviewsStopped()
    }

    suspend fun migrateConversationId(sourceConversationId: String, targetConversationId: String) {
        if (sourceConversationId.isBlank() || targetConversationId.isBlank()) return
        dao.migrateConversationId(sourceConversationId, targetConversationId)
    }

    internal suspend fun <T> withConnection(
        computerId: String,
        requireReady: Boolean = true,
        block: suspend (ComputerSshConnection, Computer) -> T,
    ): T {
        val computer = requireComputer(computerId)
        if (requireReady && !computer.status.canUseSshTools()) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器不可用")
        }
        return connectionPool.withConnection(computer) { connection -> block(connection, computer) }
    }

    internal suspend fun acquireConnection(computerId: String): Pair<ComputerConnectionLease, Computer> {
        val computer = requireComputer(computerId)
        if (computer.status != ComputerStatus.READY) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器不可用")
        }
        return connectionPool.acquire(computer) to computer
    }

    /** 建立需要跨调用持有的 PTY 或端口转发，并沿用统一的安全 Channel 重试边界。 */
    internal suspend fun <T> acquireConnectionAndOpen(
        computerId: String,
        open: suspend (ComputerSshConnection) -> T,
    ): Triple<ComputerConnectionLease, Computer, T> {
        val computer = requireComputer(computerId)
        if (computer.status != ComputerStatus.READY) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器不可用")
        }
        val (lease, resource) = connectionPool.acquireWithChannel(computer, open)
        return Triple(lease, computer, resource)
    }

    internal fun dao(): ComputerDao = dao

    /**
     * 丢弃当前服务器的 SSH Transport。
     *
     * 远端 Channel 在执行过程中断开时，底层 Transport 仍可能短暂显示为可用。
     * 只读命令恢复前必须主动丢弃它，下一次调用才会重新建立 SSH 连接。
     */
    internal suspend fun invalidateConnection(computerId: String) {
        connectionPool.disconnect(computerId)
    }
    internal fun credentialStore(): ComputerCredentialStore = credentialStore

    /** 活跃 SSH 操作持有该令牌，全部令牌释放后 Android 前台服务自动停止。 */
    internal fun acquireForegroundActivity(): Closeable =
        ComputerConnectionServiceController.acquire(applicationContext)

    private suspend fun requireComputer(computerId: String): Computer = getComputer(computerId)
        ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")

    internal suspend fun recordAudit(
        computerId: String,
        eventType: String,
        outcome: String,
        safeSummary: String?,
    ) {
        dao.upsertAuditEvent(
            ComputerAuditEventEntity(
                id = UUID.randomUUID().toString(),
                computerId = computerId,
                eventType = eventType,
                outcome = outcome,
                safeSummary = safeSummary,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun tryUpgradeToDedicatedKey(computer: Computer): Computer {
        if (computer.credentialState != ComputerCredentialState.ORIGINAL_ENCRYPTED) return computer
        return try {
            var authenticatedConnection: ComputerSshConnection? = null
            val dedicatedKey = connectionPool.withConnection(computer) { connection ->
                authenticatedConnection = connection
                // 重新配置同一账号时先移除旧标记 Key，避免 authorized_keys 累积失效授权。
                dedicatedKeyManager.removeForComputer(connection, computer.id)
                dedicatedKeyManager.installAndVerify(computer, connection)
            }
            try {
                credentialStore.saveComputerCredential(computer.id, dedicatedKey.credential)
            } catch (error: Throwable) {
                authenticatedConnection?.let { connection ->
                    runCatching { dedicatedKeyManager.rollback(connection, dedicatedKey.authorizedKeyLine) }
                }
                dedicatedKey.credential.clear()
                throw error
            }
            connectionPool.disconnect(computer.id)
            computer.copy(
                credentialState = ComputerCredentialState.DEDICATED_KEY,
                updatedAt = System.currentTimeMillis(),
            ).also { upgraded ->
                dao.upsertComputer(upgraded.toEntity(json))
                recordAudit(computer.id, "DEDICATED_KEY_INSTALLED", "SUCCESS", null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordAudit(computer.id, "DEDICATED_KEY_INSTALLED", "FALLBACK", "ORIGINAL_CREDENTIAL_RETAINED")
            computer
        }
    }

    override fun close() {
        connectionStopListener.close()
        // 连接池属于整个 App 进程。关闭某个 Repository 不能切断前台服务正在监听的任务。
    }
}
