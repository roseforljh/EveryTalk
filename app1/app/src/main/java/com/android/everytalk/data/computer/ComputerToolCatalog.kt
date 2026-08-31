package com.android.everytalk.data.computer

object ComputerToolNames {
    const val EXEC = "exec"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val EDIT = "edit"
    const val TERMINAL = "terminal"
    const val UPLOAD = "upload"
    const val DOWNLOAD = "download"
    const val OPEN_PORT = "open_port"

    val all = setOf(EXEC, READ_FILE, WRITE_FILE, EDIT, TERMINAL, UPLOAD, DOWNLOAD, OPEN_PORT)
}

/** 八个稳定的 Computer Tool Schema，服务器身份由 Android 请求快照注入，模型参数中不出现。 */
object ComputerToolCatalog {
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
