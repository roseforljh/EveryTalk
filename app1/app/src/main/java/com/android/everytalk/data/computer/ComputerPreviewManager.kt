package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
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

data class ComputerPublicPreviewRequest(
    val context: ComputerRequestContext,
    val port: Int,
    val protocol: String,
    val expiresInSeconds: Long?,
)

/** Private Preview 持有 SSH lease；Public Preview 只有显式确认后才创建。 */
class ComputerPreviewManager(private val repository: ComputerRepository) : Closeable {
    private data class ActivePrivatePreview(
        val lease: ComputerConnectionLease,
        val forward: ComputerPortForward,
        val foregroundActivity: Closeable,
    )

    private val activePrivatePreviews = ConcurrentHashMap<String, ActivePrivatePreview>()

    suspend fun openPrivate(
        context: ComputerRequestContext,
        port: Int,
        protocol: String = "http",
    ): ComputerPreviewOpenResult {
        validatePortAndProtocol(port, protocol)
        val workspace = requireWorkspace(context)
        val foregroundActivity = repository.acquireForegroundActivity()
        val (lease, computer) = try {
            repository.acquireConnection(context.computerId)
        } catch (error: Throwable) {
            foregroundActivity.close()
            throw error
        }
        try {
            val remoteHost = when (computer.runMode) {
                ComputerRunMode.DIRECT -> "127.0.0.1"
                ComputerRunMode.CONTAINER -> resolveContainerAddress(lease.connection, computer, workspace.id)
            }
            val forward = lease.connection.openLocalPortForward(port, remoteHost)
            val preview = ComputerPreview(
                workspaceId = workspace.id,
                remotePort = port,
                localPort = forward.localPort,
                protocol = protocol,
                visibility = ComputerPreviewVisibility.PRIVATE,
            )
            repository.dao().upsertPreview(preview.toEntity())
            activePrivatePreviews[preview.id] = ActivePrivatePreview(lease, forward, foregroundActivity)
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
        val publicPort = when (computer.runMode) {
            ComputerRunMode.DIRECT -> verifyDirectPublicPort(computer, request.port)
            ComputerRunMode.CONTAINER -> openContainerPublicPort(computer, workspace.id, previewId, request.port)
        }
        val now = System.currentTimeMillis()
        val preview = ComputerPreview(
            id = previewId,
            workspaceId = workspace.id,
            remotePort = request.port,
            publicPort = publicPort,
            protocol = request.protocol,
            visibility = ComputerPreviewVisibility.PUBLIC,
            createdAt = now,
            expiresAt = request.expiresInSeconds?.let { now + it.coerceIn(60, 604_800) * 1000 },
        )
        repository.dao().upsertPreview(preview.toEntity())
        val host = if (':' in computer.host) "[${computer.host}]" else computer.host
        return ComputerPreviewOpenResult(
            preview = preview,
            url = "${request.protocol}://$host:$publicPort",
            warning = "云厂商安全组或 VPS 防火墙可能仍会阻止公网访问",
        )
    }

    suspend fun stop(previewId: String) {
        val entity = repository.dao().getPreview(previewId) ?: return
        val preview = entity.toModel()
        activePrivatePreviews.remove(previewId)?.let { active ->
            active.forward.close()
            active.lease.close()
            active.foregroundActivity.close()
        }
        if (preview.visibility == ComputerPreviewVisibility.PUBLIC) {
            val workspace = repository.dao().getWorkspaceById(preview.workspaceId)?.toModel()
            if (workspace != null) {
                val computer = repository.getComputer(workspace.computerId)
                if (computer?.runMode == ComputerRunMode.CONTAINER) {
                    runCatching {
                        repository.withConnection(computer.id) { connection, _ ->
                            connection.execute(
                                command = "${helperPrefix(computer)} close-public $previewId",
                                timeoutMillis = 30_000,
                                maxOutputBytes = 64 * 1024,
                            )
                        }
                    }
                }
            }
        }
        repository.dao().upsertPreview(preview.copy(status = ComputerPreviewStatus.REVOKED).toEntity())
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

    private suspend fun verifyDirectPublicPort(computer: Computer, port: Int): Int {
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
                "Direct 服务没有监听公网地址",
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
    ): Int {
        val result = repository.withConnection(computer.id) { connection, _ ->
            connection.execute(
                command = "${helperPrefix(computer)} open-public $workspaceId $previewId $remotePort",
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
        if (
            workspace.computerId != context.computerId ||
            workspace.conversationId != context.conversationId ||
            workspace.status != ComputerWorkspaceStatus.READY
        ) {
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

    override fun close() {
        activePrivatePreviews.values.forEach { active ->
            active.forward.close()
            active.lease.close()
            active.foregroundActivity.close()
        }
        activePrivatePreviews.clear()
    }
}
