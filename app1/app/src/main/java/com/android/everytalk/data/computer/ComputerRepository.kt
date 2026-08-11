package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.service.ComputerConnectionServiceController
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.ComputerAuditEventEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
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

    suspend fun getComputer(computerId: String): Computer? = dao.getComputer(computerId)?.toModel(json)

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
    ): Computer {
        val endpoint = ComputerEndpointValidator.validate(request.host, request.port, request.username)
        if (endpoint.host != confirmedHostKey.host || endpoint.port != confirmedHostKey.port) {
            request.credential.clear()
            throw ComputerException(ComputerErrorCodes.HOST_KEY_CHANGED, "确认期间服务器地址发生变化")
        }

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

        credentialStore.saveComputerCredential(computer.id, request.credential)
        try {
            dao.upsertComputer(computer.toEntity(json))
        } catch (error: Throwable) {
            credentialStore.deleteComputerCredential(computer.id)
            throw error
        }

        return try {
            dao.updateComputerStatus(computer.id, ComputerStatus.PROBING.name, null)
            val capabilities = connectionPool.withConnection(computer) { connection ->
                probe.probe(connection, computer.port)
            }
            val status = if (
                computer.runMode == ComputerRunMode.CONTAINER &&
                (!capabilities.dockerAvailable || computer.bootstrapVersion == null)
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
            computer = tryUpgradeToDedicatedKey(computer)
            recordAudit(computer.id, "COMPUTER_ADDED", "SUCCESS", "SSH 登录和本地能力探测成功")
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
        dao.updateComputerStatus(computerId, ComputerStatus.PROBING.name, null)
        return try {
            val capabilities = connectionPool.withConnection(current) { connection ->
                probe.probe(connection, current.port)
            }
            val refreshed = current.copy(
                status = if (
                    current.runMode == ComputerRunMode.CONTAINER &&
                    (!capabilities.dockerAvailable || current.bootstrapVersion == null)
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
        }
    }

    suspend fun provisionContainer(computerId: String, sudoPassword: CharArray?): Computer {
        val current = requireComputer(computerId)
        if (current.runMode != ComputerRunMode.CONTAINER) {
            sudoPassword?.fill('\u0000')
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "当前服务器使用 Direct 模式")
        }
        val foregroundActivity = acquireForegroundActivity()
        try {
            dao.updateComputerStatus(computerId, ComputerStatus.PROVISIONING.name, null)
            return try {
                val result = withConnection(computerId, requireReady = false) { connection, computer ->
                    provisioner.provision(connection, computer, sudoPassword)
                }
                val configured = current.copy(
                    bootstrapVersion = result.bootstrapVersion,
                    sandboxImage = result.sandboxImage,
                    status = ComputerStatus.VERIFYING,
                    lastErrorCode = null,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.upsertComputer(configured.toEntity(json))
                recordAudit(computerId, "CONTAINER_PROVISION", "SUCCESS", "Container 环境配置完成")
                refreshComputer(computerId)
            } catch (error: ComputerException) {
                dao.updateComputerStatus(computerId, ComputerStatus.CONFIGURATION_REQUIRED.name, error.code)
                recordAudit(computerId, "CONTAINER_PROVISION", "FAILED", error.code)
                throw error
            }
        } finally {
            foregroundActivity.close()
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

    suspend fun replaceCredential(computerId: String, credential: ComputerCredential): Computer {
        val current = requireComputer(computerId)
        val credentialForTest = credential.copySecret()
        try {
            sshClient.connect(current, credentialForTest).use { }
        } catch (error: Throwable) {
            credential.clear()
            throw error
        }
        credentialStore.saveComputerCredential(computerId, credential)
        connectionPool.disconnect(computerId)
        val updated = current.copy(
            authKind = credential.kind,
            credentialState = ComputerCredentialState.ORIGINAL_ENCRYPTED,
            status = ComputerStatus.OFFLINE,
            lastErrorCode = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertComputer(updated.toEntity(json))
        recordAudit(computerId, "CREDENTIAL_REPLACED", "SUCCESS", null)
        return refreshComputer(computerId)
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
        recordAudit(computerId, "HOST_KEY_REPLACED", "CONFIRMED", replacement.fingerprint)
        return refreshComputer(computerId)
    }

    suspend fun disconnect(computerId: String) {
        requireComputer(computerId)
        connectionPool.disconnect(computerId)
        dao.updateComputerStatus(computerId, ComputerStatus.DISCONNECTED.name, null)
        recordAudit(computerId, "DISCONNECT", "SUCCESS", null)
    }

    suspend fun deleteComputer(computerId: String) {
        requireComputer(computerId)
        dao.updateComputerStatus(computerId, ComputerStatus.DELETING.name, null)
        connectionPool.disconnect(computerId)
        credentialStore.deleteComputerCredential(computerId)
        dao.deleteComputer(computerId)
    }

    suspend fun recoverLocalState() {
        dao.markInterruptedExecutionsUnknown()
        dao.markPrivatePreviewsStopped()
        connectionPool.closeIdle(maxIdleMillis = 0)
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

    internal fun dao(): ComputerDao = dao
    internal fun credentialStore(): ComputerCredentialStore = credentialStore

    /** 活跃 SSH 操作持有该令牌，全部令牌释放后 Android 前台服务自动停止。 */
    internal fun acquireForegroundActivity(): Closeable =
        ComputerConnectionServiceController.acquire(applicationContext)

    private suspend fun requireComputer(computerId: String): Computer = getComputer(computerId)
        ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")

    private suspend fun recordAudit(
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
                authKind = ComputerAuthKind.PRIVATE_KEY,
                credentialState = ComputerCredentialState.DEDICATED_KEY,
                updatedAt = System.currentTimeMillis(),
            ).also { upgraded ->
                dao.upsertComputer(upgraded.toEntity(json))
                recordAudit(computer.id, "DEDICATED_KEY_INSTALLED", "SUCCESS", null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordAudit(computer.id, "DEDICATED_KEY_INSTALLED", "FALLBACK", "保留本地加密的原始凭据")
            computer
        }
    }

    override fun close() {
        connectionStopListener.close()
        connectionPool.close()
    }
}

private fun ComputerCredential.copySecret(): ComputerCredential = when (this) {
    is ComputerCredential.Password -> ComputerCredential.Password(password.copyOf())
    is ComputerCredential.PrivateKey -> ComputerCredential.PrivateKey(privateKey.copyOf(), passphrase?.copyOf())
}
