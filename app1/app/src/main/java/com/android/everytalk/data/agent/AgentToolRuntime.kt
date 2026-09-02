package com.android.everytalk.data.agent

import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolApprovalPhase
import com.android.everytalk.data.computer.ComputerToolApprovalProvider
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.network.WebSearchToolResultExtractor
import com.android.everytalk.data.network.computerExecutionCompletedEvent
import com.android.everytalk.data.network.estimateToolLoopJsonTokens
import com.android.everytalk.data.network.truncateToolOutput
import com.android.everytalk.data.DataClass.AgentToolResultContentApiPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.android.everytalk.data.skill.SkillRuntimeTools

private const val MAX_AGENT_TOOL_RESULT_TOKENS = 64_000L

/**
 * 统一工具执行入口。服务器三档权限仍由 ComputerToolExecutor 内的公共策略处理。
 */
class AgentToolRuntime(
    private val executorProvider: () -> AppToolExecutor?,
    private val approvalProvider: () -> ComputerToolApprovalProvider? = AgentToolExecutorRegistry::currentApprovalProvider,
    private val resultStore: AgentToolResultStore? = null,
    private val skillRuntimeTools: SkillRuntimeTools? = null,
) {
    /** 审批预检不连接 VPS，也不创建 ComputerExecution。 */
    suspend fun approvalRequest(
        call: AgentContentBlock.ToolCall,
        computerContext: ComputerRequestContext?,
        phase: ComputerToolApprovalPhase = ComputerToolApprovalPhase.BEFORE_EXECUTION,
    ): ComputerToolApprovalRequest? = preparePiToolCallArguments(call).let { prepared ->
        approvalProvider()?.invoke(
            prepared.name,
            prepared.arguments,
            prepared.id,
            computerContext,
            phase,
        )
    }

    suspend fun execute(
        call: AgentContentBlock.ToolCall,
        computerContext: ComputerRequestContext?,
        maxModelResultTokens: Long = MAX_AGENT_TOOL_RESULT_TOKENS,
        runId: String? = null,
        emit: suspend (AppStreamEvent) -> Unit,
    ): AgentContentBlock.ToolResult {
        val preparedCall = preparePiToolCallArguments(call)
        if (runId != null && skillRuntimeTools?.handles(preparedCall.name) == true) {
            return try {
                val name = skillRuntimeTools.displayName(preparedCall, runId) ?: preparedCall.name
                emit(AppStreamEvent.ExecutionStatusUpdate("正在读取技能：$name"))
                AgentContentBlock.ToolResult(
                    toolCallId = preparedCall.id,
                    toolName = preparedCall.name,
                    content = skillRuntimeTools.execute(preparedCall, runId),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                AgentContentBlock.ToolResult(
                    toolCallId = preparedCall.id,
                    toolName = preparedCall.name,
                    content = JsonPrimitive(error.message ?: "Skill 读取失败"),
                    isError = true,
                )
            } finally {
                emit(AppStreamEvent.ExecutionStatusUpdate(null))
            }
        }
        val executor = executorProvider()
            ?: return AgentContentBlock.ToolResult(
                toolCallId = preparedCall.id,
                toolName = preparedCall.name,
                content = kotlinx.serialization.json.JsonPrimitive("工具执行器未初始化"),
                isError = true,
            )
        return try {
            val execution = executor(
                preparedCall.name,
                preparedCall.arguments,
                preparedCall.id,
                computerContext,
            ) { status -> emit(AppStreamEvent.ExecutionStatusUpdate(status)) }
            val raw = execution.content
            computerExecutionCompletedEvent(raw, preparedCall.id)?.let { emit(it) }
            WebSearchToolResultExtractor.extract(preparedCall.name, raw)
                .takeIf(List<*>::isNotEmpty)
                ?.let { emit(AppStreamEvent.WebSearchResults(it)) }
            val images = extractToolResultImages(raw)
            val modelRaw = stripUiOnlyFields(raw)
            val archive = runId?.let { resultStore?.archive(it, preparedCall.id, modelRaw) }
            val bounded = boundModelToolResult(
                result = modelRaw,
                maxTokens = maxModelResultTokens.coerceIn(64L, MAX_AGENT_TOOL_RESULT_TOKENS),
            )
            val isError = modelRaw.isToolFailureEnvelope()
            AgentContentBlock.ToolResult(
                toolCallId = preparedCall.id,
                toolName = preparedCall.name,
                content = bounded.content,
                // Computer、附件、Web 工具都用 ok=false 表示业务失败；不能继续伪装成成功结果。
                isError = isError,
                truncated = bounded.truncated,
                fullResultPath = archive?.relativePath,
                fullResultBytes = archive?.byteCount,
                fullResultSha256 = archive?.sha256,
                terminate = execution.terminate,
                contentBlocks = buildList {
                    add(AgentToolResultContentApiPart.Text(bounded.content.toToolResultText()))
                    addAll(images)
                },
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AgentContentBlock.ToolResult(
                toolCallId = preparedCall.id,
                toolName = preparedCall.name,
                content = kotlinx.serialization.json.JsonPrimitive(error.message ?: "工具执行失败"),
                isError = true,
            )
        }
    }

    private fun stripUiOnlyFields(result: JsonElement): JsonElement = (result as? JsonObject)
        ?.let { JsonObject(it.filterKeys { key -> key != "_images" }) }
        ?: result

    /**
     * 旧工具通过统一 `_images` envelope 返回图片。只在执行边界解析一次，
     * 后面的 Provider 一律读取中立 contentBlocks，不再认识这个旧字段。
     */
    private fun extractToolResultImages(result: JsonElement): List<AgentToolResultContentApiPart.Image> =
        ((result as? JsonObject)?.get("_images") as? JsonArray).orEmpty().mapNotNull { element ->
            val image = element as? JsonObject ?: return@mapNotNull null
            val data = image["base64"]?.jsonPrimitive?.contentOrNull
                ?.substringAfter(";base64,")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val mimeType = image["mimeType"]?.jsonPrimitive?.contentOrNull
                ?.lowercase()
                ?.takeIf { it.startsWith("image/") }
                ?: "image/jpeg"
            AgentToolResultContentApiPart.Image(data = data, mimeType = mimeType)
        }

    private fun JsonElement.toToolResultText(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        else -> toString()
    }

    /**
     * 公共工具约定同时兼容 `ok=false` 和只有 `error` 的旧失败对象。
     * 失败标志由本地执行边界判定，Provider Adapter 只负责把它写成各自官方 ToolResult 格式。
     */
    private fun JsonElement.isToolFailureEnvelope(): Boolean {
        val value = this as? JsonObject ?: return false
        val ok = value["ok"]?.jsonPrimitive?.booleanOrNull
        return ok == false || (ok != true && value["error"] != null)
    }

    /**
     * 统一限制所有工具回传给模型的内容，避免一个诊断命令挤满整个上下文。
     * SSH 层已保留 stdout/stderr 的截断元数据，这里继续保留首尾和原始字符数。
     */
    private fun boundModelToolResult(result: JsonElement, maxTokens: Long): BoundedToolResult {
        if (estimateToolLoopJsonTokens(result) <= maxTokens) {
            return BoundedToolResult(result, truncated = false)
        }
        val raw = result.toString()
        val bounded = truncateToolOutput(raw, maxTokens)
        return BoundedToolResult(
            content = JsonPrimitive(
                "$bounded\n[原始工具结果字符数：${raw.length}；发给模型的内容已截断]",
            ),
            truncated = true,
        )
    }
}

/** Pi `edit.prepareArguments`：先归一化兼容形态，再由公共 PiToolArgumentValidator 校验。 */
internal fun preparePiToolCallArguments(call: AgentContentBlock.ToolCall): AgentContentBlock.ToolCall {
    if (call.name != com.android.everytalk.data.computer.ComputerToolNames.EDIT) return call
    val arguments = call.arguments.toMutableMap()
    val originalEdits = arguments["edits"]
    val parsedEdits = when (originalEdits) {
        is JsonPrimitive -> originalEdits.takeIf(JsonPrimitive::isString)?.content?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        }
        else -> originalEdits
    }
    when {
        parsedEdits is JsonArray -> arguments["edits"] = parsedEdits
        parsedEdits is JsonObject && parsedEdits.isSinglePiEdit() -> arguments["edits"] = JsonArray(listOf(parsedEdits))
    }

    val oldText = arguments["oldText"] as? JsonPrimitive
    val newText = arguments["newText"] as? JsonPrimitive
    if (oldText?.isString == true && newText?.isString == true) {
        val edits = (arguments["edits"] as? JsonArray).orEmpty().toMutableList()
        edits += JsonObject(mapOf("oldText" to oldText, "newText" to newText))
        arguments.remove("oldText")
        arguments.remove("newText")
        arguments["edits"] = JsonArray(edits)
    }
    return if (arguments == call.arguments) call else call.copy(arguments = JsonObject(arguments))
}

private fun JsonObject.isSinglePiEdit(): Boolean =
    (this["oldText"] as? JsonPrimitive)?.isString == true &&
        (this["newText"] as? JsonPrimitive)?.isString == true

private data class BoundedToolResult(
    val content: JsonElement,
    val truncated: Boolean,
)
