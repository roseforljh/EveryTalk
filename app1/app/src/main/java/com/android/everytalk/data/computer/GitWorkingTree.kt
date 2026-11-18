package com.android.everytalk.data.computer

import com.android.everytalk.data.DataClass.ExecutionFileChange
import com.android.everytalk.data.DataClass.ExecutionFileEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * exec 的改动不看命令内容：执行前后各用临时索引把工作树写成树对象，再取两棵树的补丁。
 * 因此 sed、python、patch、cat > 一样会被记录；只覆盖工作目录所在的 git 仓库，不写真实索引。
 */
internal object GitWorkingTree {
    private const val MAX_FILES = 8
    private const val MAX_EDIT_CHARS = 4_000
    private const val MAX_TOTAL_CHARS = 24_000

    private val script = """
        d=${'$'}1; [ "${'$'}d" = "~" ] && d=${'$'}HOME; cd -- "${'$'}d" 2>/dev/null || exit 0
        r=${'$'}(git rev-parse --show-toplevel 2>/dev/null) || exit 0
        i=/tmp/everytalk-tree-${'$'}${'$'}; GIT_INDEX_FILE=${'$'}i git -C "${'$'}r" read-tree HEAD 2>/dev/null || { rm -f "${'$'}i"; exit 0; }
        GIT_INDEX_FILE=${'$'}i GIT_WORK_TREE=${'$'}r git -C "${'$'}r" add -A . >/dev/null 2>&1 || { rm -f "${'$'}i"; exit 0; }
        t=${'$'}(GIT_INDEX_FILE=${'$'}i git -C "${'$'}r" write-tree 2>/dev/null); rm -f "${'$'}i"; [ -n "${'$'}t" ] || exit 0
        if [ "${'$'}2" = after ] && [ -n "${'$'}3" ] && [ "${'$'}3" != "${'$'}t" ]; then git -C "${'$'}r" diff-tree -r -p --no-color --no-ext-diff --no-renames -U0 "${'$'}3" "${'$'}t"; fi
        printf 'T %s\n' "${'$'}t"
    """.trimIndent()

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun command(cwd: String, before: String? = null): String {
        require(cwd.length <= 4096 && cwd.none { it in "\u0000\r\n" }) { "工作目录无效" }
        val mode = if (before.isNullOrEmpty()) "before" else "after"
        return "sh -c ${quote(script)} everytalk ${quote(cwd)} $mode ${quote(before.orEmpty())}"
    }

    /** 只取末尾的树对象；没有 git、不是仓库或超时都按没有改动处理，不影响命令本身。 */
    fun tree(stdout: String): String =
        Regex("(?m)^T ([0-9a-f]{40})$").findAll(stdout).lastOrNull()?.groupValues?.get(1).orEmpty()

    fun parse(patch: String): List<ExecutionFileChange> {
        val changes = mutableListOf<ExecutionFileChange>()
        var edits = mutableListOf<ExecutionFileEdit>()
        var path: String? = null
        var oldLines: MutableList<String>? = null
        var newLines: MutableList<String>? = null
        fun flush() {
            val current = path
            if (current != null && oldLines != null && newLines != null && (oldLines!!.isNotEmpty() || newLines!!.isNotEmpty())) {
                val oldText = oldLines!!.joinToString("\n")
                val newText = newLines!!.joinToString("\n")
                if (oldText != newText) edits += ExecutionFileEdit(oldText, newText)
            }
            oldLines = null
            newLines = null
        }
        fun commit() {
            flush()
            val current = path
            if (current != null && edits.isNotEmpty()) changes += ExecutionFileChange(current, written = false, edits = edits)
            edits = mutableListOf()
            path = null
        }
        for (line in patch.split("\n")) {
            when {
                line.startsWith("diff --git ") -> commit()
                line.startsWith("--- ") -> {
                    val candidate = line.removePrefix("--- ").removePrefix("a/")
                    if (path == null && candidate != "/dev/null" && '\t' !in candidate) path = candidate
                }
                line.startsWith("+++ ") -> {
                    val candidate = line.removePrefix("+++ ").removePrefix("b/")
                    if (candidate != "/dev/null" && '\t' !in candidate) path = candidate
                }
                line.startsWith("@@") -> {
                    flush()
                    oldLines = mutableListOf()
                    newLines = mutableListOf()
                }
                else -> when {
                    oldLines == null || newLines == null -> Unit
                    line.startsWith("+") -> newLines!!.add(line.substring(1))
                    line.startsWith("-") -> oldLines!!.add(line.substring(1))
                    line.startsWith(" ") -> {
                        oldLines!!.add(line.substring(1))
                        newLines!!.add(line.substring(1))
                    }
                }
            }
        }
        commit()
        return capped(changes)
    }

    private fun capped(changes: List<ExecutionFileChange>): List<ExecutionFileChange> {
        val result = mutableListOf<ExecutionFileChange>()
        var budget = MAX_TOTAL_CHARS
        for (change in changes.take(MAX_FILES)) {
            val edits = change.edits.map { ExecutionFileEdit(it.oldText.take(MAX_EDIT_CHARS), it.newText.take(MAX_EDIT_CHARS)) }
            val size = Json.encodeToString(ExecutionFileChange.serializer(), change.copy(edits = edits)).length
            if (size > budget) break
            budget -= size
            result += change.copy(path = change.path.take(400), edits = edits)
        }
        return result
    }

    fun toJson(changes: List<ExecutionFileChange>): JsonArray = buildJsonArray {
        changes.forEach { change -> add(buildJsonObject {
            put("path", change.path)
            put("written", change.written)
            put("edits", buildJsonArray { change.edits.forEach { edit -> add(buildJsonObject {
                put("oldText", edit.oldText)
                put("newText", edit.newText)
            }) } })
        }) }
    }

    /** 工具结果可能是直接对象，也可能包在 data 或 content[text] 里；三者取到同一份改动。 */
    fun fromResult(result: JsonElement?): List<ExecutionFileChange> {
        val candidates = mutableListOf<JsonElement?>()
        candidates += result
        val root = result as? JsonObject
        candidates += root?.get("data")
        (root?.get("content") as? JsonArray)?.forEach { block ->
            val text = ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull ?: return@forEach
            candidates += runCatching { Json.parseToJsonElement(text) }.getOrNull()
        }
        for (candidate in candidates) {
            val objectValue = ((candidate as? JsonObject)?.get("file_changes")?.let { it as? JsonArray })
                ?: (((candidate as? JsonObject)?.get("data") as? JsonObject)?.get("file_changes") as? JsonArray)
                ?: continue
            val changes = objectValue.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val path = (item["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val edits = (item["edits"] as? JsonArray).orEmpty().mapNotNull { editElement ->
                    val edit = editElement as? JsonObject ?: return@mapNotNull null
                    val oldText = (edit["oldText"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val newText = (edit["newText"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    ExecutionFileEdit(oldText, newText)
                }
                if (edits.isEmpty()) null else ExecutionFileChange(path, (item["written"] as? JsonPrimitive)?.booleanOrNull == true, edits)
            }
            if (changes.isNotEmpty()) return changes
        }
        return emptyList()
    }
}
