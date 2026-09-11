package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareDeploymentEntity
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Worker 部署协调器：先落账本，再发请求，异常时保留 UNKNOWN 供后续对账。 */
class WorkerDeploymentManager(
    private val dao: ComputerDao,
    private val api: CloudflareApiClient,
    private val packageBuilder: WorkerPackageBuilder = WorkerPackageBuilder(),
) : AutoCloseable {
    suspend fun deploy(computerId: String, accountId: String, workerName: String, workspace: File): CloudflareDeploymentResult {
        return deployPackage(computerId, accountId, workerName, packageBuilder.build(workspace))
    }

    /** 上传审批时校验的同一份内存包，避免 hash 检查后重新读取被替换的文件。 */
    suspend fun deployPackage(computerId: String, accountId: String, workerName: String, pack: WorkerPackage): CloudflareDeploymentResult {
        require(computerId.isNotBlank() && accountId.isNotBlank()) { "Cloudflare 目标无效" }
        require(pack.files.any { it.relativePath == pack.entryPoint }) { "Worker 入口文件不存在" }
        val requestHash = sha256("$computerId\n$accountId\n$workerName\n${pack.requestHash}".toByteArray())
        dao.getCloudflareDeploymentByHash(requestHash)?.let { existing ->
            return CloudflareDeploymentResult(workerName, existing.remoteDeploymentId, recoveredStatus(existing.status), existing.versionId, null)
        }
        val localId = "deployment-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val pending = CloudflareDeploymentEntity(localId, computerId, accountId, workerName, requestHash, CloudflareDeploymentStatus.REQUEST_NOT_SENT.name, now, now, "${pack.files.size} files; ${pack.totalBytes} bytes")
        if (dao.insertCloudflareDeploymentIfAbsent(pending) == -1L) {
            val existing = dao.getCloudflareDeploymentByHash(requestHash)
                ?: error("部署占位状态无法读取")
            return CloudflareDeploymentResult(workerName, existing.remoteDeploymentId, recoveredStatus(existing.status), existing.versionId, null)
        }
        var known = pending
        suspend fun save(status: CloudflareDeploymentStatus, summary: String) {
            known = known.copy(status = status.name, updatedAt = System.currentTimeMillis(), safeSummary = summary)
            dao.upsertCloudflareDeployment(known)
        }
        return try {
            val submitted = api.deployModuleWorker(accountId, workerName, pack) { versionId ->
                // 将已知远端事实写入后才发送下一步请求；取消不能清空这些事实。
                known = known.copy(versionId = versionId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    save(CloudflareDeploymentStatus.DEPLOYMENT_PENDING, "版本已上传，等待 deployment 确认")
                }
            }
            known = known.copy(remoteDeploymentId = submitted.deploymentId, versionId = submitted.versionId ?: known.versionId)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                save(CloudflareDeploymentStatus.REQUEST_ACCEPTED, "Cloudflare 已接受部署，等待状态核验")
            }
            val deploymentId = known.remoteDeploymentId
                ?: throw CloudflareApiException("RESULT_UNKNOWN", "Cloudflare 未返回 deployment ID", versionId = known.versionId)
            val finalStatus = try {
                api.workerDeploymentStatus(accountId, workerName, deploymentId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                CloudflareDeploymentStatus.RESULT_UNKNOWN
            }
            val savedStatus = if (finalStatus == CloudflareDeploymentStatus.REQUEST_ACCEPTED) {
                CloudflareDeploymentStatus.DEPLOYMENT_PENDING
            } else finalStatus
            save(savedStatus, "Worker deployment 状态：${savedStatus.name}")
            submitted.copy(status = savedStatus)
        } catch (error: Exception) {
            val cloudflareError = error as? CloudflareApiException
            known = known.copy(versionId = known.versionId ?: cloudflareError?.versionId)
            val status = if (known.remoteDeploymentId != null || error is kotlinx.coroutines.CancellationException) {
                CloudflareDeploymentStatus.RESULT_UNKNOWN
            } else failureStatus(cloudflareError)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // 持久化失败时保留之前的占位或已接受记录；恢复会统一按未知处理。
                runCatching { save(status, if (status == CloudflareDeploymentStatus.RESULT_UNKNOWN) "部署结果需要查询确认" else "部署请求已明确失败") }
            }
            throw error
        }
    }

    /** 单文件源码和 Workspace 使用同一部署账本及恢复流程。 */
    suspend fun deployScript(computerId: String, accountId: String, workerName: String, script: String): CloudflareDeploymentResult {
        require(script.isNotBlank()) { "Worker 源码为空" }
        val bytes = script.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2 * 1024 * 1024 && !script.contains("-----BEGIN") && script.contains("export")) { "Worker 内容无效或包含敏感材料" }
        val pack = WorkerPackage(listOf(WorkerPackageFile("worker.js", bytes, sha256(bytes))), sha256(bytes), bytes.size.toLong())
        return deployPackage(computerId, accountId, workerName, pack)
    }

    /** 返回本地账本中的 UNKNOWN 记录，供恢复流程先查询而不是重复提交。 */
    suspend fun unknownDeployments(): List<CloudflareDeploymentEntity> =
        recoverableStatuses.flatMap { dao.getCloudflareDeploymentsByStatus(it.name) }

    /**
     * 对账一个 UNKNOWN 部署。查询函数只返回已确认的终态；查询失败继续保留 UNKNOWN，
     * 调用方因此不会因为网络抖动重复提交 Worker。
     */
    suspend fun reconcileUnknown(
        deployment: CloudflareDeploymentEntity,
        query: suspend (CloudflareDeploymentEntity) -> CloudflareDeploymentStatus?,
    ): CloudflareDeploymentStatus {
        require(statusOf(deployment.status) in recoverableStatuses) { "部署不在待对账状态" }
        val status = query(deployment) ?: return CloudflareDeploymentStatus.RESULT_UNKNOWN
        require(status == CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED ||
            status == CloudflareDeploymentStatus.DEPLOYMENT_FAILED ||
            status == CloudflareDeploymentStatus.DEPLOYMENT_PENDING) { "部署状态无效" }
        dao.updateCloudflareDeploymentStatus(deployment.deploymentId, status.name, "部署对账状态：${status.name}")
        return status
    }

    /** 使用持久化的真实远端 deployment 标识对账；绝不重复上传或创建部署。 */
    suspend fun reconcileUnknownWithCloudflare(deployment: CloudflareDeploymentEntity): CloudflareDeploymentStatus {
        require(statusOf(deployment.status) in recoverableStatuses) { "部署不在待对账状态" }
        val knownRemote = deployment.remoteDeploymentId
        if (knownRemote != null) {
            return reconcileUnknown(deployment) {
                try { api.workerDeploymentStatus(deployment.accountId, deployment.workerName, knownRemote) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { null }
            }
        }
        val versionId = deployment.versionId ?: return CloudflareDeploymentStatus.RESULT_UNKNOWN
        val lookup = try {
            api.findDeploymentByVersion(deployment.accountId, deployment.workerName, versionId)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { null } ?: return CloudflareDeploymentStatus.RESULT_UNKNOWN
        dao.updateCloudflareDeploymentStatus(
            deployment.deploymentId,
            lookup.status.name,
            "已通过版本 ID 对账远端 deployment",
            remoteDeploymentId = lookup.deploymentId,
            versionId = versionId,
        )
        return lookup.status
    }

    private fun statusOf(value: String): CloudflareDeploymentStatus = runCatching { CloudflareDeploymentStatus.valueOf(value) }.getOrDefault(CloudflareDeploymentStatus.RESULT_UNKNOWN)
    private fun recoveredStatus(value: String): CloudflareDeploymentStatus =
        if (value == CloudflareDeploymentStatus.REQUEST_NOT_SENT.name) CloudflareDeploymentStatus.RESULT_UNKNOWN else statusOf(value)
    /** 只有网络中断、超时和 5xx 等无法判断远端结果的错误才进入 UNKNOWN。 */
    private fun failureStatus(error: CloudflareApiException?): CloudflareDeploymentStatus =
        if (error != null && error.code in setOf(
                "AUTHORIZATION_REQUIRED", "PERMISSION_DENIED", "RESOURCE_NOT_FOUND",
                "HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_409", "HTTP_422",
            )) CloudflareDeploymentStatus.DEPLOYMENT_FAILED
        else CloudflareDeploymentStatus.RESULT_UNKNOWN

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    override fun close() = api.close()

    companion object {
        /** 冷启动时这些状态均不能证明“未发送”，只允许查询，不能重放写请求。 */
        val recoverableStatuses = setOf(
            CloudflareDeploymentStatus.REQUEST_NOT_SENT,
            CloudflareDeploymentStatus.REQUEST_ACCEPTED,
            CloudflareDeploymentStatus.DEPLOYMENT_PENDING,
            CloudflareDeploymentStatus.RESULT_UNKNOWN,
        )
    }
}
