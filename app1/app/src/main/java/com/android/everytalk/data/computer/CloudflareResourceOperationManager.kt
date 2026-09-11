package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceOperationEntity
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class CloudflareResourceOperationStatus {
    REQUEST_NOT_SENT,
    REQUEST_ACCEPTED,
    RESULT_UNKNOWN,
    /** API 已明确拒绝或失败；恢复时不能把这条记录当成成功。 */
    FAILED,
    CONFIRMED,
}

/** R2 写操作的幂等协调器；调用方提供远端核验函数，未知结果绝不自动重放。 */
class CloudflareResourceOperationManager(private val dao: ComputerDao) {
    suspend fun run(
        computerId: String, accountId: String, kind: String, resourceRef: String,
        requestMaterial: String, safeSummary: String, operationKey: String, action: suspend () -> Unit,
    ): CloudflareResourceOperationStatus {
        require(operationKey.isNotBlank()) { "资源操作缺少幂等键" }
        // 内容相同的新任务不是重放。以可信 Run/Tool 槽位区分每次操作，
        // 同一槽位的目标或内容发生变化则拒绝，避免覆盖原来未知的执行事实。
        val id = "resource-op-${sha256("$computerId\n$operationKey")}" 
        val hash = sha256("$id\n$accountId\n$kind\n$resourceRef\n$requestMaterial")
        dao.getCloudflareResourceOperation(id)?.let {
            if (it.requestHash != hash) throw CloudflareApiException("IDEMPOTENCY_CONFLICT", "该工具调用已经绑定另一份资源请求")
            return persistedStatus(it.status)
        }
        val now = System.currentTimeMillis()
        val operation = CloudflareResourceOperationEntity(id, computerId, accountId, kind, resourceRef, hash, CloudflareResourceOperationStatus.REQUEST_NOT_SENT.name, now, now, safeSummary.take(1000))
        if (dao.insertCloudflareResourceOperationIfAbsent(operation) == -1L) {
            return dao.getCloudflareResourceOperation(id)?.let {
                if (it.requestHash != hash) throw CloudflareApiException("IDEMPOTENCY_CONFLICT", "该工具调用已经绑定另一份资源请求")
                persistedStatus(it.status)
            } ?: CloudflareResourceOperationStatus.RESULT_UNKNOWN
        }
        return try {
            action()
            dao.updateCloudflareResourceOperation(id, CloudflareResourceOperationStatus.REQUEST_ACCEPTED.name, safeSummary.take(1000))
            CloudflareResourceOperationStatus.REQUEST_ACCEPTED
        } catch (error: Throwable) {
            // D1 多语句可能在后续语句失败前已产生写入。业务失败和 SQL 400
            // 不能证明整批都未执行，只能保留 UNKNOWN，禁止再次盲目提交。
            val partialD1 = kind == "D1_QUERY" && (error as? CloudflareApiException)?.code in setOf("CLOUDFLARE_API_ERROR", "HTTP_400", "HTTP_409", "HTTP_422")
            val status = if (!partialD1 && error is CloudflareApiException && isDefinitiveFailure(error.code)) {
                CloudflareResourceOperationStatus.FAILED
            } else {
                CloudflareResourceOperationStatus.RESULT_UNKNOWN
            }
            // 取消协程不等于撤销远端写入。先持久化 UNKNOWN，重启仍能找到这次操作。
            // 写账失败也不能覆盖原始异常；REQUEST_NOT_SENT 占位会按 UNKNOWN 恢复。
            withContext(NonCancellable) {
                runCatching {
                    dao.updateCloudflareResourceOperation(
                        id,
                        status.name,
                        if (status == CloudflareResourceOperationStatus.FAILED) "Cloudflare 已明确拒绝该操作" else "结果未知，需要查询确认",
                    )
                }
            }
            throw error
        }
    }

    /** 占位可能是在真正发请求前留下的；恢复时必须按未知处理，不能误报成功。 */
    private fun persistedStatus(value: String): CloudflareResourceOperationStatus =
        when (runCatching { CloudflareResourceOperationStatus.valueOf(value) }.getOrDefault(CloudflareResourceOperationStatus.RESULT_UNKNOWN)) {
            CloudflareResourceOperationStatus.REQUEST_NOT_SENT -> CloudflareResourceOperationStatus.RESULT_UNKNOWN
            else -> runCatching { CloudflareResourceOperationStatus.valueOf(value) }
                .getOrDefault(CloudflareResourceOperationStatus.RESULT_UNKNOWN)
        }

    /** 只有这些错误能证明请求已被 Cloudflare 明确拒绝；网络/超时必须保留 UNKNOWN。 */
    private fun isDefinitiveFailure(code: String): Boolean = code in setOf(
        "AUTHORIZATION_REQUIRED",
        "PERMISSION_DENIED",
        "RESOURCE_NOT_FOUND",
        "CLOUDFLARE_API_ERROR",
        "HTTP_400",
        "HTTP_401",
        "HTTP_403",
        "HTTP_404",
        "HTTP_409",
        "HTTP_422",
        "RATE_LIMITED",
        "REDIRECT_REJECTED",
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
