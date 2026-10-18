package com.android.everytalk.data.DataClass

import com.android.everytalk.data.agent.AgentContentBlock
import com.android.everytalk.data.agent.preparePiToolCallArguments
import com.android.everytalk.data.computer.ComputerToolNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一处文本替换。write_file 没有原文，oldText 为空表示整段写入。 */
@Serializable
data class ExecutionFileEdit(val oldText: String, val newText: String)

/** Agent 工具对某个文件的改动，随 ExecutionStep 持久化，用于输入框上方的改动胶囊。 */
@Serializable
data class ExecutionFileChange(
    val path: String,
    /** true 表示 write_file 覆盖写入，false 表示 edit 局部替换或追加写入。 */
    val written: Boolean,
    val edits: List<ExecutionFileEdit>,
)

// ponytail: 每段文本最多保留 20k 字符，避免整文件写入撑大消息 JSON；需要完整内容时改为按 toolCallId 读 Agent 账本。
private const val FILE_EDIT_MAX_CHARS = 20_000

private fun String.cappedForDiff(): String =
    if (length <= FILE_EDIT_MAX_CHARS) this else take(FILE_EDIT_MAX_CHARS).substringBeforeLast('\n') + "\n…"

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** 只认 write_file / edit 的结构化参数，不从其它工具输出里猜改动。 */
fun executionFileChange(toolName: String, arguments: JsonObject): ExecutionFileChange? {
    val args = preparePiToolCallArguments(
        AgentContentBlock.ToolCall(id = "", name = toolName, arguments = arguments),
    ).arguments
    val path = args.text("path")?.takeIf(String::isNotBlank) ?: return null
    val edits = when (toolName) {
        ComputerToolNames.WRITE_FILE -> {
            if (args.text("encoding") == "base64") return null
            listOf(ExecutionFileEdit("", args.text("content")?.cappedForDiff() ?: return null))
        }
        ComputerToolNames.EDIT -> (args["edits"] as? JsonArray).orEmpty().mapNotNull { element ->
            val edit = element as? JsonObject ?: return@mapNotNull null
            ExecutionFileEdit(
                oldText = edit.text("oldText")?.cappedForDiff() ?: return@mapNotNull null,
                newText = edit.text("newText")?.cappedForDiff() ?: return@mapNotNull null,
            )
        }
        else -> return null
    }
    if (edits.isEmpty()) return null
    return ExecutionFileChange(
        path = path,
        written = toolName == ComputerToolNames.WRITE_FILE && args.text("mode") != "append",
        edits = edits,
    )
}

enum class DiffLineType { Context, Added, Removed }

data class DiffLine(val type: DiffLineType, val text: String)

private fun String.diffLineList(): List<String> = if (isEmpty()) emptyList() else lines()

/** 行级 LCS diff。先剥掉公共首尾，只对中间变化段建表。 */
fun diffLines(oldText: String, newText: String): List<DiffLine> {
    val a = oldText.diffLineList()
    val b = newText.diffLineList()
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < a.size - prefix && suffix < b.size - prefix &&
        a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
    ) suffix++
    val oldMid = a.subList(prefix, a.size - suffix)
    val newMid = b.subList(prefix, b.size - suffix)
    val middle = mutableListOf<DiffLine>()
    // ponytail: O(n·m) LCS，变化段超过 1M 格时退化为整段删再整段加；需要时换 Myers。
    if (oldMid.size.toLong() * newMid.size > 1_000_000L) {
        oldMid.mapTo(middle) { DiffLine(DiffLineType.Removed, it) }
        newMid.mapTo(middle) { DiffLine(DiffLineType.Added, it) }
    } else {
        val n = oldMid.size
        val m = newMid.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (oldMid[i] == newMid[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && oldMid[i] == newMid[j] -> {
                    middle += DiffLine(DiffLineType.Context, oldMid[i]); i++; j++
                }
                // 同一处替换先列删除行再列新增行，和主流 diff 的阅读顺序一致。
                i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                    middle += DiffLine(DiffLineType.Removed, oldMid[i]); i++
                }
                else -> {
                    middle += DiffLine(DiffLineType.Added, newMid[j]); j++
                }
            }
        }
    }
    return a.subList(0, prefix).map { DiffLine(DiffLineType.Context, it) } +
        middle +
        a.subList(a.size - suffix, a.size).map { DiffLine(DiffLineType.Context, it) }
}

/** 一个文件在本轮的全部改动，hunks 按工具调用顺序排列。 */
data class FileChangeSummary(
    val path: String,
    val written: Boolean,
    val hunks: List<List<DiffLine>>,
) {
    val additions: Int = hunks.sumOf { hunk -> hunk.count { it.type == DiffLineType.Added } }
    val deletions: Int = hunks.sumOf { hunk -> hunk.count { it.type == DiffLineType.Removed } }
}

/** 消息里的工具步骤；新消息以 executionSteps 为准，旧消息只在 trace 里有。 */
fun Message.toolSteps(): List<ExecutionStep> = executionSteps.ifEmpty {
    executionTrace.filterIsInstance<ExecutionTraceEvent.Tool>().map { it.step }
}

/** 一次 Agent Run 只有一个可见气泡，按路径合并它的全部文件改动。 */
fun fileChangeSummaries(steps: List<ExecutionStep>): List<FileChangeSummary> =
    steps.mapNotNull { it.fileChange }
        .groupBy { it.path }
        .map { (path, changes) ->
            FileChangeSummary(
                path = path,
                written = changes.any { it.written },
                hunks = changes.flatMap { change -> change.edits.map { diffLines(it.oldText, it.newText) } },
            )
        }
        .filter { it.additions + it.deletions > 0 }
