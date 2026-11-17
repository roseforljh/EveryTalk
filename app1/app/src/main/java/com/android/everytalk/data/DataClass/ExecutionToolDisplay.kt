package com.android.everytalk.data.DataClass

import com.android.everytalk.data.agent.McpToolDiscovery
import com.android.everytalk.data.computer.ComputerExternalOutput
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.mcp.McpToolLabels
import com.android.everytalk.data.memory.MemoryTool
import kotlinx.serialization.json.*

private fun JsonObject.operationText(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}.orEmpty()

/** 工具身份决定操作类型，参数只提供实际目标；query/url 不能证明发生了网页操作。 */
internal fun executionToolStep(id: String, name: String, arguments: JsonObject, reasoningBefore: String? = null): ExecutionStep {
    val tool = name.trim()
    val query = arguments.operationText("query", "q", "search_query", "searchQuery")
    val target = arguments.operationText("command", "cmd", "path", "destination_path", "source_path", "url", "href", "link", "query", "q", "port", "action")
    val type = when (tool) {
        in ComputerToolNames.all, "local_bash" -> ExecutionStepType.Agent
        "web_search", "web_search_exa" -> ExecutionStepType.Search
        "webfetch", "web_fetch", "web_fetch_exa" -> ExecutionStepType.Web
        else -> ExecutionStepType.Tool
    }
    val title = when (tool) {
        MemoryTool.NAME -> when (arguments.operationText("action")) {
            "search_conversations" -> "搜索聊天记录"
            "search" -> "搜索记忆"
            "read_summary" -> "读取会话摘要"
            "save" -> "保存记忆"
            "update" -> "更新记忆"
            "forget" -> "删除记忆"
            else -> "记忆"
        }
        McpToolDiscovery.NAME -> "搜索工具"
        "exec", "local_bash", "terminal" -> "执行命令"
        "read_file" -> "读取文件"
        "write_file" -> "写入文件"
        "edit" -> "编辑文件"
        "upload" -> "上传文件"
        "download" -> "下载文件"
        "open_port" -> "开放端口"
        "web_search", "web_search_exa" -> "搜索网页"
        "webfetch", "web_fetch", "web_fetch_exa" -> "读取网页"
        else -> McpToolLabels.display(tool)
    }
    val operation = when (tool) {
        MemoryTool.NAME -> query.ifBlank { arguments.operationText("conversation_id", "id", "content", "action") }
        McpToolDiscovery.NAME -> query
        else -> target
    }.ifBlank { McpToolLabels.display(tool) }
    return ExecutionStep(
        id = id.ifBlank { "$tool:${arguments.hashCode()}" }, type = type, title = title,
        labels = listOf(ComputerExternalOutput.text(operation, Int.MAX_VALUE).text.replace('\r', ' ').replace('\n', ' ').take(180)),
        toolName = tool, detail = arguments.takeIf { it.isNotEmpty() }?.toString(),
        reasoningBefore = reasoningBefore, fileChange = executionFileChange(tool, arguments),
        computerId = arguments.operationText("computer_id").takeIf(String::isNotBlank),
    )
}

/** 只用于展示：旧记录也按真实身份纠正，不改写库内原参数、执行状态或模型思考。 */
internal fun ExecutionStep.executionDisplay(): ExecutionStep {
    val arguments = detail?.takeIf { it.trimStart().startsWith('{') }?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }
    val normalized = if (!toolName.isNullOrBlank() && arguments != null) {
        executionToolStep(id, toolName, arguments)
    } else null
    val command = arguments?.operationText("command", "cmd").orEmpty()
    val readableDetail = when {
        command.isNotBlank() -> listOf(command, arguments?.operationText("cwd", "working_directory")?.takeIf(String::isNotBlank)?.let { "cwd: $it" }).filterNotNull().joinToString("\n")
        arguments != null -> ComputerExternalOutput.json(arguments).value.toString()
        else -> detail
    }
    fun safe(value: String): String = ComputerExternalOutput.text(value, Int.MAX_VALUE).text
    return copy(
        type = normalized?.type ?: type,
        title = safe(normalized?.title ?: McpToolLabels.display(title)),
        labels = (normalized?.labels ?: labels).map(::safe),
        detail = readableDetail?.let(::safe),
        resultPreview = resultPreview?.let(::safe),
    )
}

/** 分组比较原始参数而非被截短的胶囊，避免不同命令/查询只剩最后一条。 */
internal fun ExecutionStep.sameOperation(other: ExecutionStep): Boolean =
    type == other.type && toolName == other.toolName && computerId == other.computerId &&
        labels == other.labels && detail == other.detail && isError == other.isError &&
        completed == other.completed && started == other.started
