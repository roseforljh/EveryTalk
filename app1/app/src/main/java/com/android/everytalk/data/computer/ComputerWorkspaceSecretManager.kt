package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.WorkspaceSecretMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class ComputerWorkspaceSecret(
    val id: String,
    val workspaceId: String,
    val name: String,
    val updatedAt: Long,
)

/** Secret 值只进入 Keystore 加密文件；Room 和 UI 只保存名称与更新时间。 */
class ComputerWorkspaceSecretManager(private val repository: ComputerRepository) {
    fun observe(workspaceId: String): Flow<List<ComputerWorkspaceSecret>> =
        repository.dao().observeWorkspaceSecrets(workspaceId).map { entities ->
            entities.map { it.toSecretModel() }
        }

    suspend fun save(workspaceId: String, name: String, value: CharArray): ComputerWorkspaceSecret {
        ComputerEnvironmentName.requireValid(name)
        if (value.isEmpty()) {
            value.fill('\u0000')
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Secret 值不能为空")
        }
        val workspace = repository.dao().getWorkspaceById(workspaceId)
            ?: run {
                value.fill('\u0000')
                throw ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "Workspace 不存在")
            }
        val existing = repository.dao().getWorkspaceSecret(workspaceId, name)
        val metadata = WorkspaceSecretMetadataEntity(
            id = existing?.id ?: "secret_${UUID.randomUUID().toString().replace("-", "")}",
            workspaceId = workspace.id,
            name = name,
            updatedAt = System.currentTimeMillis(),
        )
        repository.credentialStore().saveWorkspaceSecret(metadata.id, value)
        try {
            repository.dao().upsertWorkspaceSecretMetadata(metadata)
        } catch (error: Throwable) {
            repository.credentialStore().deleteWorkspaceSecret(metadata.id)
            throw error
        }
        return metadata.toSecretModel()
    }

    suspend fun loadSelected(workspaceId: String, names: Collection<String>): Map<String, CharArray> {
        if (names.isEmpty()) return emptyMap()
        val distinctNames = names.distinct()
        if (distinctNames.size != names.size || distinctNames.size > 64) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Secret 名称列表无效")
        }
        val result = linkedMapOf<String, CharArray>()
        try {
            distinctNames.forEach { name ->
                ComputerEnvironmentName.requireValid(name)
                val metadata = repository.dao().getWorkspaceSecret(workspaceId, name)
                    ?: throw ComputerException(ComputerErrorCodes.CREDENTIAL_MISSING, "Workspace Secret 不存在")
                result[name] = repository.credentialStore().loadWorkspaceSecret(metadata.id)
            }
            return result
        } catch (error: Throwable) {
            result.values.forEach { it.fill('\u0000') }
            throw error
        }
    }

    suspend fun delete(workspaceId: String, name: String) {
        val metadata = repository.dao().getWorkspaceSecret(workspaceId, name) ?: return
        repository.credentialStore().deleteWorkspaceSecret(metadata.id)
        repository.dao().deleteWorkspaceSecretMetadata(metadata.id)
    }

    suspend fun deleteAll(workspaceId: String) {
        repository.dao().getWorkspaceSecrets(workspaceId).forEach { metadata ->
            repository.credentialStore().deleteWorkspaceSecret(metadata.id)
            repository.dao().deleteWorkspaceSecretMetadata(metadata.id)
        }
    }

    private fun WorkspaceSecretMetadataEntity.toSecretModel(): ComputerWorkspaceSecret = ComputerWorkspaceSecret(
        id = id,
        workspaceId = workspaceId,
        name = name,
        updatedAt = updatedAt,
    )
}
