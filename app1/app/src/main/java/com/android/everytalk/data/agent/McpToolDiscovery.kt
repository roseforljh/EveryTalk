package com.android.everytalk.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.ln

/**
 * MCP 工具的分级声明，参考 Pi 的 deferred exposure + tool_search：
 * - 按服务器分级：工具少的服务器直接声明，总数超出预算的大服务器（GitHub、Notion 等）延迟加载；
 * - 延迟服务器只在系统提示里列一行，模型用 tool_search 按需加载，匹配的工具从下一次请求起声明；
 * - 已加载的工具在整个会话里保留，只影响声明，不改变已授权的工具全集。
 */
internal object McpToolDiscovery {
    const val NAME = "tool_search"
    /** 直接声明的 MCP 工具预算；超出时从工具最多的服务器开始延迟。 */
    const val INLINE_MCP_LIMIT = 24
    /** 会话里累计加载的延迟工具上限，只保留最近加载的，避免长会话把预算重新撑爆。 */
    const val MAX_LOADED = 32
    private const val DEFAULT_LIMIT = 8

    private val SERVER_TAG = Regex("""^\[([^\]\n]{1,80})] """)
    private val ALIAS_PREFIX = Regex("""^mcp_[0-9a-f]{32}_""")

    fun name(definition: Map<String, Any>): String =
        ((definition["function"] as? Map<*, *>)?.get("name") ?: definition["name"])?.toString().orEmpty()

    private fun description(definition: Map<String, Any>): String =
        ((definition["function"] as? Map<*, *>)?.get("description") ?: definition["description"])?.toString().orEmpty()

    private fun isMcp(definition: Map<String, Any>) = name(definition).startsWith("mcp_")

    /** 工具别名是哈希，服务器名写进描述前缀，模型才能分清多家同名的 web_search。 */
    fun tagServer(server: String, description: String): String =
        if (server.isBlank()) description else "[${server.trim().replace("]", ")")}] $description"

    private fun serverOf(definition: Map<String, Any>): String =
        SERVER_TAG.find(description(definition))?.groupValues?.get(1).orEmpty()

    private fun localName(name: String) = name.replace(ALIAS_PREFIX, "")

    /** 需要延迟加载的 MCP 工具名：服务器按工具数从少到多装入预算，装不下的整台延迟。 */
    private fun deferredNames(all: List<Map<String, Any>>?): Set<String> {
        val groups = all.orEmpty().filter(::isMcp).groupBy(::serverOf)
        if (groups.values.sumOf { it.size } <= INLINE_MCP_LIMIT) return emptySet()
        var used = 0
        return buildSet {
            groups.entries.sortedWith(compareBy({ it.value.size }, { it.key })).forEach { (_, tools) ->
                if (used + tools.size <= INLINE_MCP_LIMIT) used += tools.size
                else tools.forEach { add(name(it)) }
            }
        }
    }

    /** 本轮直接声明的工具（不含延迟工具）。 */
    fun directTools(all: List<Map<String, Any>>): List<Map<String, Any>> {
        val deferred = deferredNames(all)
        return all.filterNot { name(it) in deferred }
    }

    fun project(all: List<Map<String, Any>>?, loaded: List<String>): List<Map<String, Any>>? {
        val deferred = deferredNames(all)
        if (all == null || deferred.isEmpty()) return all
        val byName = all.associateBy(::name)
        // 已加载工具按最近加载顺序追加；撤权或服务器断开后自动从声明中消失。
        val restored = loaded.asReversed().distinct().filter { it in deferred && it in byName }.take(MAX_LOADED).asReversed()
        return all.filterNot { name(it) in deferred } + restored.map(byName::getValue) + definition
    }

    /** 延迟服务器目录，写入系统提示；工具清单变化时才变，保持 tool_search 描述稳定以利于缓存。 */
    fun serverCatalog(all: List<Map<String, Any>>, instructions: Map<String, String> = emptyMap()): String? {
        val deferred = deferredNames(all)
        if (deferred.isEmpty()) return null
        val servers = all.filter { name(it) in deferred }.groupBy(::serverOf).toSortedMap()
        return buildString {
            append("These connected MCP services are not declared as tools yet. When the task needs one, call `$NAME` with a short ENGLISH query naming the action and object (for example \"create issue\" or \"search pages\"), even if the user writes in another language; matching tools become callable on your next request and stay available for the rest of the conversation. Do not claim a service is unavailable before searching.\n")
            servers.forEach { (server, tools) ->
                val summary = instructions[server]?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotBlank)?.take(160)
                    ?: tools.take(6).joinToString(", ") { localName(name(it)) }
                append("- ${server.ifBlank { "MCP" }} (${tools.size} tools): $summary\n")
            }
        }.trimEnd()
    }

    fun search(call: AgentContentBlock.ToolCall, all: List<Map<String, Any>>?): AgentContentBlock.ToolResult {
        val query = (call.arguments["query"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val limit = ((call.arguments["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, 20)
        val deferred = deferredNames(all)
        // 直接声明的工具本来就可见，只在延迟工具里搜。
        val tools = CodemodeTool.callableTools(all).filter { it.name in deferred }
        val found = Bm25.rank(query, tools.map { tool ->
            val raw = tool.description
            val server = SERVER_TAG.find(raw)?.groupValues?.get(1).orEmpty()
            Bm25.Document(tool.name, localName(tool.name), raw.replace(SERVER_TAG, "") + " " + schemaText(tool.parameters), server)
        }, limit).map { match -> tools.first { it.name == match } }
        return AgentContentBlock.ToolResult(
            call.id, NAME,
            buildJsonObject {
                put("message", if (found.isEmpty()) "No matching tools found. Try other words, or answer without the service."
                    else "Loaded ${found.size} tools. They are declared on your NEXT request; do not call them in this response.")
                put("tools", buildJsonArray { found.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description.lineSequence().firstOrNull().orEmpty().take(240))
                    })
                } })
            },
            loadedMcpToolIds = found.map { it.name },
        )
    }

    /** 参数说明与属性名也参与检索，例如 `repo`、`owner` 能命中 GitHub 仓库类工具。 */
    private fun schemaText(schema: JsonObject): String = buildList {
        fun walk(node: Any?) {
            when (node) {
                is JsonObject -> {
                    (node["description"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                    (node["properties"] as? JsonObject)?.forEach { (key, value) -> add(key); walk(value) }
                    walk(node["items"])
                    listOf("anyOf", "oneOf", "allOf").forEach { key -> (node[key] as? JsonArray)?.forEach(::walk) }
                }
                is JsonArray -> node.forEach(::walk)
            }
        }
        walk(schema)
    }.joinToString(" ")

    private val definition: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to NAME,
            "description" to "Search tools of connected MCP services that are not declared yet (listed in the system prompt) and load the matches. Matching tools become callable on your NEXT request and stay loaded. Query with a few ENGLISH words naming the action and object, e.g. \"list pull requests\", even if the user writes in another language.",
            "parameters" to mapOf(
                "type" to "object", "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Action and object to search for."),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20, "description" to "Maximum tools to load, default $DEFAULT_LIMIT."),
                ), "required" to listOf("query"),
            ),
        ),
        "executionMode" to "sequential",
    )
}

/**
 * 移植自 Pi tool-search 的 BM25 排序：
 * 覆盖查询词更多的工具优先；名称命中权重高于任意次描述命中；服务器名只帮助找到服务器；
 * 查询正好是工具名时排第一。中文没有空格，按单字加双字切分，中英文查询都能命中。
 */
internal object Bm25 {
    data class Document(val name: String, val localName: String, val text: String, val namespace: String)

    private const val K1 = 1.2
    private const val B = 0.75
    private const val NAME_WEIGHT = 3.0
    private const val NAMESPACE_WEIGHT = 0.5
    private val STOP_WORDS = setOf("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it",
        "of", "on", "or", "that", "the", "this", "to", "with")
    private val HAN = Regex("""\p{IsHan}+""")

    /**
     * 模型被要求用英文写查询，这里兜底中文查询：常见动作和对象词映射到英文。
     * ponytail: 只覆盖中文常用词；其他语言靠模型按说明写英文查询。
     */
    private val ZH_TERMS = mapOf(
        "创建" to "create", "新建" to "create", "添加" to "add", "列出" to "list", "列表" to "list", "查看" to "get read",
        "获取" to "get", "读取" to "read get", "读" to "read", "搜索" to "search", "查找" to "search find", "查询" to "search query",
        "更新" to "update", "修改" to "update edit", "编辑" to "edit update", "删除" to "delete remove", "合并" to "merge",
        "评论" to "comment", "回复" to "reply comment", "提交" to "commit push", "推送" to "push", "触发" to "trigger run",
        "运行" to "run", "发送" to "send", "下载" to "download", "上传" to "upload", "关闭" to "close", "标星" to "star",
        "复刻" to "fork", "截图" to "screenshot capture", "抓取" to "fetch scrape read", "爬取" to "crawl scrape", "提取" to "extract",
        "仓库" to "repository repo", "问题" to "issue", "议题" to "issue", "拉取请求" to "pull request", "合并请求" to "pull request",
        "分支" to "branch", "文件" to "file content", "内容" to "content", "代码" to "code", "发布" to "release", "版本" to "release tag",
        "标签" to "label tag", "工作流" to "workflow action", "日志" to "log", "通知" to "notification", "讨论" to "discussion",
        "用户" to "user", "组织" to "org", "告警" to "alert", "漏洞" to "vulnerability alert", "依赖" to "dependabot dependency",
        "页面" to "page", "网页" to "web page url", "链接" to "url link", "数据库" to "database", "邮件" to "mail email message",
        "邮箱" to "mail email", "论文" to "paper arxiv", "图片" to "image", "文档" to "doc documentation", "评审" to "review",
        "审查" to "review", "片段" to "gist", "日期" to "date datetime", "去重" to "deduplicate", "排序" to "sort rank",
    )

    private fun stem(term: String): String = when {
        term.length > 4 && term.endsWith("ies") -> term.dropLast(3) + "y"
        term.length > 4 && Regex("(ches|shes|sses|xes|zes)$").containsMatchIn(term) -> term.dropLast(2)
        term.length > 3 && term.endsWith("s") && !term.endsWith("ss") -> term.dropLast(1)
        else -> term
    }

    fun tokenize(text: String): List<String> {
        val normalized = text
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .lowercase()
            .replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ")
        return normalized.split(' ').filter(String::isNotEmpty).flatMap { word ->
            if (!HAN.containsMatchIn(word)) listOf(word).filterNot { it in STOP_WORDS }.map { if (it.all { c -> c in 'a'..'z' || c in '0'..'9' }) stem(it) else it }
            else buildList {
                // 汉字段切成单字和相邻双字，其余字母数字段照常处理。
                var rest = word
                HAN.findAll(word).forEach { han ->
                    val run = han.value
                    run.forEach { add(it.toString()) }
                    run.windowed(2).forEach(::add)
                    ZH_TERMS.forEach { (zh, en) -> if (zh in run) en.split(' ').forEach { add(stem(it)) } }
                    rest = rest.replace(run, " ")
                }
                rest.split(' ').filter(String::isNotEmpty).forEach { add(stem(it)) }
            }
        }
    }

    fun rank(query: String, documents: List<Document>, limit: Int): List<String> {
        val queryTerms = tokenize(query).toSet()
        if (queryTerms.isEmpty() || documents.isEmpty() || limit <= 0) return emptyList()
        class Indexed(val doc: Document, val nameTerms: Set<String>, val counts: Map<String, Int>, val length: Int, val namespaceTerms: Set<String>)
        val indexed = documents.map { doc ->
            val counts = tokenize(doc.text).groupingBy { it }.eachCount()
            Indexed(doc, tokenize(doc.localName).toSet(), counts, counts.values.sum(), tokenize(doc.namespace).toSet())
        }
        val average = indexed.sumOf { it.length }.toDouble() / indexed.size
        val idf = queryTerms.associateWith { term ->
            val frequency = indexed.count { term in it.nameTerms || term in it.counts || term in it.namespaceTerms }
            ln(1 + (indexed.size - frequency + 0.5) / (frequency + 0.5))
        }
        val fullNameBonus = idf.values.sum()
        val exact = query.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
        data class Match(val name: String, val score: Double, val covered: Int, val exact: Boolean, val order: Int)
        val matches = indexed.mapIndexedNotNull { order, document ->
            val norm = K1 * (1 - B + B * document.length / (average.takeIf { it > 0 } ?: 1.0))
            var score = 0.0
            var covered = 0
            queryTerms.forEach { term ->
                val count = document.counts[term] ?: 0
                val weight = (if (term in document.nameTerms) NAME_WEIGHT else 0.0) +
                    (if (term in document.namespaceTerms) NAMESPACE_WEIGHT else 0.0) +
                    count * (K1 + 1) / (count + norm)
                if (weight > 0) covered++
                score += idf.getValue(term) * weight
            }
            if (score == 0.0) return@mapIndexedNotNull null
            if (document.nameTerms.isNotEmpty() && document.nameTerms.all { it in queryTerms }) score += fullNameBonus
            Match(document.doc.name, score, covered, document.doc.localName.lowercase() == exact || document.doc.name.lowercase() == exact, order)
        }
        return matches.sortedWith(compareByDescending<Match> { it.exact }.thenByDescending { it.covered }
            .thenByDescending { it.score }.thenBy { it.order }).take(limit).map { it.name }
    }
}
