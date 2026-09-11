package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

/**
 * Computer Provider 的唯一分流入口。
 * SSH 继续交给原有 ComputerToolExecutor；Cloudflare 只接受云资源工具，
 * 因而不会把 Cloudflare Computer 当成可以执行 SSH Shell 的服务器。
 */
class ComputerProviderRouter(
    private val computerLookup: suspend (String) -> Computer?,
    private val sshProvider: SshComputerProvider,
    private val cloudflareExecutor: CloudflareComputerProvider,
) {
    /** 保留现有调用方的命名参数，避免迁移路由器时改变 SSH 执行链路。 */
    constructor(
        computerLookup: suspend (String) -> Computer?,
        sshExecutor: suspend (String, JsonObject, String, ComputerRequestContext, suspend (String?) -> Unit) -> kotlinx.serialization.json.JsonElement,
        cloudflareExecutor: CloudflareComputerProvider,
    ) : this(computerLookup, DelegatingSshComputerProvider(sshExecutor), cloudflareExecutor)
    suspend fun approvalRequest(toolName: String, arguments: JsonObject, toolCallId: String, context: ComputerRequestContext): ComputerToolApprovalRequest? {
        // 审批预检必须和真正执行使用同一条 Provider 分流规则。
        // 之前这里无条件调用 Cloudflare 预检，导致 SSH Computer 的云端写工具
        // 可能先弹出错误的 Cloudflare 审批卡片，随后才在执行阶段失败。
        val computer = computerLookup(context.computerId) ?: return null
        return when (computer.provider) {
            ComputerProvider.SSH -> null
            ComputerProvider.CLOUDFLARE -> cloudflareExecutor.approvalRequest(toolName, arguments, toolCallId, context)
        }
    }
    suspend fun execute(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit = {},
    ): kotlinx.serialization.json.JsonElement {
        val computer = computerLookup(context.computerId)
            ?: return failure("COMPUTER_NOT_FOUND", "Computer 记录不存在")
        return when (computer.provider) {
            ComputerProvider.SSH -> if (toolName in ComputerToolNames.cloudflare || context.cloudflareBinding != null) {
                failure("PROVIDER_MISMATCH", "SSH Computer 不支持 Cloudflare 工具")
            } else sshProvider.execute(toolName, arguments, toolCallId, context, updateStatus)
            ComputerProvider.CLOUDFLARE -> cloudflareExecutor.execute(toolName, arguments, toolCallId, context, updateStatus)
        }
    }

    private fun failure(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("error", message)
        put("execution_id", "provider-${UUID.randomUUID()}")
        if (code == "AUTHORIZATION_REQUIRED") {
            put("intervention_type", "REAUTHORIZATION")
            put("intervention_provider", "CLOUDFLARE")
        }
    }
}

/** Cloudflare Provider 的最小可执行边界，所有资源调用都先验证当前 Account。 */
class CloudflareComputerProvider(
    private val configLookup: suspend (String) -> CloudflareComputerConfig?,
    private val api: CloudflareApiClient? = null,
    private val resources: CloudflareResourceClient? = null,
    private val tokenProvider: (suspend (ComputerRequestContext) -> String)? = null,
    private val apiFactory: (suspend (ComputerRequestContext) -> CloudflareApiClient)? = null,
    private val resourcesFactory: (suspend (ComputerRequestContext) -> CloudflareResourceClient)? = null,
    private val migrationManagerFactory: (suspend (ComputerRequestContext) -> D1MigrationManager)? = null,
    private val featureFlags: ComputerFeatureFlags? = null,
    private val authorizationLookup: (suspend (String) -> CloudflareAuthorizationRecord?)? = null,
    private val workspaceRootLookup: (suspend (String) -> java.io.File?)? = null,
    private val resourceOperationManagerFactory: (suspend (String) -> CloudflareResourceOperationManager)? = null,
    private val resourceIndex: CloudflareResourceIndex? = null,
    private val deploymentManagerFactory: (suspend (ComputerRequestContext) -> WorkerDeploymentManager)? = null,
    private val healthRecorder: (suspend (String, String, CloudflareWorkerHealth) -> Unit)? = null,
) {
    suspend fun approvalRequest(toolName: String, arguments: JsonObject, toolCallId: String, context: ComputerRequestContext): ComputerToolApprovalRequest? {
        if (toolName == ComputerToolNames.CRON_TRIGGER) return null
        // 和 VPS 共用同一套权限模式判定：MANUAL 全弹、SMART 看模型自报、FULL 不弹，
        // 只读工具在任何模式下都不弹。写操作的 scope 与能力校验不在这里，仍然照常执行。
        if (!ComputerToolCallSafety.requiresUnknownApproval(toolName, arguments, context.permissionMode)) return null
        if (toolName != ComputerToolNames.CRON_UPDATE) {
            if (toolName !in ComputerToolNames.cloudflare || ComputerToolCallSafety.isReadOnly(toolName, arguments)) return null
            val config = configLookup(context.computerId) ?: return null
            val auth = try { requireCloudflareAccess(config, context, toolName, arguments) }
            catch (error: CloudflareApiException) {
                // 交给执行边界返回带目标快照的授权干预；不生成一张无法执行的写审批。
                if (error.code in setOf("AUTHORIZATION_REQUIRED", "RESOURCE_SELECTION_REQUIRED", "RESOURCE_NOT_FOUND")) return null else throw error
            }
            val packageForRequest = workerPackageForRequest(toolName, arguments, context)
            if (packageForRequest != null) requireWorkerBindings(config, context, packageForRequest)
            return ComputerToolApprovalRequest.CloudflareWrite(
                toolCallId, context, config.accountId, config.authorizationId, toolName,
                cloudflareSafeSummary(toolName, arguments, packageForRequest),
                auth.generation, cloudflareRequestHash(config.accountId, toolName, arguments, context, packageForRequest),
            )
        }
        val config = configLookup(context.computerId) ?: return null
        val auth = try { requireCronAccess(config, context) }
        catch (error: CloudflareApiException) {
            if (error.code in setOf("AUTHORIZATION_REQUIRED", "RESOURCE_SELECTION_REQUIRED", "RESOURCE_NOT_FOUND")) return null else throw error
        }
        val requested = CloudflareCronSchedules.fromArguments(arguments)
        try { requireResourceTarget(config, context, toolName, arguments) }
        catch (error: CloudflareApiException) {
            if (error.code in setOf("RESOURCE_SELECTION_REQUIRED", "RESOURCE_NOT_FOUND")) return null else throw error
        }
        val workerName = arguments.requireText("worker_name")
        val ownedResources = resourcesFactory?.invoke(context)
        val resourceClient = ownedResources ?: resources
            ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
        val previous = try {
            CloudflareCronSchedules.fromResponse(resourceClient.listWorkerSchedules(config.accountId, workerName))
        } finally { ownedResources?.close() }
        requireCronAccess(config, context, auth.generation)
        return ComputerToolApprovalRequest.CloudflareCronChange(
            toolCallId, context, "Cloudflare", config.accountId, auth.authorizationId, auth.generation,
            workerName, previous, requested, cronRequestHash(config.accountId, workerName, previous, requested),
        )
    }
    suspend fun execute(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit,
    ): kotlinx.serialization.json.JsonElement {
        // 每次 Provider 调用在边界生成唯一 execution_id。Cloudflare API 的
        // 返回体不属于应用内执行协议，不能把 API 返回的 deployment/version ID
        // 冒充执行 ID，也不能让某个早退分支漏掉执行 ID。
        val executionId = "cloudflare-${UUID.randomUUID().toString().replace("-", "")}"
        val result = try {
            withExecutionId(executionId, executeChecked(toolName, arguments, toolCallId, context, updateStatus))
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudflareApiException) {
            failure(error.code, error.message, executionId, error.retryable)
        } catch (_: IllegalArgumentException) {
            failure("INVALID_ARGUMENT", "Cloudflare 工具参数无效", executionId)
        } catch (_: Exception) {
            // 外部响应解析或网络库异常不能泄露响应正文。写请求发生异常时不能
            // 鼓励自动重试，因为异常可能出现在远端已接受之后。
            val readOnly = ComputerToolCallSafety.isReadOnly(toolName, arguments)
            failure(if (readOnly) "CLOUDFLARE_REQUEST_FAILED" else "RESULT_UNKNOWN",
                "Cloudflare 请求结果未完整确认", executionId, readOnly)
        }
        // 只附加本地授权元数据，供 Broker 将重新授权固定到同一 Computer/Account。
        // Token、API 正文和模型提供的 intervention 字段都不参与这里的决策。
        val envelope = result as? JsonObject ?: return result
        val errorCode = (envelope["error_code"] as? JsonPrimitive)?.contentOrNull
        if (errorCode !in setOf("AUTHORIZATION_REQUIRED", "RESOURCE_SELECTION_REQUIRED")) return result
        val config = configLookup(context.computerId) ?: return result
        val authorization = authorizationLookup?.invoke(config.authorizationId) ?: return result
        return JsonObject(envelope + mapOf(
            "authorization_id" to JsonPrimitive(config.authorizationId),
            "authorization_generation" to JsonPrimitive(authorization.generation),
            "account_id" to JsonPrimitive(config.accountId),
            "intervention_type" to JsonPrimitive(if (errorCode == "AUTHORIZATION_REQUIRED") "REAUTHORIZATION" else "RESOURCE_SELECTION"),
        ))
    }

    private fun withExecutionId(executionId: String, result: JsonElement): JsonElement {
        val objectResult = result as? JsonObject ?: return buildJsonObject {
            put("ok", false)
            put("error_code", "RESPONSE_INVALID")
            put("error", "Provider 返回结果格式无效")
            put("execution_id", executionId)
        }
        // 只有本地生成的 error_code 才能决定协议失败；Cloudflare 返回体里的
        // ok/success/error 只是外部数据，不能伪造 App 的执行状态。
        val protocolOk = (objectResult["error_code"] as? JsonPrimitive)?.contentOrNull == null
        return JsonObject(objectResult + mapOf(
            "execution_id" to JsonPrimitive(executionId),
            "ok" to JsonPrimitive(protocolOk),
        ))
    }

    private suspend fun executeChecked(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit,
    ): kotlinx.serialization.json.JsonElement {
        if (toolName !in ComputerToolNames.cloudflare) return failure("UNKNOWN_PROVIDER_TOOL", "Cloudflare Provider 不支持该工具")
        if (toolName == ComputerToolNames.CRON_TRIGGER) return failure("UNSUPPORTED_OPERATION", "Cloudflare 官方 API 未提供 Cron 立即触发接口")
        val config = configLookup(context.computerId)
            ?: return failure("PROVIDER_CONFIG_NOT_FOUND", "Cloudflare Computer 配置不存在")
        if (featureFlags?.cloudflareEnabled == false) return failure("FEATURE_DISABLED", "Cloudflare 功能当前未开启")
        if (context.computerId != config.computerId || context.workspaceId.isBlank()) {
            return failure("COMPUTER_CONTEXT_MISMATCH", "Cloudflare Computer 与请求上下文不一致")
        }
        val authorization = requireCloudflareAccess(config, context, toolName, arguments)
        // R2 上传只读取一次字节：审批指纹和实际 HTTP 上传必须针对同一份内容，
        // 否则审批后文件替换可能让“已批准内容”与真正上传内容不一致。
        val r2Bytes = if (toolName == ComputerToolNames.R2_UPLOAD) {
            val root = workspaceRootLookup?.invoke(context.workspaceId)
                ?: return failure("WORKSPACE_NOT_FOUND", "当前 Workspace 不存在")
            val path = arguments["path"]?.jsonPrimitive?.contentOrNull
                ?: return failure("INVALID_ARGUMENT", "R2 上传缺少 Workspace 文件路径")
            LocalWorkspaceFileBridge(maxFileBytes = 32L * 1024 * 1024).read(root, path)
        } else null
        val packageForRequest = workerPackageForRequest(toolName, arguments, context)
        if (packageForRequest != null) requireWorkerBindings(config, context, packageForRequest)
        val token = tokenProvider?.invoke(context)
            ?: return failure("AUTHORIZATION_REQUIRED", "Cloudflare 授权未配置")
        if (token.isBlank()) return failure("AUTHORIZATION_REQUIRED", "Cloudflare 授权无效")
        val accountId = config.accountId
        val operationKey = "${context.runId.orEmpty()}:${ComputerToolRequestHasher.toolCallKey(toolCallId, context)}"
        val readOnly = ComputerToolCallSafety.isReadOnly(toolName, arguments)
        if (!readOnly && featureFlags?.cloudflareWorkerWriteEnabled == false && toolName.startsWith("computer_worker_")) {
            return failure("FEATURE_DISABLED", "Cloudflare Worker 写操作当前未开启")
        }
        if (!toolName.startsWith("computer_worker_")) {
            if (featureFlags?.cloudflareResourceToolsEnabled == false) return failure("FEATURE_DISABLED", "Cloudflare 资源工具当前未开启")
        }
        // 执行侧的确认门必须和审批卡用同一套权限模式判定：FULL 不产生审批卡，
        // 这里却仍要求 approvedToolCallId，会把所有写操作一律挡成 CONFIRMATION_REQUIRED。
        val requiresConfirmation = ComputerToolCallSafety.requiresUnknownApproval(toolName, arguments, context.permissionMode)
        if (requiresConfirmation && context.approvedToolCallId != toolCallId) {
            return failure("CONFIRMATION_REQUIRED", "该 Cloudflare 操作会修改云端资源，需要用户确认")
        }
        // Cron 使用包含旧值/新值的专用审批，不能同时要求另一个通用审批。
        if (requiresConfirmation && toolName != ComputerToolNames.CRON_UPDATE) {
            val approval = context.approvedCloudflareWrite
                ?: return failure("CONFIRMATION_REQUIRED", "该 Cloudflare 操作需要用户确认")
            if (approval.toolCallId != toolCallId || approval.context.runId != context.runId ||
                approval.context.computerId != context.computerId || approval.context.workspaceId != context.workspaceId ||
                approval.accountId != config.accountId || approval.toolName != toolName ||
                approval.authorizationId != config.authorizationId || approval.generation != authorization.generation ||
                approval.requestHash != cloudflareRequestHash(config.accountId, toolName, arguments, context, packageForRequest, r2Bytes)
            ) return failure("APPROVAL_MISMATCH", "批准内容与当前 Cloudflare 请求不一致，请重新确认")
        }
        var ownedResources: CloudflareResourceClient? = null
        var ownedApi: CloudflareApiClient? = null
        return try {
            updateStatus("访问 Cloudflare：$toolName")
            val requestApi = apiFactory?.invoke(context)?.also { ownedApi = it } ?: api
                ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "Cloudflare API 客户端未初始化")
            ownedResources = resourcesFactory?.invoke(context)
            val requestResources = ownedResources ?: resources
            when (toolName) {
                "computer_worker_list" -> requestApi.listWorkers(
                    accountId,
                    arguments["page"]?.jsonPrimitive?.intOrNull ?: 1,
                    arguments["per_page"]?.jsonPrimitive?.intOrNull ?: 100,
                ).also { result ->
                    resourceIndex?.rememberWorkers(context.computerId, accountId, result.workers)
                }.toJson().let(::safeExternalJson)
                "computer_worker_read" -> {
                    val name = arguments.requireText("worker_name")
                    val source = sanitizeExternalText(requestApi.downloadWorker(accountId, name), 64_000)
                    buildJsonObject {
                        put("ok", true)
                        put("worker_name", name)
                        put("script", source.text)
                        put("truncated", source.truncated)
                        put("untrusted_external_data", true)
                    }
                }
                "computer_worker_create", "computer_worker_update", "computer_worker_deploy" -> {
                    val name = arguments.requireText("worker_name")
                    val script = arguments["script"]?.jsonPrimitive?.contentOrNull
                    val result = if (toolName == "computer_worker_deploy" && script.isNullOrBlank()) {
                        val root = workspaceRootLookup?.invoke(context.workspaceId)
                            ?: return failure("WORKSPACE_NOT_FOUND", "当前 Workspace 不存在")
                        val subPath = arguments["workspace_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val project = File(root, subPath).canonicalFile
                        require(project.toPath().startsWith(root.canonicalFile.toPath())) { "Workspace 路径越界" }
                        val manager = deploymentManagerFactory?.invoke(context)
                            ?: throw CloudflareApiException("DEPLOYMENT_MANAGER_UNAVAILABLE", "部署账本未初始化，未发送请求")
                        val pack = packageForRequest ?: throw CloudflareApiException("WORKSPACE_NOT_FOUND", "Worker 部署包不存在")
                        val resolved = resolveWorkerBindings(config, context, pack)
                        try { manager.deployPackage(context.computerId, accountId, name, resolved) } finally { manager.close() }
                    } else {
                        val source = script ?: throw IllegalArgumentException("Worker 源码为空")
                        if (source.length > 2 * 1024 * 1024 || source.contains("-----BEGIN")) {
                            return failure("SENSITIVE_OR_TOO_LARGE", "Worker 内容包含敏感材料或超过大小限制")
                        }
                        val manager = deploymentManagerFactory?.invoke(context)
                            ?: throw CloudflareApiException("DEPLOYMENT_MANAGER_UNAVAILABLE", "部署账本未初始化，未发送请求")
                        try { manager.deployScript(context.computerId, accountId, name, source) } finally { manager.close() }
                    }
                    buildJsonObject {
                        val completed = result.status == CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED
                        put("ok", completed)
                        if (!completed) put("error_code", if (result.status == CloudflareDeploymentStatus.DEPLOYMENT_FAILED) "OPERATION_FAILED" else "RESULT_UNKNOWN")
                        if (!completed) put("error", if (result.status == CloudflareDeploymentStatus.DEPLOYMENT_FAILED) "Worker 部署已明确失败" else "Worker 部署结果未知，请先查询状态")
                        put("worker_name", name); put("status", result.status.name)
                        result.deploymentId?.let { put("deployment_id", it) }
                        result.versionId?.let { put("version_id", it) }
                    }
                }
                "computer_worker_status" -> {
                    val name = arguments.requireText("worker_name")
                    val raw = sanitizeExternalText(requestApi.workerSettings(accountId, name), 16_000)
                    val deployments = ComputerExternalOutput.json(requestApi.workerDeployments(accountId, name), 16_000)
                    val workerUrl = runCatching { requestApi.workerUrl(accountId, name) }.getOrNull()
                    buildJsonObject {
                        put("ok", true)
                        put("worker_name", name)
                        put("settings_summary", raw.text)
                        put("deployments", deployments.value)
                        workerUrl?.let { put("worker_url", it) }
                        put("truncated", raw.truncated || deployments.truncated)
                        put("untrusted_external_data", true)
                    }
                }
                "computer_worker_logs" -> {
                    val name = arguments.requireText("worker_name")
                    val raw = requestApi.workerTailLogs(accountId, name)
                    val safeLogs = sanitizeExternalLog(raw, 32_000)
                    buildJsonObject { put("ok", true); put("worker_name", name); put("logs_summary", safeLogs.text); put("truncated", safeLogs.truncated); put("untrusted_external_data", true) }
                }
                ComputerToolNames.WORKER_HEALTH -> {
                    val url = arguments.requireText("url")
                    val health = requestApi.probeWorker(url)
                    // 健康探测结果只保留本地摘要，供详情页和重启恢复使用。
                    // recorder 失败不能改变已经完成的远端只读探测结果。
                    healthRecorder?.let { recorder ->
                        runCatching { recorder(context.computerId, arguments["worker_name"]?.jsonPrimitive?.contentOrNull ?: url, health) }
                    }
                    buildJsonObject {
                        put("ok", true); put("runtime_status", health.status.name)
                        health.httpStatus?.let { put("http_status", it) }
                        health.latencyMs?.let { put("latency_ms", it) }
                    }
                }
                "computer_worker_delete" -> {
                    val name = arguments.requireText("worker_name")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(context.computerId, accountId, "WORKER_DELETE", name, "delete", "删除 Worker：$name", operationKey) {
                        requestApi.deleteWorker(accountId, name)
                    }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "Worker 删除结果未知，请先查询 Worker 状态")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "Worker 删除已明确失败")
                    buildJsonObject { put("ok", true); put("worker_name", name); put("status", status.name) }
                }
                "computer_d1_list" -> requestResources?.listD1(accountId)?.also { resourceIndex?.rememberPage(context.computerId, accountId, "D1", it) }?.let(::safeExternalJson)
                    ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "D1 客户端未初始化")
                "computer_d1_schema" -> {
                    val databaseId = arguments.requireText("database_id")
                    requestResources?.d1Schema(accountId, databaseId)?.let(::safeExternalJson) ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "D1 客户端未初始化")
                }
                "computer_d1_query" -> {
                    val databaseId = arguments.requireText("database_id")
                    val sql = arguments.requireText("sql")
                    val resourceClient = requestResources ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "D1 客户端未初始化")
                    if (ComputerToolCallSafety.isReadOnly(toolName, arguments)) {
                        limitD1Result(resourceClient.queryD1(accountId, databaseId, sql))
                    } else {
                        val manager = resourceOperationManagerFactory?.invoke(context.computerId)
                            ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "D1 写查询账本未初始化，未发送请求")
                        val requestHash = sha256("$accountId\n$databaseId\n$sql".toByteArray(Charsets.UTF_8))
                        val status = manager.run(context.computerId, accountId, "D1_QUERY", "$databaseId:$requestHash", requestHash, "执行 D1 写查询：数据库已校验", operationKey) {
                            resourceClient.queryD1(accountId, databaseId, sql)
                        }
                        if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "D1 写查询结果未知，请先核对数据库状态")
                        if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "D1 写查询已明确失败")
                        buildJsonObject { put("ok", true); put("status", status.name); put("request_hash", requestHash) }
                    }
                }
                "computer_d1_migration" -> {
                    val databaseId = arguments.requireText("database_id")
                    val sql = arguments.requireText("sql")
                    val manager = migrationManagerFactory?.invoke(context)
                    if (manager != null) {
                        try {
                            val result = manager.execute(context.computerId, accountId, databaseId, sql)
                            buildJsonObject { put("ok", true); put("migration_hash", result.hash); put("already_applied", result.alreadyApplied) }
                        } finally { manager.close() }
                    } else {
                        failure("RESOURCE_CLIENT_UNAVAILABLE", "D1 migration 账本未初始化，未发送请求")
                    }
                }
                "computer_kv_list_namespaces" -> requestResources?.listKvNamespaces(accountId)?.also { resourceIndex?.rememberPage(context.computerId, accountId, "KV_NAMESPACE", it) }?.let(::safeExternalJson)
                    ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "KV 客户端未初始化")
                "computer_kv_list_keys" -> {
                    val namespaceId = arguments.requireText("namespace_id")
                    requestResources?.listKvKeys(accountId, namespaceId,
                        arguments["cursor"]?.jsonPrimitive?.contentOrNull,
                        arguments["limit"]?.jsonPrimitive?.intOrNull ?: 100,
                    )?.let(::safeExternalJson) ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "KV 客户端未初始化")
                }
                "computer_kv_get" -> {
                    val namespaceId = arguments.requireText("namespace_id")
                    val value = (requestResources ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "KV 客户端未初始化"))
                        .getKv(accountId, namespaceId, arguments.requireText("key"))
                    val safeValue = if (ComputerExternalOutput.isSensitiveName(arguments.requireText("key"))) "[REDACTED]" else redactValue(value)
                    buildJsonObject {
                        // reveal 不能由模型参数开启；敏感值只有后续专门的用户界面流程才能查看。
                        put("ok", true); put("value", safeValue)
                        put("redacted", safeValue == "[REDACTED]")
                        put("truncated", false)
                        put("untrusted_external_data", true)
                    }
                }
                "computer_kv_put" -> {
                    val namespaceId = arguments.requireText("namespace_id")
                    val key = arguments.requireText("key")
                    val value = arguments.requireText("value")
                    val client = requestResources ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "KV 客户端未初始化")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(
                        context.computerId, accountId, "KV_PUT", "$namespaceId:$key", sha256(value.toByteArray(Charsets.UTF_8)), "写入 KV：Namespace 和 Key 已校验", operationKey,
                    ) { client.putKv(accountId, namespaceId, key, value) }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "KV 写入结果未知，请先读取 Key")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "KV 写入已明确失败")
                    buildJsonObject { put("ok", true); put("status", status.name) }
                }
                "computer_kv_delete" -> {
                    val namespaceId = arguments.requireText("namespace_id")
                    val key = arguments.requireText("key")
                    val client = requestResources ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "KV 客户端未初始化")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(
                        context.computerId, accountId, "KV_DELETE", "$namespaceId:$key", "delete", "删除 KV Key：Namespace 和 Key 已校验", operationKey,
                    ) { client.deleteKv(accountId, namespaceId, key) }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "KV 删除结果未知，请先读取 Key")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "KV 删除已明确失败")
                    buildJsonObject { put("ok", true); put("status", status.name) }
                }
                "computer_r2_list_buckets" -> requestResources?.listR2Buckets(accountId)?.also { resourceIndex?.rememberPage(context.computerId, accountId, "R2_BUCKET", it) }?.let(::safeExternalJson)
                    ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "R2 客户端未初始化")
                "computer_r2_list_objects" -> {
                    val bucket = arguments.requireText("bucket")
                    requestResources?.listR2Objects(accountId, bucket,
                        arguments["cursor"]?.jsonPrimitive?.contentOrNull,
                        arguments["per_page"]?.jsonPrimitive?.intOrNull ?: 100,
                    )?.let(::safeExternalJson) ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "R2 客户端未初始化")
                }
                "computer_r2_get_metadata" -> {
                    val bucket = arguments.requireText("bucket")
                    requestResources?.getR2Metadata(accountId, bucket, arguments.requireText("key"))?.let(::safeExternalJson)
                        ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "R2 客户端未初始化")
                }
                "computer_r2_upload" -> {
                    val bytes = r2Bytes ?: throw CloudflareApiException("WORKSPACE_NOT_FOUND", "R2 上传文件未冻结")
                    val bucket = arguments.requireText("bucket")
                    val key = arguments.requireText("key")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(context.computerId, accountId, "R2_UPLOAD", "$bucket/$key", sha256(bytes), "上传 R2 对象：$bucket/$key", operationKey) {
                        requestResources?.uploadR2Object(accountId, bucket, key, bytes)
                            ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "R2 客户端未初始化")
                    }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "R2 上传结果未知，请查询对象元数据")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "R2 上传已明确失败")
                    buildJsonObject { put("ok", true); put("status", status.name) }
                }
                "computer_r2_delete" -> {
                    val bucket = arguments.requireText("bucket")
                    val key = arguments.requireText("key")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(context.computerId, accountId, "R2_DELETE", "$bucket/$key", "delete", "删除 R2 对象：$bucket/$key", operationKey) {
                        requestResources?.deleteR2Object(accountId, bucket, key)
                            ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "R2 客户端未初始化")
                    }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "R2 删除结果未知，请查询对象元数据")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "R2 删除已明确失败")
                    buildJsonObject { put("ok", true); put("status", status.name) }
                }
                "computer_durable_objects_list" -> requestResources?.listDurableObjectNamespaces(accountId)?.also { resourceIndex?.rememberPage(context.computerId, accountId, "DO_NAMESPACE", it) }?.let(::safeExternalJson)
                    ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                "computer_durable_objects_list_objects" -> {
                    val namespaceId = arguments.requireText("namespace_id")
                    requestResources?.listDurableObjectInstances(accountId, namespaceId,
                        arguments["cursor"]?.jsonPrimitive?.contentOrNull,
                        arguments["limit"]?.jsonPrimitive?.intOrNull ?: 100,
                    )?.let(::safeExternalJson)
                        ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                }
                "computer_queues_list" -> requestResources?.listQueues(accountId)?.also { resourceIndex?.rememberPage(context.computerId, accountId, "QUEUE", it) }?.let(::safeExternalJson)
                    ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                "computer_queues_get" -> {
                    val queueId = arguments.requireText("queue_id")
                    requestResources?.getQueue(accountId, queueId)?.let(::safeExternalJson)
                    ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                }
                "computer_queues_metrics" -> {
                    val queueId = arguments.requireText("queue_id")
                    requestResources?.queueMetrics(accountId, queueId)?.let(::safeExternalJson)
                        ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                }
                "computer_queues_peek" -> {
                    val queueId = arguments.requireText("queue_id")
                    val batchSize = arguments["batch_size"]?.jsonPrimitive?.intOrNull ?: 10
                    val raw = requestResources?.peekQueue(accountId, queueId, batchSize)
                        ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                    safeQueuePeek(raw)
                }
                "computer_queues_create" -> {
                    val name = arguments.requireText("name")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(context.computerId, accountId, "QUEUE_CREATE", name, "create", "创建 Queue：$name", operationKey) {
                        requestResources?.createQueue(accountId, name)
                            ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "Queue 客户端未初始化")
                    }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "Queue 创建结果未知，请先查询 Queue 列表")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "Queue 创建已明确失败")
                    buildJsonObject { put("ok", true); put("status", status.name); put("queue_name", name) }
                }
                "computer_queues_delete" -> {
                    val queueId = arguments.requireText("queue_id")
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(context.computerId, accountId, "QUEUE_DELETE", queueId, "delete", "删除 Queue：$queueId", operationKey) {
                        requestResources?.deleteQueue(accountId, queueId)
                            ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "Queue 客户端未初始化")
                    }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "Queue 删除结果未知，请先查询 Queue")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "Queue 删除已明确失败")
                    buildJsonObject { put("ok", true); put("status", status.name) }
                }
                "computer_cron_list" -> requestResources?.listWorkerSchedules(accountId, arguments.requireText("worker_name"))?.let(::safeExternalJson) ?: failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化")
                "computer_cron_update" -> {
                    val change = context.approvedCloudflareCronChange
                        ?: return failure("CONFIRMATION_REQUIRED", "Cron 修改需要先读取旧值并确认")
                    val requested = CloudflareCronSchedules.fromArguments(arguments)
                    val workerName = arguments.requireText("worker_name")
                    if (change.toolCallId != toolCallId || change.context.runId != context.runId || change.accountId != accountId || change.context.computerId != context.computerId ||
                        change.context.workspaceId != context.workspaceId || change.authorizationId != config.authorizationId ||
                        change.workerName != workerName || change.schedules != requested ||
                        change.requestHash != cronRequestHash(accountId, workerName, change.previousSchedules, requested)
                    ) {
                        return failure("APPROVAL_MISMATCH", "Cron 批准内容与当前请求不一致")
                    }
                    requireCronAccess(config, context, change.generation)
                    val actual = CloudflareCronSchedules.fromResponse(requestResources?.listWorkerSchedules(accountId, change.workerName)
                        ?: return failure("RESOURCE_CLIENT_UNAVAILABLE", "资源客户端未初始化"))
                    if (actual != change.previousSchedules) return failure("RESOURCE_CHANGED", "Cron 旧值已变化，请重新读取并确认")
                    // 读取旧值期间用户可能退出登录或切换 Account；写请求前再次检查授权及绑定。
                    requireCronAccess(config, context, change.generation)
                    val manager = requireResourceOperationManager(context)
                    val status = manager.run(
                        context.computerId,
                        accountId,
                        "CRON_UPDATE",
                        change.workerName,
                        change.requestHash,
                        "更新 Worker Cron：旧值已核对",
                        operationKey,
                    ) {
                        requestResources.updateWorkerSchedules(accountId, change.workerName, change.schedules)
                    }
                    if (status == CloudflareResourceOperationStatus.RESULT_UNKNOWN) return failure("RESULT_UNKNOWN", "Cron 修改结果未知，请先重新读取触发器")
                    if (status == CloudflareResourceOperationStatus.FAILED) return failure("OPERATION_FAILED", "Cron 修改已明确失败")
                    buildJsonObject { put("ok", true); put("worker_name", change.workerName); put("schedules", CloudflareCronSchedules.encode(change.schedules)); put("request_hash", change.requestHash); put("status", status.name) }
                }
                else -> failure("UNKNOWN_PROVIDER_TOOL", "Cloudflare Provider 不支持该工具")
            }
        } finally {
            ownedResources?.close()
            ownedApi?.close()
            updateStatus(null)
        }
    }

    /** 所有资源写入都必须拥有持久化账本；未初始化时在发送请求前失败。 */
    private suspend fun requireResourceOperationManager(context: ComputerRequestContext): CloudflareResourceOperationManager =
        resourceOperationManagerFactory?.invoke(context.computerId)
            ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "资源写入账本未初始化，未发送请求")

    /** 审批和写请求共用的 Cron 权限边界，不接受旧 Account 或旧 generation 的授权。 */
    private suspend fun requireCronAccess(
        config: CloudflareComputerConfig,
        context: ComputerRequestContext,
        expectedGeneration: Long? = null,
    ): CloudflareAuthorizationRecord {
        if (featureFlags?.let { !it.cloudflareEnabled || !it.cloudflareResourceToolsEnabled || !it.cloudflareWorkerWriteEnabled } == true) {
            throw CloudflareApiException("FEATURE_DISABLED", "Cloudflare Cron 写操作当前未开启")
        }
        val current = configLookup(context.computerId)
        if (current != config || config.computerId != context.computerId || context.workspaceId.isBlank()) {
            throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "Cloudflare 目标绑定已变化")
        }
        val auth = authorizationLookup?.invoke(config.authorizationId)
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权不存在")
        context.requireCloudflareBinding(config, auth)
        // 到期与否交给取 Token 那一层判断：它会先用 refresh_token 续期，续不上才报失效。
        // 这里如果按 expiresAt 直接拒绝，续期逻辑永远走不到（路由检查排在取 Token 之前）。
        if (auth.authorizationId != config.authorizationId || auth.revoked ||
            (expectedGeneration != null && auth.generation != expectedGeneration)
        ) throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权已失效，请重新确认")
        if (ComputerCapability.WORKER_UPDATE !in config.capabilities || !auth.hasAnyScope(WORKER_WRITE_SCOPES)) {
            throw CloudflareApiException("PERMISSION_DENIED", "Cloudflare 授权缺少 Worker 写权限")
        }
        return auth
    }

    /** 非 Cron 写操作的统一授权边界；审批前和执行前都必须经过同一校验。 */
    private suspend fun requireCloudflareAccess(
        config: CloudflareComputerConfig,
        context: ComputerRequestContext,
        toolName: String,
        arguments: JsonObject,
    ): CloudflareAuthorizationRecord {
        if (featureFlags?.cloudflareEnabled == false ||
            (!toolName.startsWith("computer_worker_") && featureFlags?.cloudflareResourceToolsEnabled == false) ||
            (toolName.startsWith("computer_worker_") && !ComputerToolCallSafety.isReadOnly(toolName, arguments) && featureFlags?.cloudflareWorkerWriteEnabled == false)) {
            throw CloudflareApiException("FEATURE_DISABLED", "Cloudflare 功能当前未开启")
        }
        if (configLookup(context.computerId) != config || context.computerId != config.computerId || context.workspaceId.isBlank()) {
            throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "Cloudflare 目标绑定已变化")
        }
        // 显式提供的目标只能与可信上下文一致；模型不能用参数覆盖当前目标。
        mapOf("computer_id" to config.computerId, "workspace_id" to context.workspaceId, "account_id" to config.accountId).forEach { (key, expected) ->
            if (arguments[key] != null && (arguments[key] as? JsonPrimitive)?.contentOrNull != expected) {
                throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "Cloudflare 目标参数与当前绑定不一致")
            }
        }
        val auth = authorizationLookup?.invoke(config.authorizationId)
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权不存在")
        context.requireCloudflareBinding(config, auth)
        // 同上：过期只代表需要续期，不代表要重新登录。
        if (auth.authorizationId != config.authorizationId || auth.revoked) {
            throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权已失效")
        }
        val writesD1 = toolName == ComputerToolNames.D1_QUERY && !ComputerToolCallSafety.isReadOnly(toolName, arguments)
        val capability = if (writesD1) ComputerCapability.D1_WRITE else capabilityFor(toolName)
        val scopes = if (writesD1) setOf("d1.write") else requiredScopes(toolName)
        if (capability == null || capability !in config.capabilities || !auth.hasAnyScope(scopes)) {
            throw CloudflareApiException("PERMISSION_DENIED", "当前 Cloudflare 授权缺少该操作所需的能力或 scope")
        }
        requireResourceTarget(config, context, toolName, arguments)
        return auth
    }

    /** 列表读取与所有后续资源调用共用校验，审批前即阻止缺少目标的写操作。 */
    private suspend fun requireResourceTarget(config: CloudflareComputerConfig, context: ComputerRequestContext, toolName: String, arguments: JsonObject) {
        val target = cloudflareResourceTarget(toolName) ?: return
        val id = (arguments[target.parameter] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw CloudflareApiException("RESOURCE_SELECTION_REQUIRED", "请先选择 ${target.kind} 资源")
        val index = resourceIndex ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "资源索引未初始化，未发送请求")
        if (index.isKnown(context.computerId, config.accountId, target.kind, id)) return
        // 索引只保留 10 分钟。过期只说明本地缓存旧了，不代表用户没授权：
        // 先由 App 自己重新列一次。
        refreshResourceIndex(config, context, target)
        if (index.isKnown(context.computerId, config.accountId, target.kind, id)) return
        // 模型给了名字但当前 Account 里没有：这是执行错误，直接告诉模型重新列一遍改名，
        // 不要把用户拉进来替它在已有资源里选一个它本来就不想要的目标。
        throw CloudflareApiException(
            "RESOURCE_NOT_FOUND",
            "当前 Account 没有 ${target.kind} 资源「$id」，请先用列表工具确认名称",
        )
    }

    /** 重新读取该类资源的列表并写回索引；刷新失败按未命中处理，不把网络错误当成权限结论。 */
    private suspend fun refreshResourceIndex(
        config: CloudflareComputerConfig,
        context: ComputerRequestContext,
        target: CloudflareResourceTarget,
    ) {
        val index = resourceIndex ?: return
        val accountId = config.accountId
        val ownedApi = apiFactory?.invoke(context)
        val ownedResources = resourcesFactory?.invoke(context)
        try {
            val requestApi = ownedApi ?: api
            val requestResources = ownedResources ?: resources
            when (target.kind) {
                "WORKER" -> requestApi?.let {
                    index.rememberWorkers(context.computerId, accountId, it.listWorkers(accountId, 1, 1000).workers)
                }
                "D1" -> requestResources?.let { index.rememberPage(context.computerId, accountId, "D1", it.listD1(accountId)) }
                "KV_NAMESPACE" -> requestResources?.let { index.rememberPage(context.computerId, accountId, "KV_NAMESPACE", it.listKvNamespaces(accountId)) }
                "R2_BUCKET" -> requestResources?.let { index.rememberPage(context.computerId, accountId, "R2_BUCKET", it.listR2Buckets(accountId)) }
                "DO_NAMESPACE" -> requestResources?.let { index.rememberPage(context.computerId, accountId, "DO_NAMESPACE", it.listDurableObjectNamespaces(accountId)) }
                "QUEUE" -> requestResources?.let { index.rememberPage(context.computerId, accountId, "QUEUE", it.listQueues(accountId)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 交给后面的 requireKnown 决定是否转人工。
        } finally {
            ownedResources?.close()
            ownedApi?.close()
        }
    }


    private fun cloudflareSafeSummary(toolName: String, arguments: JsonObject, pack: WorkerPackage? = null): String = when {
        pack != null -> "Worker 部署包：${pack.entryPoint}，${pack.files.size} 个文件，${pack.totalBytes} 字节，${pack.bindings.size} 个资源绑定"
        toolName.contains("delete") -> "删除操作，资源标识已校验"
        toolName.contains("upload") || toolName.contains("put") -> "写入操作，内容不会在审批卡片中展示"
        toolName.contains("migration") || toolName.contains("query") -> "执行 D1 SQL，原文不会在审批卡片中展示"
        else -> "云端配置写操作，参数已在执行前重新校验"
    }

    /**
     * 生成 Cloudflare 写操作的审批指纹。
     *
     * Worker 以 Workspace 部署时，工具参数里只有 Workspace 相对目录，
     * 因此仅对参数做 hash 会留下“审批后文件被替换仍可上传”的窗口。
     * 这里把经过 WorkerPackageBuilder 校验的完整包 hash 一并冻结；执行前
     * 会再次计算同一指纹，文件发生任何变化都会要求重新确认。
     */
    private suspend fun cloudflareRequestHash(
        accountId: String,
        toolName: String,
        arguments: JsonObject,
        context: ComputerRequestContext,
        pack: WorkerPackage? = null,
        r2Bytes: ByteArray? = null,
    ): String {
        val workspaceHash = if (toolName == ComputerToolNames.WORKER_DEPLOY &&
            arguments["script"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        ) {
            val root = workspaceRootLookup?.invoke(context.workspaceId)
                ?: throw CloudflareApiException("WORKSPACE_NOT_FOUND", "当前 Workspace 不存在")
            val subPath = arguments["workspace_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val project = File(root, subPath).canonicalFile
            require(project.toPath().startsWith(root.canonicalFile.toPath())) { "Workspace 路径越界" }
            (pack ?: WorkerPackageBuilder().build(project)).requestHash
        } else {
            arguments["script"]?.jsonPrimitive?.contentOrNull?.let { script ->
                sha256(script.toByteArray(Charsets.UTF_8))
            }.orEmpty()
        }
        val fileHash = if (toolName == ComputerToolNames.R2_UPLOAD) {
            val root = workspaceRootLookup?.invoke(context.workspaceId)
                ?: throw CloudflareApiException("WORKSPACE_NOT_FOUND", "当前 Workspace 不存在")
            val path = arguments["path"]?.jsonPrimitive?.contentOrNull
                ?: throw CloudflareApiException("INVALID_ARGUMENT", "R2 上传缺少 Workspace 文件路径")
            // 审批指纹必须包含待上传文件内容；只对当前 Workspace 读取，不能接受宿主绝对路径。
            sha256(r2Bytes ?: LocalWorkspaceFileBridge(maxFileBytes = 32L * 1024 * 1024).read(root, path))
        } else {
            ""
        }
        return sha256("$accountId\n$toolName\n${arguments}\n$workspaceHash\n$fileHash".toByteArray(Charsets.UTF_8))
    }

    private suspend fun requireWorkerBindings(
        config: CloudflareComputerConfig,
        context: ComputerRequestContext,
        pack: WorkerPackage,
    ) {
        // 没有 bindings 的普通 Worker 不需要资源索引；只有项目声明资源绑定时，
        // 才强制要求它们先来自当前 Computer/Account 的可信列表。
        if (pack.bindings.isEmpty()) return
        val index = resourceIndex ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "资源索引未初始化")
        pack.bindings.forEach { binding ->
            val kind = when (binding.type) {
                "d1" -> "D1"
                "kv_namespace" -> "KV_NAMESPACE"
                "r2_bucket" -> "R2_BUCKET"
                "durable_object_namespace" -> "DO_NAMESPACE"
                "queue" -> "QUEUE"
                else -> throw CloudflareApiException("INVALID_ARGUMENT", "Worker binding 类型无效")
            }
            index.requireKnownBinding(context.computerId, config.accountId, kind, binding.resourceId)
        }
    }

    /** 把模型只能看到的可信资源 ID 转换为 Cloudflare 部署协议需要的字段。 */
    private suspend fun resolveWorkerBindings(config: CloudflareComputerConfig, context: ComputerRequestContext, pack: WorkerPackage): WorkerPackage {
        if (pack.bindings.isEmpty()) return pack
        val index = resourceIndex ?: throw CloudflareApiException("RESOURCE_CLIENT_UNAVAILABLE", "资源索引未初始化")
        val bindings = pack.bindings.map { binding ->
            val kind = when (binding.type) {
                "d1" -> "D1"
                "kv_namespace" -> "KV_NAMESPACE"
                "r2_bucket" -> "R2_BUCKET"
                "durable_object_namespace" -> "DO_NAMESPACE"
                "queue" -> "QUEUE"
                else -> throw CloudflareApiException("INVALID_ARGUMENT", "Worker binding 类型无效")
            }
            val option = index.resolveKnownBinding(context.computerId, config.accountId, kind, binding.resourceId)
            binding.copy(resourceId = if (kind == "QUEUE") option.displayName else option.id)
        }
        return pack.copy(bindings = bindings)
    }

    private suspend fun workerPackageForRequest(
        toolName: String,
        arguments: JsonObject,
        context: ComputerRequestContext,
    ): WorkerPackage? {
        // 只有部署请求没有直接提供 script 时，才从当前 Workspace 打包。
        // 其他 Worker 操作以及带 script 的部署都不应读取本地文件。
        val script = arguments["script"]?.jsonPrimitive?.contentOrNull
        if (toolName != ComputerToolNames.WORKER_DEPLOY || !script.isNullOrBlank()) return null
        val root = workspaceRootLookup?.invoke(context.workspaceId)
            ?: throw CloudflareApiException("WORKSPACE_NOT_FOUND", "当前 Workspace 不存在")
        val rawPath = arguments["workspace_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val relative = ComputerWorkspacePath.normalize(rawPath.ifBlank { "." }, allowRoot = true)
        val project = File(root, relative).canonicalFile
        require(project.toPath().startsWith(root.canonicalFile.toPath())) { "Workspace 路径越界" }
        return WorkerPackageBuilder().build(project)
    }

    private fun failure(code: String, message: String, executionId: String? = null, retryable: Boolean = false) = buildJsonObject {
        put("ok", false); put("error_code", code); put("error", message)
        executionId?.let { put("execution_id", it) }
        put("retryable", retryable)
        if (code == "AUTHORIZATION_REQUIRED") {
            put("intervention_type", "REAUTHORIZATION")
            put("intervention_provider", "CLOUDFLARE")
        } else if (code == "RESOURCE_SELECTION_REQUIRED") {
            put("intervention_type", "RESOURCE_SELECTION")
            put("intervention_provider", "CLOUDFLARE")
        }
    }

    /** 日志先脱敏再裁剪；长行截断也要保留截断事实。 */
    private fun sanitizeExternalLog(value: String, maxChars: Int) =
        ComputerExternalOutput.text(value, maxChars, maxLines = 500, maxLineChars = 2_000)

    private fun sanitizeExternalText(value: String, maxChars: Int) =
        ComputerExternalOutput.text(value, maxChars)

    /** Queue Peek 只保留元数据，绝不把消息正文直接送入模型上下文。 */
    private fun safeQueuePeek(result: JsonObject): JsonObject {
        val resultObject = result["result"] as? kotlinx.serialization.json.JsonObject
            ?: return buildJsonObject { put("ok", true); put("message_count", 0) }
        val messages = resultObject["messages"] as? kotlinx.serialization.json.JsonArray
            ?: return buildJsonObject { put("ok", true); put("message_count", 0) }
        val summaries = kotlinx.serialization.json.JsonArray(messages.take(100).mapNotNull { item ->
            val message = item as? JsonObject ?: return@mapNotNull null
            buildJsonObject {
                listOf("id", "timestamp", "lease_id", "attempts").forEach { key ->
                    message[key]?.let { value -> put(key, ComputerExternalOutput.text(value.toString(), 200).text) }
                }
                put("body_omitted", true)
            }
        })
        return buildJsonObject {
            put("ok", true)
            put("message_count", summaries.size)
            put("messages", summaries)
            put("truncated", messages.size > summaries.size)
            put("untrusted_external_data", true)
        }
    }

    /** API 正文只能放进 data；远端同名错误或授权字段不能变为本地执行协议。 */
    private fun safeExternalJson(result: JsonObject): JsonObject = ComputerExternalOutput.apiResult(result)

    private fun limitD1Result(result: JsonObject): JsonObject = safeExternalJson(result)

    private fun redactValue(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val suspicious = Regex("(?i)(token|secret|password|passwd|authorization|cookie|private[_ -]?key|api[_ -]?key)")
        return if (suspicious.containsMatchIn(value) || value.length > 4_096 ||
            value.length >= 24 && value.matches(Regex("[A-Za-z0-9+/=_-]+"))) "[REDACTED]" else value
    }



    private fun capabilityFor(toolName: String): ComputerCapability? = when (toolName) {
        "computer_worker_list" -> ComputerCapability.WORKER_LIST
        "computer_worker_read" -> ComputerCapability.WORKER_READ
        "computer_worker_create" -> ComputerCapability.WORKER_CREATE
        "computer_worker_update" -> ComputerCapability.WORKER_UPDATE
        "computer_worker_deploy" -> ComputerCapability.WORKER_DEPLOY
        "computer_worker_status" -> ComputerCapability.WORKER_STATUS
        "computer_worker_logs" -> ComputerCapability.WORKER_LOGS
        ComputerToolNames.WORKER_HEALTH -> ComputerCapability.WORKER_STATUS
        "computer_worker_delete" -> ComputerCapability.WORKER_DELETE
        "computer_d1_list", "computer_d1_schema" -> ComputerCapability.D1_READ
        "computer_d1_query" -> ComputerCapability.D1_READ
        "computer_d1_migration" -> ComputerCapability.D1_WRITE
        "computer_kv_list_namespaces", "computer_kv_list_keys", "computer_kv_get" -> ComputerCapability.KV_READ
        "computer_kv_put", "computer_kv_delete" -> ComputerCapability.KV_WRITE
        "computer_r2_list_buckets", "computer_r2_list_objects" -> ComputerCapability.R2_READ
        "computer_r2_get_metadata" -> ComputerCapability.R2_READ
        "computer_r2_upload", "computer_r2_delete" -> ComputerCapability.R2_WRITE
        "computer_durable_objects_list", "computer_durable_objects_list_objects" -> ComputerCapability.DURABLE_OBJECTS_READ
        "computer_queues_list", "computer_queues_get", "computer_queues_metrics", "computer_queues_peek" -> ComputerCapability.QUEUES_READ
        "computer_queues_create", "computer_queues_delete" -> ComputerCapability.QUEUES_WRITE
        "computer_cron_list" -> ComputerCapability.WORKER_READ
        "computer_cron_update" -> ComputerCapability.WORKER_UPDATE
        ComputerToolNames.CRON_TRIGGER -> ComputerCapability.WORKER_UPDATE
        else -> null
    }

    /**
     * 工具能力与 OAuth scope 双重校验。
     *
     * 这里的名字必须与 Cloudflare 授权端点实际返回的 scope 标识一致（形如 workers-scripts.read），
     * 不能用自造的 workers:read 这类写法，否则真实授权永远匹配不上，所有工具都会被判成 PERMISSION_DENIED。
     * 同一族资源在 Cloudflare 侧有 read/write/edit/bind 多个标识，返回集合表示命中任意一个即可。
     */
    internal fun requiredScopes(toolName: String): Set<String> = when (toolName) {
        "computer_worker_list", "computer_worker_read", "computer_worker_status",
        ComputerToolNames.WORKER_HEALTH, "computer_cron_list" -> setOf("workers-scripts.read")
        "computer_worker_logs" -> setOf("workers-tail.read", "workers-scripts.read")
        "computer_worker_create", "computer_worker_update", "computer_worker_delete" -> WORKER_WRITE_SCOPES
        "computer_worker_deploy" -> WORKER_WRITE_SCOPES
        "computer_cron_update", ComputerToolNames.CRON_TRIGGER -> WORKER_WRITE_SCOPES
        "computer_d1_list", "computer_d1_schema", "computer_d1_query" -> setOf("d1.read")
        "computer_d1_migration" -> setOf("d1.write")
        "computer_kv_list_namespaces", "computer_kv_list_keys", "computer_kv_get" -> setOf("workers-kv-storage.read")
        "computer_kv_put", "computer_kv_delete" -> setOf("workers-kv-storage.write")
        "computer_r2_list_buckets", "computer_r2_list_objects", "computer_r2_get_metadata" ->
            setOf("workers-r2.read", "workers-r2-bucket-item.read")
        "computer_r2_upload", "computer_r2_delete" ->
            setOf("workers-r2-bucket-item.write", "workers-r2.write")
        "computer_durable_objects_list", "computer_durable_objects_list_objects" -> setOf("workers-scripts.read")
        "computer_queues_list", "computer_queues_get", "computer_queues_metrics", "computer_queues_peek" -> setOf("queues.read")
        "computer_queues_create", "computer_queues_delete" -> setOf("queues.write")
        else -> emptySet()
    }
}

/** Worker 写操作在 Cloudflare 侧可能落在 write/edit/bind 任一 scope 上。 */
private val WORKER_WRITE_SCOPES = setOf("workers-scripts.write", "workers-scripts.edit", "workers-scripts.bind")

/** 要求的 scope 为空表示该工具没有额外 scope 约束；否则必须命中当前授权实际授予的标识。 */
private fun CloudflareAuthorizationRecord.hasAnyScope(required: Set<String>): Boolean =
    required.isEmpty() || required.any { it in grantedScopes }

private fun cronRequestHash(accountId: String, workerName: String, previous: List<String>, next: List<String>): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(buildJsonObject {
        put("account_id", accountId); put("worker_name", workerName)
        put("previous", CloudflareCronSchedules.encode(previous)); put("next", CloudflareCronSchedules.encode(next))
    }.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

private fun JsonObject.requireText(name: String): String =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: throw CloudflareApiException("INVALID_ARGUMENT", "$name 无效")

private fun CloudflareWorkerListResult.toJson() = buildJsonObject {
    put("ok", true)
    put("page", page)
    put("per_page", perPage)
    put("total_count", totalCount)
    put("workers", kotlinx.serialization.json.JsonArray(workers.map { worker ->
        buildJsonObject { put("name", worker.id); worker.etag?.let { put("etag", it) } }
    }))
}
