package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PREVIEW_HELPER = "/usr/local/libexec/everytalk-containerctl"

data class ComputerPreviewOpenResult(
    val preview: ComputerPreview,
    val url: String,
    val warning: String? = null,
)

@kotlinx.serialization.Serializable
data class ComputerPublicPreviewRequest(
    val context: ComputerRequestContext,
    val port: Int,
    val protocol: String,
    val expiresInSeconds: Long?,
    val target: ComputerExecTarget = ComputerExecTarget.CONTAINER,
)

/** Private Preview 持有 SSH lease；Public Preview 只有显式确认后才创建。 */
class ComputerPreviewManager(private val repository: ComputerRepository) : Closeable {
    private data class ActivePrivatePreview(
        val lease: ComputerConnectionLease,
        val forward: ComputerPortForward,
        val foregroundActivity: Closeable,
    )

    private val activePrivatePreviews = ConcurrentHashMap<String, ActivePrivatePreview>()
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val expiryJobs = ConcurrentHashMap<String, Job>()

    suspend fun openPrivate(
        context: ComputerRequestContext,
        port: Int,
        protocol: String = "http",
        target: ComputerExecTarget = ComputerExecTarget.CONTAINER,
    ): ComputerPreviewOpenResult {
        validatePortAndProtocol(port, protocol)
        val workspace = requireWorkspace(context)
        val foregroundActivity = repository.acquireForegroundActivity()
        val computer: Computer
        val remoteHost: String
        try {
            computer = repository.getComputer(context.computerId)
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
            remoteHost = when (effectivePreviewTarget(computer, target)) {
                ComputerExecTarget.HOST -> "127.0.0.1"
                ComputerExecTarget.CONTAINER -> repository.withConnection(computer.id) { connection, _ ->
                    resolveContainerAddress(connection, computer, workspace.id)
                }
            }
        } catch (error: Throwable) {
            foregroundActivity.close()
            throw error
        }
        val (lease, _, forward) = try {
            repository.acquireConnectionAndOpen(context.computerId) { connection ->
                connection.openLocalPortForward(port, remoteHost)
            }
        } catch (error: Throwable) {
            foregroundActivity.close()
            throw error
        }
        try {
            val preview = ComputerPreview(
                workspaceId = workspace.id,
                remotePort = port,
                target = effectivePreviewTarget(computer, target),
                localPort = forward.localPort,
                protocol = protocol,
                visibility = ComputerPreviewVisibility.PRIVATE,
            )
            repository.dao().upsertPreview(preview.toEntity())
            activePrivatePreviews[preview.id] = ActivePrivatePreview(lease, forward, foregroundActivity)
            repository.recordAudit(computer.id, "PRIVATE_PREVIEW_OPENED", "SUCCESS", port.toString())
            return ComputerPreviewOpenResult(preview, "$protocol://127.0.0.1:${forward.localPort}")
        } catch (error: Throwable) {
            lease.close()
            foregroundActivity.close()
            throw error
        }
    }

    suspend fun confirmPublic(request: ComputerPublicPreviewRequest): ComputerPreviewOpenResult {
        validatePortAndProtocol(request.port, request.protocol)
        val workspace = requireWorkspace(request.context)
        val computer = repository.getComputer(request.context.computerId)
            ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器记录不存在")
        val previewId = "preview_${UUID.randomUUID().toString().replace("-", "")}"
        val target = effectivePreviewTarget(computer, request.target)
        val publicPort = when (target) {
            ComputerExecTarget.HOST -> verifyHostPublicPort(computer, request.port)
            ComputerExecTarget.CONTAINER -> openContainerPublicPort(
                computer,
                workspace.id,
                previewId,
                request.port,
                request.expiresInSeconds,
            )
        }
        val now = System.currentTimeMillis()
        val preview = ComputerPreview(
            id = previewId,
            workspaceId = workspace.id,
            remotePort = request.port,
            target = target,
            publicPort = publicPort,
            protocol = request.protocol,
            visibility = ComputerPreviewVisibility.PUBLIC,
            createdAt = now,
            expiresAt = request.expiresInSeconds?.let { now + it.coerceIn(60, 604_800) * 1000 },
        )
        repository.dao().upsertPreview(preview.toEntity())
        scheduleExpiry(preview)
        repository.recordAudit(computer.id, "PUBLIC_PREVIEW_OPENED", "SUCCESS", publicPort.toString())
        val host = if (':' in computer.host) "[${computer.host}]" else computer.host
        return ComputerPreviewOpenResult(
            preview = preview,
            url = "${request.protocol}://$host:$publicPort",
            warning = "云厂商安全组或 VPS 防火墙可能仍会阻止公网访问",
        )
    }

    suspend fun stop(previewId: String) {
        stopWithStatus(previewId, ComputerPreviewStatus.REVOKED)
    }

    private suspend fun stopWithStatus(previewId: String, targetStatus: ComputerPreviewStatus) {
        val entity = repository.dao().getPreview(previewId) ?: return
        val preview = entity.toModel()
        expiryJobs.remove(previewId)?.cancel()
        if (preview.status != ComputerPreviewStatus.ACTIVE) return
        activePrivatePreviews.remove(previewId)?.let { active ->
            active.forward.close()
            active.lease.close()
            active.foregroundActivity.close()
        }
        if (preview.visibility == ComputerPreviewVisibility.PUBLIC) {
            val workspace = repository.dao().getWorkspaceById(preview.workspaceId)?.toModel()
            if (workspace != null) {
                val computer = repository.getComputer(workspace.computerId)
                if (preview.target == ComputerExecTarget.CONTAINER && computer?.runMode == ComputerRunMode.CONTAINER) {
                    try {
                        repository.withConnection(computer.id, requireReady = false) { connection, _ ->
                            val result = connection.execute(
                                command = "${helperPrefix(computer)} close-public $previewId",
                                timeoutMillis = 30_000,
                                maxOutputBytes = 64 * 1024,
                            )
                            if (result.timedOut || result.exitCode != 0) {
                                throw ComputerException(
                                    ComputerErrorCodes.PUBLIC_PORT_BLOCKED,
                                    "无法确认 Public Preview 已撤销",
                                    retryable = true,
                                )
                            }
                        }
                    } catch (error: Throwable) {
                        repository.recordAudit(
                            workspace.computerId,
                            previewAuditEvent(targetStatus),
                            "FAILED",
                            "REMOTE_CLEANUP_PENDING",
                        )
                        throw error
                    }
                }
            }
        }
        repository.dao().upsertPreview(preview.copy(status = targetStatus, localPort = null).toEntity())
        val workspace = repository.dao().getWorkspaceById(preview.workspaceId)
        if (workspace != null) {
            repository.recordAudit(
                workspace.computerId,
                previewAuditEvent(targetStatus),
                "SUCCESS",
                null,
            )
        }
    }

    /** 关闭 Workspace 对应的所有活动 Preview，供清理和删除流程复用。 */
    suspend fun stopByWorkspace(workspaceId: String) {
        repository.dao().getPreviews(workspaceId)
            .map { it.toModel() }
            .filter { it.status == ComputerPreviewStatus.ACTIVE }
            .forEach { stop(it.id) }
    }

    /** App 恢复时对账全部 Public Preview，并重新建立本地到期计时。 */
    suspend fun reconcileExpirations() {
        repository.dao().getActivePublicPreviews().forEach { entity ->
            val preview = entity.toModel()
            if (preview.expiresAt != null && preview.expiresAt <= System.currentTimeMillis()) {
                runCatching { stopWithStatus(preview.id, ComputerPreviewStatus.EXPIRED) }
            } else {
                scheduleExpiry(preview)
            }
        }
    }

    /** 重连后核对 Public Preview 的真实进程状态，修正 VPS 重启或远端提前停止造成的陈旧记录。 */
    suspend fun reconcileComputer(computerId: String) {
        val computer = repository.getComputer(computerId) ?: return
        if (computer.runMode != ComputerRunMode.CONTAINER || computer.status != ComputerStatus.READY) return
        val previews = repository.dao().getActivePublicPreviewsForComputer(computerId)
            .map { it.toModel() }
            .filter { it.target == ComputerExecTarget.CONTAINER }
        if (previews.isEmpty()) return
        val inactiveIds = repository.withConnection(computerId) { connection, _ ->
            previews.filter { preview ->
                val result = connection.execute(
                    command = "${helperPrefix(computer)} preview-status ${preview.id}",
                    timeoutMillis = 15_000,
                    maxOutputBytes = 64 * 1024,
                )
                result.timedOut || result.exitCode != 0 || "status=active" !in result.stdout.lineSequence()
            }.map(ComputerPreview::id)
        }
        inactiveIds.forEach { previewId ->
            val preview = previews.first { it.id == previewId }
            val status = if (preview.expiresAt?.let { it <= System.currentTimeMillis() } == true) {
                ComputerPreviewStatus.EXPIRED
            } else {
                ComputerPreviewStatus.STOPPED
            }
            runCatching { stopWithStatus(previewId, status) }
        }
    }

    /** 网络切换会让本地转发地址失效，关闭全部进程内 lease，保留远端服务与历史记录。 */
    fun handleNetworkChanged() {
        activePrivatePreviews.values.forEach { active ->
            active.forward.close()
            active.lease.close()
            active.foregroundActivity.close()
        }
        activePrivatePreviews.clear()
    }

    private suspend fun resolveContainerAddress(
        connection: ComputerSshConnection,
        computer: Computer,
        workspaceId: String,
    ): String {
        val result = connection.execute(
            command = "${helperPrefix(computer)} container-address $workspaceId",
            timeoutMillis = 30_000,
            maxOutputBytes = 64 * 1024,
        )
        val address = result.stdout.trim()
        if (result.timedOut || result.exitCode != 0 || address.isEmpty() || address.any(Char::isWhitespace)) {
            throw ComputerException(ComputerErrorCodes.PREVIEW_FORWARD_LOST, "无法解析 Workspace Container 地址")
        }
        return try {
            InetAddress.getByName(address).hostAddress
        } catch (error: Throwable) {
            throw ComputerException(ComputerErrorCodes.PREVIEW_FORWARD_LOST, "Container 地址无效", cause = error)
        }
    }

    private suspend fun verifyHostPublicPort(computer: Computer, port: Int): Int {
        val result = repository.withConnection(computer.id) { connection, _ ->
            connection.execute(
                command = "ss -ltnH 'sport = :$port'",
                timeoutMillis = 15_000,
                maxOutputBytes = 64 * 1024,
            )
        }
        val addresses = result.stdout.lineSequence().mapNotNull { line ->
            line.trim().split(' ', '\t').filter(String::isNotBlank).getOrNull(3)
        }.toList()
        val publiclyBound = addresses.any { address ->
            !address.startsWith("127.") && !address.startsWith("[::1]") && !address.startsWith("::1:")
        }
        if (result.exitCode != 0 || !publiclyBound) {
            throw ComputerException(
                ComputerErrorCodes.PUBLIC_PORT_BLOCKED,
                "VPS 主机服务没有监听公网地址",
                action = "CHECK_LISTEN_ADDRESS",
            )
        }
        return port
    }

    private suspend fun openContainerPublicPort(
        computer: Computer,
        workspaceId: String,
        previewId: String,
        remotePort: Int,
        expiresInSeconds: Long?,
    ): Int {
        val serverExpiry = expiresInSeconds?.coerceIn(60, 604_800) ?: 0
        val result = repository.withConnection(computer.id) { connection, _ ->
            connection.execute(
                command = "${helperPrefix(computer)} open-public $workspaceId $previewId $remotePort $serverExpiry",
                timeoutMillis = 60_000,
                maxOutputBytes = 64 * 1024,
            )
        }
        val publicPort = result.stdout.lineSequence()
            .firstOrNull { it.startsWith("public_port=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
        if (result.timedOut || result.exitCode != 0 || publicPort == null || publicPort !in 1..65_535) {
            throw ComputerException(ComputerErrorCodes.PUBLIC_PORT_BLOCKED, "无法创建 Public Preview", retryable = true)
        }
        return publicPort
    }

    private suspend fun requireWorkspace(context: ComputerRequestContext): ComputerWorkspace {
        val workspace = repository.dao().getWorkspaceById(context.workspaceId)?.toModel()
            ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
        if (!workspace.matchesRequestContext(context)) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 与当前请求不匹配")
        }
        return workspace
    }

    private fun validatePortAndProtocol(port: Int, protocol: String) {
        if (port !in 1..65_535 || protocol !in setOf("http", "https")) {
            throw ComputerException(ComputerErrorCodes.PUBLIC_PORT_BLOCKED, "Preview 参数无效")
        }
    }

    private fun helperPrefix(computer: Computer): String = if (computer.username == "root") {
        PREVIEW_HELPER
    } else {
        "sudo -n -- $PREVIEW_HELPER"
    }

    /** 旧 Direct 记录只可能访问 Host；统一模式下按 Tool 请求选择 Host 或 Container。 */
    private fun effectivePreviewTarget(computer: Computer, requested: ComputerExecTarget): ComputerExecTarget =
        if (computer.runMode == ComputerRunMode.DIRECT) ComputerExecTarget.HOST else requested

    private fun scheduleExpiry(preview: ComputerPreview) {
        val expiresAt = preview.expiresAt ?: return
        expiryJobs.remove(preview.id)?.cancel()
        expiryJobs[preview.id] = expiryScope.launch {
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            expiryJobs.remove(preview.id)
            runCatching { stopWithStatus(preview.id, ComputerPreviewStatus.EXPIRED) }
        }
    }

    private fun previewAuditEvent(status: ComputerPreviewStatus): String = when (status) {
        ComputerPreviewStatus.EXPIRED -> "PREVIEW_EXPIRED"
        ComputerPreviewStatus.STOPPED -> "PREVIEW_STOPPED"
        else -> "PREVIEW_REVOKED"
    }

    override fun close() {
        activePrivatePreviews.values.forEach { active ->
            active.forward.close()
            active.lease.close()
            active.foregroundActivity.close()
        }
        activePrivatePreviews.clear()
        expiryJobs.values.forEach(Job::cancel)
        expiryJobs.clear()
        expiryScope.cancel()
    }
}
