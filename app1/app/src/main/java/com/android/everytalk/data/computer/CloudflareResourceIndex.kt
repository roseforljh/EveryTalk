package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cloudflare 资源的本地可信索引。
 *
 * 模型传入的 resource ID 不能直接成为云端请求目标。只有 App 刚刚从当前
 * Computer、Account 的列表接口读到的资源，才会进入这里并允许后续操作。
 * 索引只保存 ID 和展示名，不保存 D1/KV/R2 的内容或 Secret。
 */
class CloudflareResourceIndex(private val dao: ComputerDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun rememberPage(
        computerId: String,
        accountId: String,
        kind: String,
        result: JsonObject,
    ) {
        val payload = result["result"]
        val rows = (if (kind == "R2_BUCKET") (payload as? JsonObject)?.get("buckets") else payload) as? JsonArray
            ?: throw CloudflareApiException("RESPONSE_INVALID", "资源列表格式无效")
        val items = rows.mapNotNull { item ->
            val objectItem = item as? JsonObject ?: return@mapNotNull null
            val id = firstText(objectItem, when (kind) {
                "D1" -> "uuid"
                "R2_BUCKET" -> "name"
                "QUEUE" -> "queue_id"
                else -> "id"
            }) ?: return@mapNotNull null
            val displayName = firstText(objectItem, "name", "title", "queue_name") ?: id
            id to displayName
        }
        items.forEach { (id, displayName) ->
            dao.upsertCloudflareResource(
                CloudflareResourceEntity(
                    resourceRef = ref(computerId, accountId, kind, id),
                    computerId = computerId,
                    accountId = accountId,
                    kind = kind,
                    resourceId = id,
                    displayName = displayName.take(160),
                    updatedAt = clock(),
                ),
            )
        }
    }

    suspend fun rememberWorkers(computerId: String, accountId: String, workers: List<CloudflareWorkerSummary>) {
        workers.forEach { worker ->
            dao.upsertCloudflareResource(
                CloudflareResourceEntity(
                    ref(computerId, accountId, "WORKER", worker.id), computerId, accountId,
                    "WORKER", worker.id, worker.id.take(160), clock(),
                ),
            )
        }
    }

    suspend fun requireKnown(computerId: String, accountId: String, kind: String, resourceId: String) {
        if (!isKnown(computerId, accountId, kind, resourceId)) throw CloudflareApiException(
            "RESOURCE_SELECTION_REQUIRED",
            "请先通过 App 列出并选择当前 Account 的 $kind 资源",
        )
    }

    /** 不抛异常的命中判断；调用方据此决定是重新列一次还是转人工。 */
    suspend fun isKnown(computerId: String, accountId: String, kind: String, resourceId: String): Boolean =
        resourceId.isNotBlank() && options(computerId, accountId, kind).any { it.id == resourceId }

    /**
     * Worker binding 必须使用列表接口返回的真实资源引用。
     * 不能接受展示名：D1 的展示名可能与 UUID 不同，直接把展示名发给部署
     * API 会造成错误目标或让用户误以为绑定已经生效。
     */
    suspend fun requireKnownBinding(computerId: String, accountId: String, kind: String, reference: String) {
        resolveKnownBinding(computerId, accountId, kind, reference)
    }

    /** 返回可信展示值。Queue 的部署 API 使用 queue_name，而列表操作的稳定选择键是 queue_id。 */
    suspend fun resolveKnownBinding(computerId: String, accountId: String, kind: String, reference: String): ResourceOption =
        options(computerId, accountId, kind).singleOrNull { it.id == reference }
            ?: throw CloudflareApiException("RESOURCE_SELECTION_REQUIRED", "Worker binding 资源不属于当前 Cloudflare Account")

    /** 缓存只用于短期选择；过期后必须重新读取，不能把旧账号的列表当作授权。 */
    suspend fun options(computerId: String, accountId: String, kind: String): List<ResourceOption> =
        dao.getCloudflareResources(computerId, kind).filter {
            it.accountId == accountId && clock() - it.updatedAt in 0..600_000
        }.map { ResourceOption(it.resourceId, it.displayName) }

    /** 只由 App 选择控件调用。选择绑定 Suspension，恢复时不会复用其他任务的选择。 */
    suspend fun select(suspensionId: String, computerId: String, accountId: String, kind: String, id: String) {
        val option = options(computerId, accountId, kind).singleOrNull { it.id == id }
            ?: throw CloudflareApiException("RESOURCE_SELECTION_REQUIRED", "资源列表已变化，请刷新")
        dao.upsertCloudflareResource(CloudflareResourceEntity(
            "cloudflare-selection:$suspensionId", computerId, accountId, "SELECTED_$kind",
            option.id, option.displayName, clock(),
        ))
    }

    suspend fun selection(suspensionId: String): CloudflareResourceEntity? =
        dao.getCloudflareResource("cloudflare-selection:$suspensionId")

    /** 重新读取原 Account 和授权代次；等待期间切账号、退出或重登都会使旧选择失效。 */
    suspend fun requireBinding(parameters: Map<String, String>) {
        val config = dao.getCloudflareConfig(parameters["computer_id"].orEmpty())
            ?: throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "原 Computer 已不存在")
        val authorization = dao.getCloudflareAuthorization(config.authorizationId)
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "原授权已不存在")
        if (config.accountId != parameters["account_id"] || config.authorizationId != parameters["authorization_id"] ||
            authorization.generation != parameters["previous_generation"]?.toLongOrNull() || authorization.revoked ||
            authorization.expiresAt?.let { it <= clock() } == true) {
            throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "目标或授权已变化，请重新发起资源选择")
        }
    }

    suspend fun selectionReady(suspensionId: String, parameters: Map<String, String>): Boolean {
        try { requireBinding(parameters) } catch (_: CloudflareApiException) { return false }
        val selected = selection(suspensionId) ?: return false
        return selected.computerId == parameters["computer_id"] && selected.accountId == parameters["account_id"] &&
            selected.kind == "SELECTED_${parameters["resource_kind"]}"
    }

    private fun ref(computerId: String, accountId: String, kind: String, resourceId: String): String =
        "cloudflare-resource:$computerId:$accountId:$kind:$resourceId"

    private fun firstText(item: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}

/** 目标字段、资源类型和列表工具由本地契约固定，不接收模型指定的 UI 或 API 路径。 */
internal data class CloudflareResourceTarget(val parameter: String, val kind: String, val listTool: String)

internal fun cloudflareResourceTarget(toolName: String): CloudflareResourceTarget? = when {
    toolName in setOf(ComputerToolNames.WORKER_READ, ComputerToolNames.WORKER_UPDATE,
        ComputerToolNames.WORKER_STATUS, ComputerToolNames.WORKER_LOGS, ComputerToolNames.WORKER_DELETE,
        ComputerToolNames.CRON_LIST, ComputerToolNames.CRON_UPDATE) ->
        CloudflareResourceTarget("worker_name", "WORKER", ComputerToolNames.WORKER_LIST)
    toolName.startsWith("computer_d1_") && toolName != ComputerToolNames.D1_LIST ->
        CloudflareResourceTarget("database_id", "D1", ComputerToolNames.D1_LIST)
    toolName.startsWith("computer_kv_") && toolName != ComputerToolNames.KV_LIST_NAMESPACES ->
        CloudflareResourceTarget("namespace_id", "KV_NAMESPACE", ComputerToolNames.KV_LIST_NAMESPACES)
    toolName.startsWith("computer_r2_") && toolName != ComputerToolNames.R2_LIST_BUCKETS ->
        CloudflareResourceTarget("bucket", "R2_BUCKET", ComputerToolNames.R2_LIST_BUCKETS)
    toolName == ComputerToolNames.DO_OBJECTS_LIST ->
        CloudflareResourceTarget("namespace_id", "DO_NAMESPACE", ComputerToolNames.DO_LIST)
    toolName in setOf(ComputerToolNames.QUEUES_GET, ComputerToolNames.QUEUES_METRICS,
        ComputerToolNames.QUEUES_PEEK, ComputerToolNames.QUEUES_DELETE) ->
        CloudflareResourceTarget("queue_id", "QUEUE", ComputerToolNames.QUEUES_LIST)
    else -> null
}
