package com.android.everytalk.data.computer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Cloudflare API 返回的 Worker 摘要，只保留 UI 和 Agent 所需字段。 */
@Serializable
data class CloudflareWorkerSummary(
    val id: String,
    val etag: String? = null,
    @SerialName("created_on") val createdOn: String? = null,
    @SerialName("modified_on") val modifiedOn: String? = null,
)

@Serializable
data class CloudflareWorkerListResult(
    val workers: List<CloudflareWorkerSummary>,
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = workers.size,
    @SerialName("total_count") val totalCount: Int = workers.size,
)

/** Worker 部署状态，和本地 Tool 执行状态分开保存。 */
@Serializable
enum class CloudflareDeploymentStatus {
    REQUEST_NOT_SENT,
    REQUEST_ACCEPTED,
    DEPLOYMENT_PENDING,
    DEPLOYMENT_SUCCEEDED,
    DEPLOYMENT_FAILED,
    RESULT_UNKNOWN,
}

@Serializable
data class CloudflareDeploymentResult(
    val workerName: String,
    val deploymentId: String? = null,
    val status: CloudflareDeploymentStatus,
    val versionId: String? = null,
    val workerUrl: String? = null,
)

/** 用已上传的版本 ID 从部署历史反查远端 deployment，专门用于 UNKNOWN 对账。 */
data class CloudflareDeploymentLookup(
    val deploymentId: String,
    val status: CloudflareDeploymentStatus,
)

@Serializable
enum class CloudflareWorkerRuntimeStatus { HEALTHY, UNHEALTHY, UNKNOWN }

/** Worker 运行时探测只返回状态和耗时，不把响应正文送入模型。 */
@Serializable
data class CloudflareWorkerHealth(
    val status: CloudflareWorkerRuntimeStatus,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
)

/** API 错误不会把原始响应直接传给模型。 */
@Serializable
data class CloudflareApiError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
)
