package com.android.everytalk.data.computer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
enum class ComputerStatus {
    DRAFT,
    RESOLVING_HOST,
    HOST_KEY_PENDING,
    AUTHENTICATING,
    PROBING,
    CONFIGURATION_REQUIRED,
    PROVISIONING,
    VERIFYING,
    READY,
    OFFLINE,
    HOST_KEY_CHANGED,
    ACTION_REQUIRED,
    ERROR,
    DISCONNECTED,
    DELETING,
    DELETED,
}

@Serializable
enum class ComputerRunMode { CONTAINER, DIRECT }

/**
 * Agent 操作这台服务器时采用的确认方式。
 * 该设置跟随服务器保存，切换会话或模型时保持一致。
 */
@Serializable
enum class ComputerPermissionMode {
    MANUAL,
    SMART,
    FULL,
}

/**
 * 首次添加服务器时对用户展示的真实处理阶段。
 *
 * 枚举顺序就是界面上的步骤顺序，新增或调整阶段时必须同时更新中英文文案。
 */
enum class ComputerSetupStage {
    READING_HOST_KEY,
    AUTHENTICATING,
    INSPECTING_VPS,
    SECURING_CONNECTION,
    PREPARING_CONTAINER,
    PREPARING_DOCKER,
    INSTALLING_HELPER,
    BUILDING_IMAGE,
    CONFIGURING_NETWORK,
    VERIFYING,
}

/** 单次 exec 的执行位置。服务器连接始终使用 SSH，代码任务默认进入 Container。 */
@Serializable
enum class ComputerExecTarget { CONTAINER, HOST }

@Serializable
enum class ComputerAuthKind { PASSWORD, PRIVATE_KEY }

@Serializable
enum class ComputerCredentialState { ORIGINAL_ENCRYPTED, DEDICATED_KEY, MISSING }

@Serializable
enum class ComputerWorkspaceStatus { CREATING, READY, STOPPED, RECOVERING, ERROR, DELETING, DELETED }

@Serializable
enum class ComputerExecutionStatus {
    QUEUED,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN,
}

@Serializable
enum class ComputerPreviewVisibility { PRIVATE, PUBLIC }

@Serializable
enum class ComputerPreviewStatus { ACTIVE, STOPPED, REVOKED, EXPIRED, ERROR }

/**
 * SSH 登录凭据只在添加、连接或轮换流程中短暂存在。
 * 调用 clear 后会覆盖字符数组，禁止把该对象写入日志或 Room。
 */
sealed interface ComputerCredential {
    val kind: ComputerAuthKind
    fun clear()

    class Password(val password: CharArray) : ComputerCredential {
        override val kind: ComputerAuthKind = ComputerAuthKind.PASSWORD
        override fun clear() = password.fill('\u0000')
    }

    class PrivateKey(
        val privateKey: CharArray,
        val passphrase: CharArray? = null,
    ) : ComputerCredential {
        override val kind: ComputerAuthKind = ComputerAuthKind.PRIVATE_KEY
        override fun clear() {
            privateKey.fill('\u0000')
            passphrase?.fill('\u0000')
        }
    }
}

/** 复制一份可独立清零的凭据，供测试连接与多份本地加密存储使用。 */
internal fun ComputerCredential.copySecret(): ComputerCredential = when (this) {
    is ComputerCredential.Password -> ComputerCredential.Password(password.copyOf())
    is ComputerCredential.PrivateKey -> ComputerCredential.PrivateKey(
        privateKey = privateKey.copyOf(),
        passphrase = passphrase?.copyOf(),
    )
}

/** 优先使用单独保存的 sudo 密码；未填写时，密码登录用户沿用其 SSH 密码。 */
internal fun resolveComputerProvisionPassword(
    savedSudoPassword: CharArray?,
    originalCredential: ComputerCredential?,
): CharArray? {
    if (savedSudoPassword != null) {
        originalCredential?.clear()
        return savedSudoPassword
    }
    return when (originalCredential) {
        is ComputerCredential.Password -> originalCredential.password
        is ComputerCredential.PrivateKey -> {
            originalCredential.clear()
            null
        }
        null -> null
    }
}

@Serializable
data class ComputerCapabilities(
    val osId: String = "",
    val osVersion: String = "",
    val kernel: String = "",
    val architecture: String = "",
    val remoteUser: String = "",
    val shell: String = "",
    val cpuCount: Int? = null,
    val memoryBytes: Long? = null,
    val diskAvailableBytes: Long? = null,
    val loadAverage: String? = null,
    val dockerAvailable: Boolean = false,
    val sudoAvailable: Boolean = false,
    val sftpAvailable: Boolean = false,
    val ptyAvailable: Boolean = false,
    val portForwardAvailable: Boolean = false,
    val containerSandboxAvailable: Boolean = false,
)

@Serializable
data class Computer(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val resolvedAddress: String? = null,
    val hostKeyAlgorithm: String? = null,
    val hostKeyBlobBase64: String? = null,
    val hostKeyFingerprint: String? = null,
    val authKind: ComputerAuthKind,
    val credentialState: ComputerCredentialState = ComputerCredentialState.MISSING,
    val runMode: ComputerRunMode,
    val status: ComputerStatus = ComputerStatus.DRAFT,
    val capabilities: ComputerCapabilities? = null,
    val bootstrapVersion: String? = null,
    val sandboxImage: String? = null,
    val allowPrivateNetwork: Boolean = false,
    val permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
    val lastConnectedAt: Long? = null,
    val lastErrorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ComputerWorkspace(
    val id: String,
    val computerId: String,
    val conversationId: String,
    val runMode: ComputerRunMode,
    val hostPath: String,
    val containerName: String? = null,
    val containerImage: String? = null,
    val status: ComputerWorkspaceStatus = ComputerWorkspaceStatus.CREATING,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/** 旧 Direct 记录只迁移本地路由字段，Host、凭据和远端文件位置保持原值。 */
internal fun migrateLegacyDirectComputer(computer: Computer): Computer =
    if (computer.runMode == ComputerRunMode.DIRECT) {
        computer.copy(
            runMode = ComputerRunMode.CONTAINER,
            status = ComputerStatus.CONFIGURATION_REQUIRED,
            updatedAt = System.currentTimeMillis(),
        )
    } else {
        computer
    }

/** 原 Host Workspace 继续作为 Container 的 bind mount，迁移过程中不移动或删除文件。 */
internal fun migrateLegacyDirectWorkspace(
    workspace: ComputerWorkspace,
    containerImage: String?,
): ComputerWorkspace = if (workspace.runMode == ComputerRunMode.DIRECT) {
    workspace.copy(
        runMode = ComputerRunMode.CONTAINER,
        containerName = "everytalk-${workspace.id}",
        containerImage = containerImage,
        status = ComputerWorkspaceStatus.RECOVERING,
    )
} else {
    workspace
}

@Serializable
data class ComputerExecution(
    val id: String = "execution_${UUID.randomUUID()}",
    val toolCallId: String,
    val computerId: String,
    val workspaceId: String,
    val toolName: String,
    val requestHash: String,
    val status: ComputerExecutionStatus,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val exitCode: Int? = null,
    val errorCode: String? = null,
    val safeSummary: String? = null,
)

@Serializable
data class ComputerPreview(
    val id: String = "preview_${UUID.randomUUID()}",
    val workspaceId: String,
    val remotePort: Int,
    val target: ComputerExecTarget = ComputerExecTarget.CONTAINER,
    val localPort: Int? = null,
    val publicPort: Int? = null,
    val protocol: String = "http",
    val visibility: ComputerPreviewVisibility = ComputerPreviewVisibility.PRIVATE,
    val status: ComputerPreviewStatus = ComputerPreviewStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
)

/**
 * 服务器详情页展示的本地安全审计记录。
 * safeSummary 只能保存经过筛选的短说明，禁止放入 Host、用户名、命令、输出或 Secret 值。
 */
data class ComputerAuditEvent(
    val id: String,
    val computerId: String,
    val eventType: String,
    val outcome: String,
    val safeSummary: String? = null,
    val createdAt: Long,
)

/** 删除服务器后的远端清理结果。无论远端是否在线，本地删除都可以完成。 */
data class ComputerDeleteResult(
    val remoteKeyRemoved: Boolean,
    val remoteWorkspaceCleanupSucceeded: Boolean = true,
)

data class HostKeyProbeResult(
    val host: String,
    val resolvedAddress: String,
    val port: Int,
    val algorithm: String,
    val keyBlob: ByteArray,
    val fingerprint: String,
)

data class AddComputerRequest(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val credential: ComputerCredential,
    val runMode: ComputerRunMode,
)

/**
 * 编辑服务器时的候选参数。
 * credential 为 null 表示沿用当前登录凭据，避免为了修改名称而要求用户再次输入 Secret。
 */
data class UpdateComputerRequest(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val credential: ComputerCredential?,
)

@Serializable
data class ComputerRequestContext(
    val conversationId: String,
    val computerId: String,
    val workspaceId: String,
    val permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
    /** 只在当前工具调用内有效，禁止写进请求快照。 */
    @Transient val approvedToolCallId: String? = null,
    /** 用户明确选择重试 UNKNOWN 工具后才设置，禁止写进请求快照。 */
    @Transient val retryUnknownToolCallId: String? = null,
)

/**
 * 校验冻结请求仍指向同一服务器和 Workspace。
 * 首条消息保存时会把临时会话 ID 迁移为稳定 ID，Workspace ID 在迁移前后保持不变，
 * 因此这里不能用可变的 conversationId 拒绝仍在执行中的模型请求。
 */
internal fun ComputerWorkspace.matchesRequestContext(context: ComputerRequestContext): Boolean =
    id == context.workspaceId &&
        computerId == context.computerId &&
        status == ComputerWorkspaceStatus.READY

/** 一次模型请求启动时冻结的本地 Computer 信息，禁止序列化或发送给模型服务。 */
data class PreparedComputerRequest(
    val context: ComputerRequestContext,
    val environmentPrompt: String,
    val permissionMode: ComputerPermissionMode,
)

@Serializable
data class ComputerToolError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val action: String? = null,
)

@Serializable
data class ComputerToolResult<T>(
    val ok: Boolean,
    @SerialName("execution_id") val executionId: String,
    val data: T? = null,
    val error: ComputerToolError? = null,
)

/** 稳定错误码由 UI 本地化，异常文本只用于当前操作说明。 */
class ComputerException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val action: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

object ComputerErrorCodes {
    const val HOST_INVALID = "HOST_INVALID"
    const val HOST_RESOLUTION_FAILED = "HOST_RESOLUTION_FAILED"
    const val SSH_TIMEOUT = "SSH_TIMEOUT"
    const val HOST_KEY_CHANGED = "HOST_KEY_CHANGED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val PRIVATE_KEY_INVALID = "PRIVATE_KEY_INVALID"
    const val KEYSTORE_UNAVAILABLE = "KEYSTORE_UNAVAILABLE"
    const val CREDENTIAL_MISSING = "CREDENTIAL_MISSING"
    const val SUDO_REQUIRED = "SUDO_REQUIRED"
    const val UNSUPPORTED_OS = "UNSUPPORTED_OS"
    const val DOCKER_INSTALL_FAILED = "DOCKER_INSTALL_FAILED"
    const val HELPER_INTEGRITY_FAILED = "HELPER_INTEGRITY_FAILED"
    const val SERVER_NOT_SELECTED = "SERVER_NOT_SELECTED"
    const val COMPUTER_NOT_READY = "COMPUTER_NOT_READY"
    const val MODEL_TOOL_CALL_UNSUPPORTED = "MODEL_TOOL_CALL_UNSUPPORTED"
    const val WORKSPACE_PATH_INVALID = "WORKSPACE_PATH_INVALID"
    const val WORKSPACE_NOT_READY = "WORKSPACE_NOT_READY"
    const val EXECUTION_UNKNOWN = "EXECUTION_UNKNOWN"
    const val TERMINAL_LOST = "TERMINAL_LOST"
    const val UPLOAD_INTERRUPTED = "UPLOAD_INTERRUPTED"
    const val DOWNLOAD_INTERRUPTED = "DOWNLOAD_INTERRUPTED"
    const val PREVIEW_FORWARD_LOST = "PREVIEW_FORWARD_LOST"
    const val PUBLIC_PORT_BLOCKED = "PUBLIC_PORT_BLOCKED"
    const val HOST_COMMAND_REJECTED = "HOST_COMMAND_REJECTED"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val TOOL_NAME_CONFLICT = "TOOL_NAME_CONFLICT"
    const val VPS_DISK_FULL = "VPS_DISK_FULL"
    const val VPS_PROCESS_OOM = "VPS_PROCESS_OOM"
}
