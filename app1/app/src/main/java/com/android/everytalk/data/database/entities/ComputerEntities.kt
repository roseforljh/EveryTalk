package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuditEvent
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerCapabilities
import com.android.everytalk.data.computer.ComputerCredentialState
import com.android.everytalk.data.computer.ComputerExecTarget
import com.android.everytalk.data.computer.ComputerExecution
import com.android.everytalk.data.computer.ComputerExecutionStatus
import com.android.everytalk.data.computer.ComputerPreview
import com.android.everytalk.data.computer.ComputerPreviewStatus
import com.android.everytalk.data.computer.ComputerPreviewVisibility
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.ComputerWorkspace
import com.android.everytalk.data.computer.ComputerWorkspaceStatus
import kotlinx.serialization.json.Json

@Entity(
    tableName = "computers",
    indices = [Index(value = ["status"])],
)
data class ComputerEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val resolvedAddress: String?,
    val hostKeyAlgorithm: String?,
    val hostKeyBlobBase64: String?,
    val hostKeyFingerprint: String?,
    val authKind: String,
    val credentialState: String,
    val runMode: String,
    val status: String,
    val capabilitiesJson: String?,
    val bootstrapVersion: String?,
    val sandboxImage: String?,
    val allowPrivateNetwork: Boolean,
    val permissionMode: String,
    val lastConnectedAt: Long?,
    val lastErrorCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "computer_workspaces",
    foreignKeys = [
        ForeignKey(
            entity = ComputerEntity::class,
            parentColumns = ["id"],
            childColumns = ["computerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["computerId"]),
        Index(value = ["computerId", "conversationId"], unique = true),
    ],
)
data class ComputerWorkspaceEntity(
    @PrimaryKey val id: String,
    val computerId: String,
    val conversationId: String,
    val runMode: String,
    val hostPath: String,
    val containerName: String?,
    val containerImage: String?,
    val status: String,
    val createdAt: Long,
    val lastUsedAt: Long,
)

@Entity(
    tableName = "conversation_computer_selections",
    foreignKeys = [
        ForeignKey(
            entity = ComputerEntity::class,
            parentColumns = ["id"],
            childColumns = ["selectedComputerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["selectedComputerId"])],
)
data class ConversationComputerSelectionEntity(
    @PrimaryKey val conversationId: String,
    val selectedComputerId: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "computer_executions",
    foreignKeys = [
        ForeignKey(
            entity = ComputerEntity::class,
            parentColumns = ["id"],
            childColumns = ["computerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ComputerWorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["computerId"]),
        Index(value = ["workspaceId"]),
        Index(value = ["toolCallId"], unique = true),
    ],
)
data class ComputerExecutionEntity(
    @PrimaryKey val id: String,
    val toolCallId: String,
    val computerId: String,
    val workspaceId: String,
    val toolName: String,
    val requestHash: String,
    val status: String,
    val startedAt: Long?,
    val finishedAt: Long?,
    val exitCode: Int?,
    val errorCode: String?,
    val safeSummary: String?,
)

@Entity(
    tableName = "computer_previews",
    foreignKeys = [
        ForeignKey(
            entity = ComputerWorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["workspaceId"])],
)
data class ComputerPreviewEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val remotePort: Int,
    @ColumnInfo(defaultValue = "'CONTAINER'") val target: String,
    val localPort: Int?,
    val publicPort: Int?,
    val protocol: String,
    val visibility: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long?,
)

@Entity(
    tableName = "workspace_secret_metadata",
    foreignKeys = [
        ForeignKey(
            entity = ComputerWorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["workspaceId"]),
        Index(value = ["workspaceId", "name"], unique = true),
    ],
)
data class WorkspaceSecretMetadataEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val name: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "computer_audit_events",
    foreignKeys = [
        ForeignKey(
            entity = ComputerEntity::class,
            parentColumns = ["id"],
            childColumns = ["computerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["computerId", "createdAt"])],
)
data class ComputerAuditEventEntity(
    @PrimaryKey val id: String,
    val computerId: String,
    val eventType: String,
    val outcome: String,
    val safeSummary: String?,
    val createdAt: Long,
)

fun ComputerEntity.toModel(json: Json): Computer = Computer(
    id = id,
    displayName = displayName,
    host = host,
    port = port,
    username = username,
    resolvedAddress = resolvedAddress,
    hostKeyAlgorithm = hostKeyAlgorithm,
    hostKeyBlobBase64 = hostKeyBlobBase64,
    hostKeyFingerprint = hostKeyFingerprint,
    authKind = enumValueOrDefault(authKind, ComputerAuthKind.PASSWORD),
    credentialState = enumValueOrDefault(credentialState, ComputerCredentialState.MISSING),
    runMode = enumValueOrDefault(runMode, ComputerRunMode.DIRECT),
    status = enumValueOrDefault(status, ComputerStatus.ERROR),
    capabilities = capabilitiesJson?.let {
        runCatching { json.decodeFromString<ComputerCapabilities>(it) }.getOrNull()
    },
    bootstrapVersion = bootstrapVersion,
    sandboxImage = sandboxImage,
    allowPrivateNetwork = allowPrivateNetwork,
    permissionMode = enumValueOrDefault(permissionMode, ComputerPermissionMode.MANUAL),
    lastConnectedAt = lastConnectedAt,
    lastErrorCode = lastErrorCode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Computer.toEntity(json: Json): ComputerEntity = ComputerEntity(
    id = id,
    displayName = displayName,
    host = host,
    port = port,
    username = username,
    resolvedAddress = resolvedAddress,
    hostKeyAlgorithm = hostKeyAlgorithm,
    hostKeyBlobBase64 = hostKeyBlobBase64,
    hostKeyFingerprint = hostKeyFingerprint,
    authKind = authKind.name,
    credentialState = credentialState.name,
    runMode = runMode.name,
    status = status.name,
    capabilitiesJson = capabilities?.let { json.encodeToString(it) },
    bootstrapVersion = bootstrapVersion,
    sandboxImage = sandboxImage,
    allowPrivateNetwork = allowPrivateNetwork,
    permissionMode = permissionMode.name,
    lastConnectedAt = lastConnectedAt,
    lastErrorCode = lastErrorCode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ComputerWorkspaceEntity.toModel(): ComputerWorkspace = ComputerWorkspace(
    id = id,
    computerId = computerId,
    conversationId = conversationId,
    runMode = enumValueOrDefault(runMode, ComputerRunMode.DIRECT),
    hostPath = hostPath,
    containerName = containerName,
    containerImage = containerImage,
    status = enumValueOrDefault(status, ComputerWorkspaceStatus.ERROR),
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
)

fun ComputerWorkspace.toEntity(): ComputerWorkspaceEntity = ComputerWorkspaceEntity(
    id = id,
    computerId = computerId,
    conversationId = conversationId,
    runMode = runMode.name,
    hostPath = hostPath,
    containerName = containerName,
    containerImage = containerImage,
    status = status.name,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
)

fun ComputerExecutionEntity.toModel(): ComputerExecution = ComputerExecution(
    id = id,
    toolCallId = toolCallId,
    computerId = computerId,
    workspaceId = workspaceId,
    toolName = toolName,
    requestHash = requestHash,
    status = enumValueOrDefault(status, ComputerExecutionStatus.UNKNOWN),
    startedAt = startedAt,
    finishedAt = finishedAt,
    exitCode = exitCode,
    errorCode = errorCode,
    safeSummary = safeSummary,
)

fun ComputerExecution.toEntity(): ComputerExecutionEntity = ComputerExecutionEntity(
    id = id,
    toolCallId = toolCallId,
    computerId = computerId,
    workspaceId = workspaceId,
    toolName = toolName,
    requestHash = requestHash,
    status = status.name,
    startedAt = startedAt,
    finishedAt = finishedAt,
    exitCode = exitCode,
    errorCode = errorCode,
    safeSummary = safeSummary,
)

fun ComputerPreviewEntity.toModel(): ComputerPreview = ComputerPreview(
    id = id,
    workspaceId = workspaceId,
    remotePort = remotePort,
    target = enumValueOrDefault(target, ComputerExecTarget.CONTAINER),
    localPort = localPort,
    publicPort = publicPort,
    protocol = protocol,
    visibility = enumValueOrDefault(visibility, ComputerPreviewVisibility.PRIVATE),
    status = enumValueOrDefault(status, ComputerPreviewStatus.ERROR),
    createdAt = createdAt,
    expiresAt = expiresAt,
)

fun ComputerPreview.toEntity(): ComputerPreviewEntity = ComputerPreviewEntity(
    id = id,
    workspaceId = workspaceId,
    remotePort = remotePort,
    target = target.name,
    localPort = localPort,
    publicPort = publicPort,
    protocol = protocol,
    visibility = visibility.name,
    status = status.name,
    createdAt = createdAt,
    expiresAt = expiresAt,
)

fun ComputerAuditEventEntity.toModel(): ComputerAuditEvent = ComputerAuditEvent(
    id = id,
    computerId = computerId,
    eventType = eventType,
    outcome = outcome,
    safeSummary = safeSummary,
    createdAt = createdAt,
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
