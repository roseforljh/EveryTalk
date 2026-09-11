package com.android.everytalk.data.computer

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
    const val WORKER_LIST = "computer.worker.list"
    const val WORKER_READ = "computer.worker.read"
    const val WORKER_CREATE = "computer.worker.create"
    const val WORKER_UPDATE = "computer.worker.update"
    const val WORKER_DEPLOY = "computer.worker.deploy"
    const val WORKER_STATUS = "computer.worker.status"
    const val WORKER_LOGS = "computer.worker.logs"
    const val WORKER_HEALTH = "computer.worker.health"
    const val WORKER_DELETE = "computer.worker.delete"
    const val D1_LIST = "computer.d1.list"
    const val D1_SCHEMA = "computer.d1.schema"
    const val KV_LIST_NAMESPACES = "computer.kv.list_namespaces"
    const val R2_LIST_BUCKETS = "computer.r2.list_buckets"
    const val R2_LIST_OBJECTS = "computer.r2.list_objects"
    const val R2_GET_METADATA = "computer.r2.get_metadata"
    const val R2_UPLOAD = "computer.r2.upload"
    const val R2_DELETE = "computer.r2.delete"
    const val D1_QUERY = "computer.d1.query"
    const val D1_MIGRATION = "computer.d1.migration"
    const val KV_LIST_KEYS = "computer.kv.list_keys"
    const val KV_GET = "computer.kv.get"
    const val KV_PUT = "computer.kv.put"
    const val KV_DELETE = "computer.kv.delete"
    const val DO_LIST = "computer.durable_objects.list"
    const val DO_OBJECTS_LIST = "computer.durable_objects.list_objects"
    const val QUEUES_LIST = "computer.queues.list"
    const val QUEUES_GET = "computer.queues.get"
    const val QUEUES_METRICS = "computer.queues.metrics"
    const val QUEUES_PEEK = "computer.queues.peek"
    const val CRON_LIST = "computer.cron.list"
    const val CRON_UPDATE = "computer.cron.update"
    const val CRON_TRIGGER = "computer.cron.trigger"
    const val QUEUES_CREATE = "computer.queues.create"
    const val QUEUES_DELETE = "computer.queues.delete"

    val cloudflare = setOf(WORKER_LIST, WORKER_READ, WORKER_CREATE, WORKER_UPDATE, WORKER_DEPLOY, WORKER_STATUS, WORKER_LOGS, WORKER_HEALTH, WORKER_DELETE, D1_LIST, D1_SCHEMA, D1_QUERY, D1_MIGRATION, KV_LIST_NAMESPACES, KV_LIST_KEYS, KV_GET, KV_PUT, KV_DELETE, R2_LIST_BUCKETS, R2_LIST_OBJECTS, R2_GET_METADATA, R2_UPLOAD, R2_DELETE, DO_LIST, DO_OBJECTS_LIST, QUEUES_LIST, QUEUES_GET, QUEUES_METRICS, QUEUES_PEEK, QUEUES_CREATE, QUEUES_DELETE, CRON_LIST, CRON_UPDATE, CRON_TRIGGER)
    /** 兼容既有 SSH 工具契约；Cloudflare 工具通过 allProviders 单独识别。 */
    val all = setOf(EXEC, READ_FILE, WRITE_FILE, EDIT, TERMINAL, UPLOAD, DOWNLOAD, OPEN_PORT)
    val allProviders = all + cloudflare
}

/** 八个稳定的 Computer Tool Schema，服务器身份由 Android 请求快照注入，模型参数中不出现。 */
object ComputerToolCatalog {
    /** Cloudflare 工具单独声明，避免把 VPS exec schema 暴露给云端目标。 */
    fun cloudflareDefinitions(workerWriteEnabled: Boolean = true, resourceToolsEnabled: Boolean = true): List<Map<String, Any>> = listOf(
        function("computer.worker.list", "列出当前 Cloudflare Account 的 Workers。", mapOf("page" to integer("页码。", 1, 10000), "per_page" to integer("每页数量。", 1, 1000)), emptyList()),
        function("computer.worker.read", "读取 Worker 模块源码。", mapOf("worker_name" to string("Worker 名称。")), listOf("worker_name")),
        function("computer.worker.create", "创建或上传 Worker；必须先取得用户确认。", workerWriteProperties(), listOf("worker_name", "script")),
        function("computer.worker.update", "更新 Worker；必须先取得用户确认。", workerWriteProperties(), listOf("worker_name", "script")),
        function("computer.worker.deploy", "部署当前 Workspace 中的 Worker；必须先取得用户确认。", workerDeployProperties(), listOf("worker_name")),
        function("computer.worker.status", "查看 Worker 配置和最近部署状态。", mapOf("worker_name" to string("Worker 名称。")), listOf("worker_name")),
        function("computer.worker.logs", "查看 Worker 日志会话摘要。", mapOf("worker_name" to string("Worker 名称。")), listOf("worker_name")),
        function("computer.worker.health", "探测指定 Worker URL 的运行时 HTTP 状态；只返回状态码和耗时。", mapOf("url" to string("Worker 的 HTTPS URL，必须是 workers.dev 或 Cloudflare 域名。")), listOf("url")),
        function("computer.worker.delete", "删除 Worker；必须先取得用户确认。", mapOf("worker_name" to string("Worker 名称。")), listOf("worker_name")),
        function("computer.d1.list", "列出 D1 数据库。", emptyMap(), emptyList()),
        function("computer.d1.schema", "查看 D1 数据库结构。", mapOf("database_id" to string("D1 数据库 ID。")), listOf("database_id")),
        function("computer.d1.query", "执行 D1 查询；写查询必须先取得用户确认。", mapOf("database_id" to string("D1 数据库 ID。"), "sql" to string("SQL 查询。")), listOf("database_id", "sql")),
        function("computer.d1.migration", "执行 D1 migration；必须先取得用户确认且相同内容不会重复执行。", mapOf("database_id" to string("D1 数据库 ID。"), "sql" to string("Migration SQL。")), listOf("database_id", "sql")),
        function("computer.kv.list_namespaces", "列出 KV Namespace。", emptyMap(), emptyList()),
        function("computer.kv.list_keys", "列出 KV Key。", mapOf("namespace_id" to string("Namespace ID。"), "cursor" to string("下一页游标。"), "limit" to integer("每页数量。", 10, 1000)), listOf("namespace_id")),
        function("computer.kv.get", "读取 KV Value。", mapOf("namespace_id" to string("Namespace ID。"), "key" to string("Key。")), listOf("namespace_id", "key")),
        function("computer.kv.put", "写入 KV Value；必须先取得用户确认。", mapOf("namespace_id" to string("Namespace ID。"), "key" to string("Key。"), "value" to string("Value。")), listOf("namespace_id", "key", "value")),
        function("computer.kv.delete", "删除 KV Key；必须先取得用户确认。", mapOf("namespace_id" to string("Namespace ID。"), "key" to string("Key。")), listOf("namespace_id", "key")),
        function("computer.r2.list_buckets", "列出 R2 Bucket。", emptyMap(), emptyList()),
        function("computer.r2.list_objects", "列出 R2 对象。", mapOf("bucket" to string("Bucket 名称。"), "cursor" to string("下一页游标。"), "per_page" to integer("每页数量。", 1, 1000)), listOf("bucket")),
        function("computer.r2.get_metadata", "查看 R2 对象元数据。", mapOf("bucket" to string("Bucket 名称。"), "key" to string("对象 Key。")), listOf("bucket", "key")),
        function("computer.r2.upload", "上传当前 Workspace 文件到 R2；必须先取得用户确认。", mapOf("bucket" to string("Bucket 名称。"), "key" to string("对象 Key。"), "path" to string("当前 Workspace 内的相对文件路径。")), listOf("bucket", "key", "path")),
        function("computer.r2.delete", "删除 R2 对象；必须先取得用户确认。", mapOf("bucket" to string("Bucket 名称。"), "key" to string("对象 Key。")), listOf("bucket", "key")),
        function("computer.durable_objects.list", "列出 Durable Objects Namespace。", emptyMap(), emptyList()),
        function("computer.durable_objects.list_objects", "查看指定 Durable Objects Namespace 的实例列表。", mapOf("namespace_id" to string("Namespace ID。"), "cursor" to string("下一页游标。"), "limit" to integer("每页数量。", 10, 1000)), listOf("namespace_id")),
        function("computer.queues.list", "列出 Queues。", emptyMap(), emptyList()),
        function("computer.queues.get", "查看 Queue 配置和状态。", mapOf("queue_id" to string("Queue ID。")), listOf("queue_id")),
        function("computer.queues.metrics", "查看 Queue 指标。", mapOf("queue_id" to string("Queue ID。")), listOf("queue_id")),
        function("computer.queues.peek", "预览 Queue 消息；不会租赁或删除消息。", mapOf("queue_id" to string("Queue ID。"), "batch_size" to mapOf("type" to "integer", "description" to "最多预览的消息数。")), listOf("queue_id")),
        function("computer.queues.create", "创建 Queue；必须先取得用户确认。", mapOf("name" to string("Queue 名称。")), listOf("name")),
        function("computer.queues.delete", "删除 Queue；必须先取得用户确认。", mapOf("queue_id" to string("Queue ID。")), listOf("queue_id")),
        function("computer.cron.list", "查看 Worker Cron 触发器。", mapOf("worker_name" to string("Worker 名称。")), listOf("worker_name")),
        function("computer.cron.update", "修改 Worker Cron 触发器；必须先读取旧值并取得用户确认。", mapOf("worker_name" to string("Worker 名称。"), "schedules" to mapOf("type" to "array", "items" to mapOf("type" to "string"))), listOf("worker_name", "schedules")),
        function("computer.cron.trigger", "立即触发 Cron；当前 Cloudflare 官方 API 未提供该操作，调用会返回明确的不支持错误。", mapOf("worker_name" to string("Worker 名称。")), listOf("worker_name")),
    ).filter { definition ->
        // 工具定义的名称位于 function.name；读取外层 name 会让功能开关失效，
        // 从而在关闭写能力时仍把危险工具暴露给模型。
        val function = definition["function"] as? Map<*, *>
        val name = function?.get("name") as? String ?: ""
        val workerWrite = name in setOf(ComputerToolNames.WORKER_CREATE, ComputerToolNames.WORKER_UPDATE, ComputerToolNames.WORKER_DEPLOY, ComputerToolNames.WORKER_DELETE, ComputerToolNames.CRON_UPDATE, ComputerToolNames.QUEUES_CREATE, ComputerToolNames.QUEUES_DELETE)
        val resource = name.startsWith("computer.d1.") || name.startsWith("computer.kv.") || name.startsWith("computer.r2.") || name.startsWith("computer.durable_objects.") || name.startsWith("computer.queues.") || name.startsWith("computer.cron.")
        (!workerWrite || workerWriteEnabled) && (!resource || resourceToolsEnabled)
    }

    private fun workerWriteProperties(): Map<String, Any> = mapOf(
        "worker_name" to string("Worker 名称。"),
        "script" to string("Worker 模块入口源码；不得包含密钥。"),
    )

    private fun workerDeployProperties(): Map<String, Any> = mapOf(
        "worker_name" to string("Worker 名称。"),
        "script" to string("可选：直接提供 Worker 模块入口源码；不提供时从 Workspace 打包。"),
        "workspace_path" to string("可选：当前 Workspace 下的项目相对目录，默认是 Workspace 根目录。"),
    )
    fun definitions(
        permissionMode: ComputerPermissionMode = ComputerPermissionMode.MANUAL,
    ): List<Map<String, Any>> = listOf(
        function(
            name = ComputerToolNames.EXEC,
            description = execDescription(permissionMode),
            properties = buildMap {
                put("command", string("Command or shell script to run."))
                put(
                    "target",
                    enumStringWithDefault(
                        description = "Execution location. Defaults to the isolated Container.",
                        default = "container",
                        "container",
                        "host",
                    ),
                )
                put("cwd", string("Working directory. Container defaults to /workspace; host defaults to the SSH user's home directory."))
                put(
                    "env",
                    mapOf(
                        "type" to "object",
                        "description" to "Non-secret environment variables for target=container only.",
                        "additionalProperties" to mapOf("type" to "string"),
                    ),
                )
                // Secret 不再作为通用 exec 参数暴露；必须由可信语义 Adapter 履行。
                put("stdin", string("Optional UTF-8 stdin for target=container only."))
                put("timeout_ms", integer("Foreground timeout in milliseconds.", 1, 3_600_000))
                put("background", boolean("Start a persistent background process inside the Container only."))
                put("as_root", boolean("Run as root inside the Container only. Never use for target=host; use an explicit sudo command there."))
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
            description = "Read one page of a file inside the current /workspace.",
            properties = mapOf(
                "path" to string("Relative path or /workspace path."),
                "offset" to integer("Byte offset.", 0, Long.MAX_VALUE),
                "limit" to integer("Maximum bytes to return.", 1, 1_048_576),
                "encoding" to enumString("utf8", "base64"),
            ),
            required = listOf("path"),
        ),
        function(
            name = ComputerToolNames.WRITE_FILE,
            description = "Write a UTF-8 or base64 file inside the current /workspace.",
            properties = mapOf(
                "path" to string("Relative path or /workspace path."),
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
                "path" to string("Relative path or /workspace path."),
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
            description = "Open and interact with a PTY terminal for the current /workspace.",
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
            description = "Upload a local conversation attachment into the current /workspace.",
            properties = mapOf(
                "attachment_id" to string("Local attachment ID from the current conversation."),
                "destination_path" to string("Destination inside /workspace."),
                "overwrite" to boolean("Allow replacing an existing file."),
            ),
            required = listOf("attachment_id", "destination_path"),
        ),
        function(
            name = ComputerToolNames.DOWNLOAD,
            description = "Download a file from /workspace as a local EveryTalk attachment.",
            properties = mapOf(
                "source_path" to string("Source path inside /workspace."),
                "suggested_name" to string("Optional local file name."),
            ),
            required = listOf("source_path"),
        ),
        function(
            name = ComputerToolNames.OPEN_PORT,
            description = "Open an HTTP or HTTPS service. Use target=container for services created in /workspace and target=host for services already running on the VPS.",
            properties = buildMap {
                put("port", integer("Service port on the VPS or Workspace Container.", 1, 65_535))
                put(
                    "target",
                    enumStringWithDefault(
                        description = "Service location. Defaults to the Workspace Container.",
                        default = "container",
                        "container",
                        "host",
                    ),
                )
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
        return "Run a command on the user's selected server. Use target=container for code, scripts, builds, tests, package installs, and file-producing work. " +
            "Use target=host only to inspect or manage the VPS itself. $approvalText Combine related read-only diagnostics and cap output. " +
            "For basic VPS configuration, prefer one host call: hostname; uname -a; cat /etc/os-release; nproc; free -m; df -h."
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

    private fun enumStringWithDefault(
        description: String,
        default: String,
        vararg values: String,
    ): Map<String, Any> = mapOf(
        "type" to "string",
        "description" to description,
        "enum" to values.toList(),
        "default" to default,
    )
}
