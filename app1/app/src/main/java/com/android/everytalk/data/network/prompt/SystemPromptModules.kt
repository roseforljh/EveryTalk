package com.android.everytalk.data.network

/**
 * 从随应用打包的 Markdown 读取固定规则，供所有模型客户端共用。
 * 参考 Pi：只有一份英文规则（回复语言由规则跟随用户，不按语言切换整套提示词），
 * 前言不加标签，其余每段包在同名标签里，模型能分清规则、上下文和用户指令。
 */
internal object SystemPromptModules {
    // 顺序明确且不扫描目录，避免新增说明文件意外改变模型收到的规则或缓存前缀。
    private val sections = listOf("core" to null, "execution" to "execution", "tools" to "tools", "output" to "output")
    val base by lazy {
        sections.joinToString("\n\n") { (name, tag) ->
            readResource("base/$name").let { if (tag == null) it else section(tag, it) }
        }
    }

    // 专项任务按需读取，不混入普通聊天的固定前缀，避免无关指令互相干扰。
    val historySummary by lazy { readResource("tasks/history-summary") }
    val inputSummary by lazy { readResource("tasks/input-summary") }
    val conversationTitle by lazy { readResource("tasks/conversation-title") }
    val mcpAccess by lazy { readResource("tasks/mcp-access") }
    val conversationMemory by lazy { readResource("tasks/conversation-memory") }
    val memoryConsolidation by lazy { readResource("tasks/memory-consolidation") }

    fun section(tag: String, content: String): String = "<$tag>\n${content.trim()}\n</$tag>"

    /** 缺失或空白模块属于打包错误，明确报错，避免静默发送缺少规则的提示词。 */
    private fun readResource(name: String): String {
        val path = "/system-prompts/$name.md"
        val stream = checkNotNull(SystemPromptModules::class.java.getResourceAsStream(path)) {
            "缺少系统提示词模块：$path"
        }
        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        check(content.isNotEmpty()) { "系统提示词模块为空：$path" }
        return content
    }
}
