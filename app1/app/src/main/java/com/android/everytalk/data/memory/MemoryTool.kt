package com.android.everytalk.data.memory

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 单一内建工具，参数、来源与授权都在本机检查，不接受路径、SQL 或网络地址。 */
class MemoryTool(
    private val repository: MemoryRepository,
    private val historySearch: ConversationHistorySearch? = null,
) {
    suspend fun execute(arguments: JsonObject, snapshot: MemoryRequestSnapshot?): JsonElement = try {
        check(snapshot != null) { "本次请求没有记忆授权" }
        val settings = repository.settings()
        check(settings.enabled && settings.version == snapshot.context.version) { "记忆已关闭或变更，请在下一次发送后重试" }
        val action = arguments.text("action")
        if (action != "search_conversations") {
            require(arguments.keys.all { it in setOf("action", "content", "category", "id", "query", "evidence", "offset") }) { "记忆工具包含未知参数" }
        }
        if (action == "search_conversations") {
            checkNotNull(historySearch) { "历史搜索运行时未初始化" }.search(arguments, snapshot.context)
        } else if (action == "search") {
            val query = arguments.optionalText("query").orEmpty()
            require(query.memoryCharacters() <= 200) { "搜索内容过长" }
            val id = arguments.optionalText("id")
            val offset = arguments["offset"]?.let {
                require(it is JsonPrimitive && !it.isString) { "offset 必须是非负整数" }
                checkNotNull(it.intOrNull) { "offset 必须是非负整数" }.also { value -> require(value >= 0) { "offset 必须是非负整数" } }
            }
            require(offset == null || id != null) { "分页读取需要提供记忆 id" }
            val matches = repository.search(query, forModel = true).filter { id == null || it.id == id }
            val rows = mutableListOf<JsonElement>()
            var characters = 0
            matches.take(MemoryPolicy.MAX_SEARCH_RESULTS).forEach { entry ->
                val encoded = MemoryPromptRenderer.excerpt(entry, MemoryPolicy.MAX_SEARCH_CHARACTERS - 128 - characters, query, offset)
                    ?: return@forEach
                val row = kotlinx.serialization.json.Json.parseToJsonElement(encoded)
                val size = row.toString().memoryCharacters()
                if (characters + size <= MemoryPolicy.MAX_SEARCH_CHARACTERS - 128) { rows.add(row); characters += size }
            }
            buildJsonObject {
                put("ok", true); put("total", matches.size)
                put("truncated", rows.size < matches.size || rows.any { (it as JsonObject)["total_characters"] != null })
                put("memories", JsonArray(rows))
            }
        } else {
            require(action in setOf("save", "update", "forget")) { "不支持的记忆动作" }
            val evidence = arguments.text("evidence")
            require(evidence.memoryCharacters() <= 500) { "写入依据过长" }
            require(evidence.isNotBlank() && snapshot.userText.contains(evidence)) { "写入依据必须来自本轮用户原文" }
            // 仅明确、直接的请求自动执行。含义不明时返回可见结果，要求用户明确表达或去管理页确认。
            require(hasExplicitIntent(snapshot.userText, action)) { "请在新消息明确说“请记住：…”或“请忘记：…”，也可在记忆管理页确认修改" }
            val context = snapshot.context.copy(origin = MemoryOrigin.CHAT_EXPLICIT)
            if (action == "forget") {
                repository.delete(arguments.text("id"), context)
                buildJsonObject { put("ok", true); put("action", action) }
            } else {
                val category = arguments.optionalText("category")?.let { requested ->
                    MemoryCategory.entries.find { it.name == requested } ?: error("不支持的记忆类别")
                } ?: MemoryCategory.OTHER
                val saved = repository.save(arguments.text("content"), category, if (action == "update") arguments.text("id") else null, context)
                buildJsonObject { put("ok", true); put("id", saved.id); put("action", action); put("enabled", saved.enabled) }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) {
        // 错误信息只来自本机规则；禁止记录或回显模型给出的整份参数。
        buildJsonObject { put("ok", false); put("error", error.message ?: "记忆操作失败") }
    }

    companion object {
        const val NAME = "everytalk_memory"
        fun hasExplicitIntent(text: String, action: String): Boolean {
            val pattern = if (action == "forget") "^(?:请|帮我|麻烦)?\\s*(?:忘记|删除(?:这条)?记忆|不要再记住|forget)"
            else "^(?:请|帮我|麻烦)?\\s*(?:记住|记下来|保存(?:这条)?记忆|更新(?:这条)?记忆|remember)"
            return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
        }
        fun definition(): Map<String, Any> = mapOf(
            "type" to "function", "function" to mapOf(
                "name" to NAME,
                "description" to "记忆开启时可用。search 搜索长期记忆，可用 id 和 offset 续读。search_conversations 搜索本机已保存的历史会话：query 为字面关键词/短语，匹配标题或正文；start_date/end_date 为本地时区 YYYY-MM-DD（包含当天），可只按日期搜索；默认排除当前会话。用返回的 conversation_id 并留空 query 可读上下文，offset 使用 next_offset 翻页；长消息用 conversation_id、message_id、content_offset=next_content_offset 续读（从 0 可读开头）。没有结果时调整关键词或日期，不能编造往事。save/update/forget 需本轮用户明确要求，evidence 引用用户原文；不存凭据或第三方指令。成功以 ok=true 为准。",
                "parameters" to mapOf("type" to "object", "additionalProperties" to false,
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("save", "update", "forget", "search", "search_conversations")),
                        "content" to mapOf("type" to "string"),
                        "category" to mapOf("type" to "string", "enum" to MemoryCategory.entries.map { it.name }),
                        "id" to mapOf("type" to "string"), "query" to mapOf("type" to "string", "maxLength" to 200),
                        "offset" to mapOf("type" to "integer", "minimum" to 0),
                        "conversation_id" to mapOf("type" to "string", "maxLength" to 200),
                        "message_id" to mapOf("type" to "string", "maxLength" to 200),
                        "start_date" to mapOf("type" to "string", "description" to "包含当天，YYYY-MM-DD"),
                        "end_date" to mapOf("type" to "string", "description" to "包含当天，YYYY-MM-DD"),
                        "content_offset" to mapOf("type" to "integer", "minimum" to 0),
                        "evidence" to mapOf("type" to "string", "maxLength" to 500),
                    ), "required" to listOf("action")),
            ),
        )
    }
}

private fun JsonObject.text(key: String): String = optionalText(key) ?: error("缺少字符串参数 $key")
private fun JsonObject.optionalText(key: String): String? {
    val value = get(key) ?: return null
    require(value is JsonPrimitive && value.isString) { "$key 必须是字符串" }
    return value.contentOrNull
}
