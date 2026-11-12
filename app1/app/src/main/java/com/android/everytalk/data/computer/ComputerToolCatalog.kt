package com.android.everytalk.data.computer

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage

/**
 * 恢复旧 Run 时也替换应用生成的 SSH 工具定义，并把当前规则作为 system 指令。
 * 活动任务快照会在发送时降为普通状态文本，不能用它覆盖旧 system 中的容器默认规则。
 * 只处理已绑定 SSH 工具的请求；用户聊天内容、其他工具和 Cloudflare 请求原样保留。
 */
internal fun refreshComputerExecutionPolicy(request: ChatRequest, sessionPrompt: String?): ChatRequest {
    val context = request.localComputerRequestContext ?: return request
    val tools = request.tools ?: return request
    if (tools.none { (it["function"] as? Map<*, *>)?.get("name") == ComputerToolNames.EXEC }) return request
    if (sessionPrompt == null || !sessionPrompt.contains("<computer-session-state>")) return request
    val environment = sessionPrompt.substringBefore("<computer-session-state>").trim()
    if (environment.isEmpty()) return request
    val policyId = "computer-execution-policy"
    val generatedHeaders = listOf(
        "Agent server tools are enabled for this request. The Android app connects to the VPS directly over SSH. ",
        "Agent server tools connect to the selected VPS directly over SSH. ",
    )
    val generatedEnd = "Available tools: ${ComputerToolNames.all.sorted().joinToString(", ")}."
    val messages = request.messages.filterNot { it.id == policyId }.mapNotNull { message ->
        if (message !is SimpleTextApiMessage || !message.role.equals("system", ignoreCase = true)) return@mapNotNull message
        // 只移除具有完整固定首尾的应用环境块，保留同条 system 中的用户规则及全部聊天内容。
        var content = message.content
        generatedHeaders.forEach { header ->
            val start = content.indexOf(header)
            val end = if (start >= 0) content.indexOf(generatedEnd, start) else -1
            if (end >= 0) content = content.removeRange(start, end + generatedEnd.length).trim()
        }
        if (content.isBlank()) null else if (content == message.content) message else message.copy(content = content)
    } +
        SimpleTextApiMessage(id = policyId, role = "system", content = environment)
    val currentTools = tools.filterNot { (it["function"] as? Map<*, *>)?.get("name") in ComputerToolNames.all } +
        if (context.selectedTargets.isEmpty()) ComputerToolCatalog.definitions(context.permissionMode)
        else multiComputerToolDefinitions(context).filter { (it["function"] as? Map<*, *>)?.get("name") in ComputerToolNames.all }
    return request.copy(messages = messages, tools = currentTools)
}

object ComputerToolNames {
    const val LOCAL_FILE_SAVE = "local_file_save"
    const val EXEC = "exec"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val EDIT = "edit"
    const val TERMINAL = "terminal"
    const val UPLOAD = "upload"
    const val DOWNLOAD = "download"
    const val OPEN_PORT = "open_port"
    const val WORKER_LIST = "computer_worker_list"
    const val WORKER_READ = "computer_worker_read"
    const val WORKER_CREATE = "computer_worker_create"
    const val WORKER_UPDATE = "computer_worker_update"
    const val WORKER_DEPLOY = "computer_worker_deploy"
    const val WORKER_STATUS = "computer_worker_status"
    const val WORKER_LOGS = "computer_worker_logs"
    const val WORKER_HEALTH = "computer_worker_health"
    const val WORKER_DELETE = "computer_worker_delete"
    const val D1_LIST = "computer_d1_list"
    const val D1_SCHEMA = "computer_d1_schema"
    const val KV_LIST_NAMESPACES = "computer_kv_list_namespaces"
    const val R2_LIST_BUCKETS = "computer_r2_list_buckets"
    const val R2_LIST_OBJECTS = "computer_r2_list_objects"
    const val R2_GET_METADATA = "computer_r2_get_metadata"
    const val R2_UPLOAD = "computer_r2_upload"
    const val R2_DELETE = "computer_r2_delete"
    const val D1_QUERY = "computer_d1_query"
    const val D1_MIGRATION = "computer_d1_migration"
    const val KV_LIST_KEYS = "computer_kv_list_keys"
    const val KV_GET = "computer_kv_get"
    const val KV_PUT = "computer_kv_put"
    const val KV_DELETE = "computer_kv_delete"
    const val DO_LIST = "computer_durable_objects_list"
    const val DO_OBJECTS_LIST = "computer_durable_objects_list_objects"
    const val QUEUES_LIST = "computer_queues_list"
    const val QUEUES_GET = "computer_queues_get"
    const val QUEUES_METRICS = "computer_queues_metrics"
    const val QUEUES_PEEK = "computer_queues_peek"
    const val CRON_LIST = "computer_cron_list"
    const val CRON_UPDATE = "computer_cron_update"
    const val CRON_TRIGGER = "computer_cron_trigger"
    const val QUEUES_CREATE = "computer_queues_create"
    const val QUEUES_DELETE = "computer_queues_delete"

    val cloudflare = setOf(WORKER_LIST, WORKER_READ, WORKER_CREATE, WORKER_UPDATE, WORKER_DEPLOY, WORKER_STATUS, WORKER_LOGS, WORKER_HEALTH, WORKER_DELETE, D1_LIST, D1_SCHEMA, D1_QUERY, D1_MIGRATION, KV_LIST_NAMESPACES, KV_LIST_KEYS, KV_GET, KV_PUT, KV_DELETE, R2_LIST_BUCKETS, R2_LIST_OBJECTS, R2_GET_METADATA, R2_UPLOAD, R2_DELETE, DO_LIST, DO_OBJECTS_LIST, QUEUES_LIST, QUEUES_GET, QUEUES_METRICS, QUEUES_PEEK, QUEUES_CREATE, QUEUES_DELETE, CRON_LIST, CRON_UPDATE, CRON_TRIGGER)
    /** 兼容既有 SSH 工具契约；Cloudflare 工具通过 allProviders 单独识别。 */
    val all = setOf(EXEC, READ_FILE, WRITE_FILE, EDIT, TERMINAL, UPLOAD, DOWNLOAD, OPEN_PORT)
    val allProviders = all + cloudflare
}

/** 八个稳定的 Computer Tool Schema，服务器身份由 Android 请求快照注入，模型参数中不出现。 */
object ComputerToolCatalog {
    /** Cloudflare 工具单独声明，避免把 VPS exec schema 暴露给云端目标。 */
    fun cloudflareDefinitions(
        workerWriteEnabled: Boolean = true,
        resourceToolsEnabled: Boolean = true,
        permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
    ): List<Map<String, Any>> = listOf(
        function("computer_worker_list", "List Workers in the current Cloudflare account.", mapOf("page" to integer("Page number.", 1, 10000), "per_page" to integer("Items per page.", 1, 1000)), emptyList()),
        function("computer_worker_read", "Read the Worker module source.", mapOf("worker_name" to string("Worker name.")), listOf("worker_name")),
        function("computer_worker_create", "Create or upload a Worker.", workerWriteProperties(), listOf("worker_name", "script")),
        function("computer_worker_update", "Update a Worker.", workerWriteProperties(), listOf("worker_name", "script")),
        function("computer_worker_deploy", "Deploy the Worker from the current Workspace.", workerDeployProperties(), listOf("worker_name")),
        function("computer_worker_status", "Show Worker settings and recent deployment status.", mapOf("worker_name" to string("Worker name.")), listOf("worker_name")),
        function("computer_worker_logs", "Show a summary of the Worker log session.", mapOf("worker_name" to string("Worker name.")), listOf("worker_name")),
        function("computer_worker_health", "Probe the runtime HTTP status of a Worker URL; returns only the status code and latency.", mapOf("url" to string("HTTPS URL of the Worker on workers.dev or a Cloudflare domain.")), listOf("url")),
        function("computer_worker_delete", "Delete a Worker.", mapOf("worker_name" to string("Worker name.")), listOf("worker_name")),
        function("computer_d1_list", "List D1 databases.", emptyMap(), emptyList()),
        function("computer_d1_schema", "Show a D1 database schema.", mapOf("database_id" to string("D1 database ID.")), listOf("database_id")),
        function("computer_d1_query", "Run a D1 query.", mapOf("database_id" to string("D1 database ID."), "sql" to string("SQL query.")), listOf("database_id", "sql")),
        function("computer_d1_migration", "Apply a D1 migration; identical content is not applied twice.", mapOf("database_id" to string("D1 database ID."), "sql" to string("Migration SQL.")), listOf("database_id", "sql")),
        function("computer_kv_list_namespaces", "List KV namespaces.", emptyMap(), emptyList()),
        function("computer_kv_list_keys", "List KV keys.", mapOf("namespace_id" to string("Namespace ID."), "cursor" to string("Cursor for the next page."), "limit" to integer("Items per page.", 10, 1000)), listOf("namespace_id")),
        function("computer_kv_get", "Read a KV value.", mapOf("namespace_id" to string("Namespace ID."), "key" to string("Key.")), listOf("namespace_id", "key")),
        function("computer_kv_put", "Write a KV value.", mapOf("namespace_id" to string("Namespace ID."), "key" to string("Key."), "value" to string("Value.")), listOf("namespace_id", "key", "value")),
        function("computer_kv_delete", "Delete a KV key.", mapOf("namespace_id" to string("Namespace ID."), "key" to string("Key.")), listOf("namespace_id", "key")),
        function("computer_r2_list_buckets", "List R2 buckets.", emptyMap(), emptyList()),
        function("computer_r2_list_objects", "List R2 objects.", mapOf("bucket" to string("Bucket name."), "cursor" to string("Cursor for the next page."), "per_page" to integer("Items per page.", 1, 1000)), listOf("bucket")),
        function("computer_r2_get_metadata", "Show R2 object metadata.", mapOf("bucket" to string("Bucket name."), "key" to string("Object key.")), listOf("bucket", "key")),
        function("computer_r2_upload", "Upload a file from the current Workspace to R2.", mapOf("bucket" to string("Bucket name."), "key" to string("Object key."), "path" to string("File path relative to the current Workspace.")), listOf("bucket", "key", "path")),
        function("computer_r2_delete", "Delete an R2 object.", mapOf("bucket" to string("Bucket name."), "key" to string("Object key.")), listOf("bucket", "key")),
        function("computer_durable_objects_list", "List Durable Objects namespaces.", emptyMap(), emptyList()),
        function("computer_durable_objects_list_objects", "List instances of a Durable Objects namespace.", mapOf("namespace_id" to string("Namespace ID."), "cursor" to string("Cursor for the next page."), "limit" to integer("Items per page.", 10, 1000)), listOf("namespace_id")),
        function("computer_queues_list", "List Queues.", emptyMap(), emptyList()),
        function("computer_queues_get", "Show Queue settings and status.", mapOf("queue_id" to string("Queue ID.")), listOf("queue_id")),
        function("computer_queues_metrics", "Show Queue metrics.", mapOf("queue_id" to string("Queue ID.")), listOf("queue_id")),
        function("computer_queues_peek", "Peek at Queue messages without leasing or deleting them.", mapOf("queue_id" to string("Queue ID."), "batch_size" to mapOf("type" to "integer", "description" to "Maximum number of messages to peek.")), listOf("queue_id")),
        function("computer_queues_create", "Create a Queue.", mapOf("name" to string("Queue name.")), listOf("name")),
        function("computer_queues_delete", "Delete a Queue.", mapOf("queue_id" to string("Queue ID.")), listOf("queue_id")),
        function("computer_cron_list", "Show Worker cron triggers.", mapOf("worker_name" to string("Worker name.")), listOf("worker_name")),
        function("computer_cron_update", "Change Worker cron triggers; read the current ones first.", mapOf("worker_name" to string("Worker name."), "schedules" to mapOf("type" to "array", "items" to mapOf("type" to "string"))), listOf("worker_name", "schedules")),
        function("computer_cron_trigger", "Trigger a cron now. The official Cloudflare API does not offer this, so the call returns an explicit unsupported error.", mapOf("worker_name" to string("Worker name.")), listOf("worker_name")),
    ).filter { definition ->
        // 工具定义的名称位于 function.name；读取外层 name 会让功能开关失效，
        // 从而在关闭写能力时仍把危险工具暴露给模型。
        val function = definition["function"] as? Map<*, *>
        val name = function?.get("name") as? String ?: ""
        val workerWrite = name in setOf(ComputerToolNames.WORKER_CREATE, ComputerToolNames.WORKER_UPDATE, ComputerToolNames.WORKER_DEPLOY, ComputerToolNames.WORKER_DELETE, ComputerToolNames.CRON_UPDATE, ComputerToolNames.QUEUES_CREATE, ComputerToolNames.QUEUES_DELETE)
        val resource = name.startsWith("computer_d1_") || name.startsWith("computer_kv_") || name.startsWith("computer_r2_") || name.startsWith("computer_durable_objects_") || name.startsWith("computer_queues_") || name.startsWith("computer_cron_")
        (!workerWrite || workerWriteEnabled) && (!resource || resourceToolsEnabled)
    }.map { definition -> withSmartApprovalArgument(definition, permissionMode) }

    /**
     * SMART 模式下由模型自报是否打断用户，和 exec / open_port 保持一致。
     * 只读工具不加这个参数；判定复用执行期的 ComputerToolCallSafety，
     * 避免 schema 允许的取值和审批边界对不上。
     */
    private fun withSmartApprovalArgument(
        definition: Map<String, Any>,
        permissionMode: ComputerPermissionMode,
    ): Map<String, Any> {
        if (permissionMode != ComputerPermissionMode.SMART) return definition
        val function = definition["function"] as? Map<*, *> ?: return definition
        val name = function["name"] as? String ?: return definition
        if (ComputerToolCallSafety.isReadOnly(name, kotlinx.serialization.json.JsonObject(emptyMap()))) return definition
        val parameters = function["parameters"] as? Map<*, *> ?: return definition
        val properties = parameters["properties"] as? Map<*, *> ?: return definition
        val required = parameters["required"] as? List<*> ?: emptyList<Any>()
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to (function["description"] ?: ""),
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to (
                        properties + mapOf(
                            "ask_user_approval" to boolean(
                                "Required in smart approval mode. Set true only when this operation should pause for the user's approval; otherwise set false.",
                            ),
                        )
                        ),
                    "required" to (required + "ask_user_approval"),
                    "additionalProperties" to false,
                ),
            ),
        )
    }

    private fun workerWriteProperties(): Map<String, Any> = mapOf(
        "worker_name" to string("Worker name."),
        "script" to string("Worker module entry source; must not contain secrets."),
    )

    private fun workerDeployProperties(): Map<String, Any> = mapOf(
        "worker_name" to string("Worker name."),
        "script" to string("Optional Worker module entry source; when omitted the Workspace is bundled."),
        "workspace_path" to string("Optional project directory relative to the Workspace; defaults to the Workspace root."),
    )
    fun definitions(
        permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
    ): List<Map<String, Any>> = listOf(
        function(
            name = ComputerToolNames.EXEC,
            description = execDescription(permissionMode),
            properties = buildMap {
                put("command", string("Command or shell script to run."))
                put("cwd", string("Absolute working directory or ~. Defaults to the SSH user's home directory."))
                put("timeout_ms", integer("Timeout in milliseconds.", 1, 3_600_000))
                if (permissionMode == ComputerPermissionMode.SMART) {
                    put(
                        "ask_user_approval",
                        boolean("Required in smart approval mode. Set true only when this operation should pause for the user's approval; otherwise set false."),
                    )
                }
            },
            required = buildList {
                add("command")
                if (permissionMode == ComputerPermissionMode.SMART) add("ask_user_approval")
            },
        ),
        function(
            name = ComputerToolNames.READ_FILE,
            description = "Read one page of a file on the SSH server.",
            properties = mapOf(
                "path" to string("Absolute path, or a path relative to the SSH user's home."),
                "offset" to integer("Byte offset.", 0, Long.MAX_VALUE),
                "limit" to integer("Maximum bytes to return.", 1, 1_048_576),
                "encoding" to enumString("utf8", "base64"),
            ),
            required = listOf("path"),
        ),
        function(
            name = ComputerToolNames.WRITE_FILE,
            description = "Write a UTF-8 or base64 file on the SSH server. Existing file permissions are kept.",
            properties = mapOf(
                "path" to string("Absolute path, or a path relative to the SSH user's home."),
                "content" to string("UTF-8 text or base64 data."),
                "encoding" to enumString("utf8", "base64"),
                "mode" to enumString("overwrite", "append"),
                "create_parents" to boolean("Create missing parent directories."),
            ),
            required = listOf("path", "content"),
        ),
        function(
            name = ComputerToolNames.EDIT,
            description = "Edit one file with one or more precise text replacements. Every oldText must be unique in the original file, and edits must not overlap.",
            properties = mapOf(
                "path" to string("Absolute path, or a path relative to the SSH user's home."),
                "edits" to mapOf(
                    "type" to "array",
                    "description" to "Targeted replacements matched against the original file.",
                    "minItems" to 1,
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "oldText" to string("Exact text for one unique targeted replacement."),
                            "newText" to string("Replacement text for this targeted edit."),
                        ),
                        "required" to listOf("oldText", "newText"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            required = listOf("path", "edits"),
        ),
        function(
            name = ComputerToolNames.TERMINAL,
            description = "Open and interact with a PTY terminal on the SSH server, starting in the user's home.",
            properties = mapOf(
                "action" to enumString("open", "write", "read", "resize", "close"),
                "terminal_id" to string("Terminal ID returned by open."),
                "input" to string("Text to write to the terminal."),
                "cursor" to integer("Read cursor returned by the previous read.", 0, Long.MAX_VALUE),
                "cols" to integer("Terminal columns.", 1, 1000),
                "rows" to integer("Terminal rows.", 1, 1000),
            ),
            required = listOf("action"),
        ),
        function(
            name = ComputerToolNames.UPLOAD,
            description = "Upload a conversation attachment to the selected SSH server. Accepts attachment_id returned by download from another selected server in this conversation; no session switch or server-to-server SSH credentials are needed.",
            properties = mapOf(
                "attachment_id" to string("Local attachment ID from the current conversation."),
                "destination_path" to string("Absolute destination path, or a path relative to the SSH user's home."),
                "overwrite" to boolean("Allow replacing an existing file."),
            ),
            required = listOf("attachment_id", "destination_path"),
        ),
        function(
            name = ComputerToolNames.DOWNLOAD,
            description = "Send a deliverable file from the server to the client: it is saved to the phone under Download/App/AppTalk and attached to the chat. Call this after creating any file the user needs instead of only returning a server path; after success, link it where you describe it as [file name](returned file_link). For server-to-server transfer, pass the returned attachment_id to the other server's upload.",
            properties = mapOf(
                "source_path" to string("Absolute source path, or a path relative to the SSH user's home."),
                "suggested_name" to string("Optional local file name."),
            ),
            required = listOf("source_path"),
        ),
        function(
            name = ComputerToolNames.OPEN_PORT,
            description = "Open an HTTP or HTTPS service running on the SSH server.",
            properties = buildMap {
                put("port", integer("Service port on the VPS.", 1, 65_535))
                put("protocol", enumString("http", "https"))
                put("visibility", enumString("private", "public"))
                put("expires_in_seconds", integer("Optional public preview expiry.", 60, 604_800))
                if (permissionMode == ComputerPermissionMode.SMART) {
                    put(
                        "ask_user_approval",
                        boolean("Required in smart approval mode. Set true only when opening this port should pause for the user's approval; otherwise set false."),
                    )
                }
            },
            required = buildList {
                add("port")
                if (permissionMode == ComputerPermissionMode.SMART) add("ask_user_approval")
            },
        ),
    )

    private fun function(
        name: String,
        description: String,
        properties: Map<String, Any>,
        required: List<String>,
    ): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required,
                "additionalProperties" to false,
            ),
        ),
    )

    private fun execDescription(permissionMode: ComputerPermissionMode): String {
        val approvalText = when (permissionMode) {
            ComputerPermissionMode.MANUAL ->
                "The app applies its local approval policy to host operations."
            ComputerPermissionMode.SMART ->
                "You must decide whether to ask the user by setting ask_user_approval."
            ComputerPermissionMode.FULL ->
                "Valid operations execute without an approval prompt."
        }
        return "Run a command directly on the user's selected server over SSH. " + approvalText
    }

    private fun string(description: String): Map<String, Any> = mapOf(
        "type" to "string",
        "description" to description,
    )

    private fun boolean(description: String): Map<String, Any> = mapOf(
        "type" to "boolean",
        "description" to description,
    )

    private fun integer(description: String, minimum: Long, maximum: Long): Map<String, Any> = mapOf(
        "type" to "integer",
        "description" to description,
        "minimum" to minimum,
        "maximum" to maximum,
    )

    private fun enumString(vararg values: String): Map<String, Any> = mapOf(
        "type" to "string",
        "enum" to values.toList(),
    )
}
