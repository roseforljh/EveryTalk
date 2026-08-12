package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.ComputerAuditEventEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.Base64
import java.util.UUID

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
    private val connectionPool = ComputerConnectionPool(sshClient, credentialStore)
    private val probe = ComputerProbe()
    private val dedicatedKeyManager = ComputerDedicatedKeyManager(sshClient)
    private val provisioner = ComputerProvisioner(applicationContext)
    private val connectionStopListener = ComputerConnectionServiceController.addStopListener(connectionPool::close)

    fun observeComputers(): Flow<List<Computer>> = dao.observeComputers().map { entities ->
        entities.map { it.toModel(json) }
    }

    fun observeSelections(): Flow<Map<String, String>> = dao.observeSelections().map { selections ->
        selections.associate { it.conversationId to it.selectedComputerId }
    }

    fun observeWorkspaces(computerId: String): Flow<List<ComputerWorkspace>> =
        dao.observeWorkspaces(computerId).map { entities -> entities.map { it.toModel() } }

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
                    provisioner.provision(connection, computer, sudoPassword, onProgress)
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
        migrateLegacyDirectRecords()
        dao.markInterruptedExecutionsUnknown()
        dao.markPrivatePreviewsStopped()
        dao.recoverInterruptedComputerOperations(COMPUTER_BOOTSTRAP_VERSION)
        dao.markOutdatedContainerConfiguration(COMPUTER_BOOTSTRAP_VERSION)
        connectionPool.closeIdle(maxIdleMillis = 0)
    }

    /**
     * v2.0 的 Direct 服务器升级后统一进入混合模式。
     * 这里只更新 Room，用户点击修复前绝不连接或修改 VPS。
     */
    private suspend fun migrateLegacyDirectRecords() {
        dao.getLegacyDirectComputers().forEach { entity ->
            val migratedComputer = migrateLegacyDirectComputer(entity.toModel(json))
            dao.getWorkspacesForComputer(entity.id).forEach { workspaceEntity ->
                dao.upsertWorkspace(
                    migrateLegacyDirectWorkspace(workspaceEntity.toModel(), migratedComputer.sandboxImage).toEntity(),
                )
            }
            dao.upsertComputer(migratedComputer.toEntity(json))
        }
    }

    /** 手机网络发生切换时丢弃旧 Transport，下一次操作会重新解析并验证固定 Host Key。 */
    suspend fun handleNetworkChanged() {
        connectionPool.close()
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
        if (requireReady && computer.status != ComputerStatus.READY) {
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
        connectionPool.close()
    }
}
