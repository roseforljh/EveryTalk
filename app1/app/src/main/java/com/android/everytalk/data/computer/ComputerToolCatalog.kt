package com.android.everytalk.data.computer

object ComputerToolNames {
    const val EXEC = "exec"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val TERMINAL = "terminal"
    const val UPLOAD = "upload"
    const val DOWNLOAD = "download"
    const val OPEN_PORT = "open_port"

    val all = setOf(EXEC, READ_FILE, WRITE_FILE, TERMINAL, UPLOAD, DOWNLOAD, OPEN_PORT)
}

/** 七个稳定的 Computer Tool Schema，服务器身份由 Android 请求快照注入，模型参数中不出现。 */
object ComputerToolCatalog {
    fun definitions(): List<Map<String, Any>> = listOf(
        function(
            name = ComputerToolNames.EXEC,
            description = "Run a command in the current persistent /workspace on the user's selected server.",
            properties = mapOf(
                "command" to string("Command or shell script to run."),
                "cwd" to string("Working directory inside /workspace. Defaults to /workspace."),
                "env" to mapOf(
                    "type" to "object",
                    "description" to "Non-secret environment variables.",
                    "additionalProperties" to mapOf("type" to "string"),
                ),
                "secret_names" to mapOf(
                    "type" to "array",
                    "description" to "Names of workspace secrets to inject for this command.",
                    "items" to mapOf("type" to "string"),
                    "uniqueItems" to true,
                ),
                "stdin" to string("Optional UTF-8 stdin."),
                "timeout_ms" to integer("Foreground timeout in milliseconds.", 1, 3_600_000),
                "background" to boolean("Start a persistent background process."),
                "as_root" to boolean("Run as root inside Container mode only."),
            ),
            required = listOf("command"),
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
            description = "Open a private phone-local preview or request a confirmed public port for a service.",
            properties = mapOf(
                "port" to integer("Service port on the VPS or Workspace Container.", 1, 65_535),
                "protocol" to enumString("http", "https"),
                "visibility" to enumString("private", "public"),
                "expires_in_seconds" to integer("Optional public preview expiry.", 60, 604_800),
            ),
            required = listOf("port"),
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
