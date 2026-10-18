package com.android.everytalk.data.network

/**
 * 从随应用打包的 Markdown 读取固定规则，供所有模型客户端共用。
 * 这里只组合固定模块；会话指令、Skill 目录和运行状态仍由原有请求流程补充。
 */
internal object SystemPromptModules {
    // 顺序明确且不扫描目录，避免新增说明文件意外改变模型收到的规则或缓存前缀。
    private val moduleNames = listOf("core", "execution", "tools", "output")
    private val chinese by lazy { read("zh-CN") }
    private val english by lazy { read("en") }

    // 专项任务按需读取，不混入普通聊天的固定前缀，避免无关指令互相干扰。
    val historySummary by lazy { readResource("tasks/history-summary") }
    val inputSummary by lazy { readResource("tasks/input-summary") }
    val memoryExtraction by lazy { readResource("tasks/memory-extraction") }
    val conversationTitle by lazy { readResource("tasks/conversation-title") }
    val mcpAccess by lazy { readResource("tasks/mcp-access") }

    fun readForLanguage(language: String): String =
        if (language.startsWith("zh")) chinese else english

    /** 缺失或空白模块属于打包错误，明确报错，避免静默发送缺少规则的提示词。 */
    private fun read(language: String): String = moduleNames.joinToString("\n\n") { name ->
        readResource("$language/$name")
    }

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
