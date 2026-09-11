package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.TemporaryWorkerDeploymentEntity
import kotlinx.serialization.Serializable

/** 临时 Worker 的本地状态；它不是 Computer，不参与会话目标选择。 */
@Serializable
enum class TemporaryWorkerStatus { CREATING, ACTIVE, EXPIRED, CLAIM_PENDING, CLAIMED, FAILED }

@Serializable
data class TemporaryWorkerDeployment(
    val temporaryDeploymentId: String,
    val workerUrl: String? = null,
    val claimUrl: String? = null,
    val expiresAt: Long,
    val claimStatus: TemporaryWorkerStatus,
    val sourceWorkspaceId: String,
)

/** 仅负责状态迁移，云端创建/Claim 由调用方通过接口实现。 */
class TemporaryWorkerStateMachine {
    fun expire(deployment: TemporaryWorkerDeployment, now: Long = System.currentTimeMillis()): TemporaryWorkerDeployment =
        if (deployment.claimStatus in setOf(TemporaryWorkerStatus.ACTIVE, TemporaryWorkerStatus.CLAIM_PENDING) && deployment.expiresAt <= now) {
            deployment.copy(claimStatus = TemporaryWorkerStatus.EXPIRED)
        } else deployment

    fun beginClaim(deployment: TemporaryWorkerDeployment, now: Long = System.currentTimeMillis()): TemporaryWorkerDeployment {
        require(deployment.claimStatus == TemporaryWorkerStatus.ACTIVE) { "临时 Worker 当前不可 Claim" }
        require(deployment.expiresAt > now) { "临时 Worker 已过期" }
        return deployment.copy(claimStatus = TemporaryWorkerStatus.CLAIM_PENDING)
    }

    fun completeClaim(deployment: TemporaryWorkerDeployment): TemporaryWorkerDeployment {
        require(deployment.claimStatus == TemporaryWorkerStatus.CLAIM_PENDING) { "临时 Worker 不在 Claim 状态" }
        return deployment.copy(claimStatus = TemporaryWorkerStatus.CLAIMED)
    }

    fun cancelClaim(deployment: TemporaryWorkerDeployment, now: Long = System.currentTimeMillis()): TemporaryWorkerDeployment {
        require(deployment.claimStatus == TemporaryWorkerStatus.CLAIM_PENDING) { "临时 Worker 不在 Claim 状态" }
        return if (deployment.expiresAt <= now) {
            deployment.copy(claimStatus = TemporaryWorkerStatus.EXPIRED)
        } else {
            deployment.copy(claimStatus = TemporaryWorkerStatus.ACTIVE)
        }
    }
}

/** Temporary Worker 云端操作的唯一边界；具体 Cloudflare 托管方式由实现方注入。 */
interface TemporaryWorkerGateway {
    suspend fun create(sourceWorkspaceId: String, packageData: WorkerPackage): TemporaryWorkerGatewayResult
    suspend fun claim(temporaryDeploymentId: String): Boolean
}

/**
 * 默认网关明确拒绝执行。Cloudflare 没有公开“无账号匿名创建 Worker”的接口，
 * 因此没有配置真实外部 Gateway 时必须失败并保持开关关闭，不能伪造 URL 或部署 ID。
 */
object UnavailableTemporaryWorkerGateway : TemporaryWorkerGateway {
    override suspend fun create(sourceWorkspaceId: String, packageData: WorkerPackage): TemporaryWorkerGatewayResult =
        throw CloudflareApiException("FEATURE_NOT_CONFIGURED", "Temporary Worker Gateway 未配置")

    override suspend fun claim(temporaryDeploymentId: String): Boolean =
        throw CloudflareApiException("FEATURE_NOT_CONFIGURED", "Temporary Worker Gateway 未配置")
}

data class TemporaryWorkerGatewayResult(
    val temporaryDeploymentId: String,
    val workerUrl: String,
    val claimUrl: String,
    val expiresAt: Long,
)

/** Temporary Worker 的完整协调器，状态先落本地，云端异常保留可恢复状态。 */
class TemporaryWorkerCoordinator(
    private val store: TemporaryWorkerStore,
    private val gateway: TemporaryWorkerGateway,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun create(sourceWorkspaceId: String, packageData: WorkerPackage): TemporaryWorkerDeployment {
        check(enabled()) { "Temporary Worker 当前未开启" }
        require(sourceWorkspaceId.isNotBlank()) { "Workspace 无效" }
        val localId = "temporary-${java.util.UUID.randomUUID()}"
        val creating = TemporaryWorkerDeployment(localId, expiresAt = Long.MAX_VALUE, claimStatus = TemporaryWorkerStatus.CREATING, sourceWorkspaceId = sourceWorkspaceId)
        store.save(creating)
        return try {
            val result = gateway.create(sourceWorkspaceId, packageData)
            val active = creating.copy(
                temporaryDeploymentId = result.temporaryDeploymentId,
                workerUrl = result.workerUrl,
                claimUrl = result.claimUrl,
                expiresAt = result.expiresAt,
                claimStatus = TemporaryWorkerStatus.ACTIVE,
            )
            store.save(active)
            // 先保存远端 ID，再删除本地占位；进程在两步之间退出时最多留下可清理的
            // CREATING 占位，不会丢失已经返回的临时部署。
            if (active.temporaryDeploymentId != creating.temporaryDeploymentId) store.delete(creating.temporaryDeploymentId)
            active
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failed = creating.copy(claimStatus = TemporaryWorkerStatus.FAILED)
            store.save(failed)
            throw error
        }
    }

    suspend fun beginClaim(id: String): TemporaryWorkerDeployment {
        check(enabled()) { "Temporary Worker 当前未开启" }
        val current = store.get(id) ?: throw IllegalArgumentException("临时 Worker 不存在")
        val next = TemporaryWorkerStateMachine().beginClaim(TemporaryWorkerStateMachine().expire(current, clock()), clock())
        store.save(next)
        return next
    }

    suspend fun completeClaim(id: String): TemporaryWorkerDeployment {
        check(enabled()) { "Temporary Worker 当前未开启" }
        val current = store.get(id) ?: throw IllegalArgumentException("临时 Worker 不存在")
        require(current.claimStatus == TemporaryWorkerStatus.CLAIM_PENDING) { "临时 Worker 不在 Claim 状态" }
        if (current.expiresAt <= clock()) {
            val expired = current.copy(claimStatus = TemporaryWorkerStatus.EXPIRED)
            store.save(expired)
            throw CloudflareApiException("TEMPORARY_WORKER_EXPIRED", "Temporary Worker 已过期，请重新创建")
        }
        return try {
            check(gateway.claim(id)) { "Cloudflare Claim 未完成" }
            TemporaryWorkerStateMachine().completeClaim(current).also { store.save(it) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Claim 失败回到 ACTIVE，允许用户修复登录或重试，不丢失临时部署。
            TemporaryWorkerStateMachine().cancelClaim(current, clock()).also { store.save(it) }
            throw error
        }
    }

    suspend fun cancelClaim(id: String): TemporaryWorkerDeployment {
        val current = store.get(id) ?: throw IllegalArgumentException("临时 Worker 不存在")
        return TemporaryWorkerStateMachine().cancelClaim(current, clock()).also { store.save(it) }
    }
}

/** 临时 Worker 的持久化协调器，保证进程重启后仍可继续过期检查和 Claim。 */
class TemporaryWorkerStore(private val dao: ComputerDao) {
    suspend fun save(deployment: TemporaryWorkerDeployment) = dao.upsertTemporaryWorker(deployment.toEntity())
    suspend fun get(id: String): TemporaryWorkerDeployment? = dao.getTemporaryWorker(id)?.toModel()
    suspend fun delete(id: String) = dao.deleteTemporaryWorker(id)
    suspend fun all(): List<TemporaryWorkerDeployment> {
        val machine = TemporaryWorkerStateMachine()
        return dao.getTemporaryWorkers().map { entity ->
            val deployment = entity.toModel()
            val expired = machine.expire(deployment, System.currentTimeMillis())
            if (expired.claimStatus == TemporaryWorkerStatus.EXPIRED && deployment.claimStatus != expired.claimStatus) {
                dao.markTemporaryWorkerExpired(deployment.temporaryDeploymentId, System.currentTimeMillis())
            }
            expired
        }
    }
    suspend fun active(now: Long = System.currentTimeMillis()): List<TemporaryWorkerDeployment> {
        val machine = TemporaryWorkerStateMachine()
        return dao.getActiveTemporaryWorkers().map { entity ->
            val deployment = entity.toModel()
            val expired = machine.expire(deployment, now)
            if (expired.claimStatus == TemporaryWorkerStatus.EXPIRED) {
                dao.markTemporaryWorkerExpired(deployment.temporaryDeploymentId, now)
            }
            expired
        }
    }
}

private fun TemporaryWorkerDeployment.toEntity() = TemporaryWorkerDeploymentEntity(
    temporaryDeploymentId, workerUrl, claimUrl, expiresAt, claimStatus.name, sourceWorkspaceId,
)

private fun TemporaryWorkerDeploymentEntity.toModel() = TemporaryWorkerDeployment(
    temporaryDeploymentId, workerUrl, claimUrl, expiresAt,
    runCatching { TemporaryWorkerStatus.valueOf(claimStatus) }.getOrDefault(TemporaryWorkerStatus.FAILED),
    sourceWorkspaceId,
)
