package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 类似 Skill 的能力卡目录。
 *
 * 能力卡只描述回答契约，不负责猜测用户意图，也不直接授予外部工具权限。
 * 当前问题由模型依据目录自主选择，应用只校验选择结果并返回受控指令正文。
 */
internal object PromptCapabilityCatalog {

    internal const val PROTOCOL_VERSION = 1
    internal const val SELECT_TOOL_NAME = "everytalk_select_capabilities"

    private enum class CapabilityKind {
        TASK,
        FORMAT,
        SAFETY,
    }

    private data class CapabilityCard(
        val id: String,
        val kind: CapabilityKind,
        val summaryZh: String,
        val summaryEn: String,
        val instructionZh: String,
        val instructionEn: String,
    )

    private val cards = listOf(
        CapabilityCard(
            id = "general-answer",
            kind = CapabilityKind.TASK,
            summaryZh = "普通问答、解释事实和日常问题",
            summaryEn = "ordinary questions and factual explanations",
            instructionZh = "直接回答问题，先给结论，信息不足时说明不确定性。",
            instructionEn = "Answer directly, lead with the conclusion, and mark uncertainty.",
        ),
        CapabilityCard(
            id = "explanation-tutoring",
            kind = CapabilityKind.TASK,
            summaryZh = "概念解释、教学和原理讲解",
            summaryEn = "concept explanations and tutoring",
            instructionZh = "先讲直观含义，再补充必要的前提、步骤和技术细节。",
            instructionEn = "Start with intuition, then provide necessary assumptions, steps, and details.",
        ),
        CapabilityCard(
            id = "coding",
            kind = CapabilityKind.TASK,
            summaryZh = "代码、配置、脚本和实现任务",
            summaryEn = "code, configuration, scripts, and implementation",
            instructionZh = "给出可运行实现，说明关键约束和验证方式。",
            instructionEn = "Provide runnable implementation with key constraints and verification.",
        ),
        CapabilityCard(
            id = "debugging",
            kind = CapabilityKind.TASK,
            summaryZh = "报错、崩溃、日志和性能问题",
            summaryEn = "errors, crashes, logs, and performance problems",
            instructionZh = "先定位可验证的根因，再给最小修复和回归验证。",
            instructionEn = "Identify a falsifiable root cause, then give the smallest fix and regression check.",
        ),
        CapabilityCard(
            id = "document-analysis",
            kind = CapabilityKind.TASK,
            summaryZh = "PDF、报告、合同和长文档分析",
            summaryEn = "PDFs, reports, contracts, and long documents",
            instructionZh = "只依据文档内容回答，区分原文事实、计算结果和推断，并说明解析缺口。",
            instructionEn = "Use document evidence, separate facts, calculations, and inferences, and mark gaps.",
        ),
        CapabilityCard(
            id = "data-analysis",
            kind = CapabilityKind.TASK,
            summaryZh = "数据、指标、统计和趋势分析",
            summaryEn = "data, metrics, statistics, and trend analysis",
            instructionZh = "明确数据口径，区分事实、计算和推断，避免把相关性写成因果。",
            instructionEn = "State the data definition, separate facts, calculations, and inferences, and avoid causal overclaiming.",
        ),
        CapabilityCard(
            id = "research",
            kind = CapabilityKind.TASK,
            summaryZh = "最新信息、网页和外部资料研究",
            summaryEn = "current information, webpages, and external research",
            instructionZh = "需要时调用工具核实来源和日期，无法核实时明确说明限制。",
            instructionEn = "Use tools when needed to verify sources and dates, and state limits when verification fails.",
        ),
        CapabilityCard(
            id = "writing-editing",
            kind = CapabilityKind.TASK,
            summaryZh = "写作、润色、改写和翻译",
            summaryEn = "writing, editing, rewriting, and translation",
            instructionZh = "保留目标受众、用途、语气、术语和原文事实含义。",
            instructionEn = "Preserve audience, purpose, tone, terminology, and factual meaning.",
        ),
        CapabilityCard(
            id = "markdown-table",
            kind = CapabilityKind.FORMAT,
            summaryZh = "二维数据、对比项和参数矩阵",
            summaryEn = "two-dimensional data, comparisons, and matrices",
            instructionZh = "使用 GFM 表格时，正文结束后留空行，表头、分隔行和每个数据行列数一致；每一行独占一行，无法保证合法时改用列表。",
            instructionEn = "For GFM tables, leave a blank line after prose, keep equal columns in the header, separator, and every data row, put each row on its own line, and use a list when validity cannot be guaranteed.",
        ),
        CapabilityCard(
            id = "structured-output",
            kind = CapabilityKind.FORMAT,
            summaryZh = "报告、审查、摘要和固定字段输出",
            summaryEn = "reports, reviews, summaries, and fixed fields",
            instructionZh = "严格遵守用户要求的字段和顺序，不增加无关内容。",
            instructionEn = "Follow the requested fields and order without unrelated additions.",
        ),
        CapabilityCard(
            id = "source-citations",
            kind = CapabilityKind.FORMAT,
            summaryZh = "需要来源、日期和证据对应关系",
            summaryEn = "sources, dates, and evidence mapping",
            instructionZh = "让来源与具体结论对应，标明信息日期和来源可靠性。",
            instructionEn = "Map sources to specific claims and state information dates and reliability.",
        ),
        CapabilityCard(
            id = "financial-caution",
            kind = CapabilityKind.SAFETY,
            summaryZh = "财务、投资、收益和估值内容",
            summaryEn = "financial, investment, return, and valuation content",
            instructionZh = "区分已核实事实、计算和推断，明确数据基准日、来源性质和投资不确定性，不把非官方材料写成审计财报。",
            instructionEn = "Separate verified facts, calculations, and inferences; state the data date and source status; mark investment uncertainty and never present unofficial material as audited financial statements.",
        ),
        CapabilityCard(
            id = "privacy-safety",
            kind = CapabilityKind.SAFETY,
            summaryZh = "隐私、凭据和敏感信息",
            summaryEn = "privacy, credentials, and sensitive information",
            instructionZh = "最小化敏感信息复述，不输出完整凭据和不必要的个人数据。",
            instructionEn = "Minimize sensitive repetition and never output complete credentials or unnecessary personal data.",
        ),
    )

    private val cardsById = cards.associateBy(CapabilityCard::id)
    private val taskIds = cards.filter { it.kind == CapabilityKind.TASK }.mapTo(linkedSetOf(), CapabilityCard::id)

    internal fun capabilityIds(): Set<String> = cardsById.keys

    fun systemCatalog(userLanguage: String): String {
        val isChinese = userLanguage.startsWith("zh", ignoreCase = true)
        val lines = cards.joinToString("\n") { card ->
            val summary = if (isChinese) card.summaryZh else card.summaryEn
            "${card.id}: $summary"
        }
        return if (isChinese) {
            """
            # 能力卡目录
            根据用户目标自主选择能力卡。先选恰好一个任务卡，再按需要组合格式卡和安全卡。选择后调用 `$SELECT_TOOL_NAME`，不要在回答中提及能力卡。能力卡不授予新工具权限，外部工具仍须按现有工具规则调用。
            $lines
            """.trimIndent()
        } else {
            """
            # Capability cards
            Choose cards from the user's goal. Select exactly one task card, then add needed format and safety cards. Call `$SELECT_TOOL_NAME` before the final answer and do not mention cards. Cards never grant new tool permissions.
            $lines
            """.trimIndent()
        }
    }

    fun selectionToolDefinition(): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to SELECT_TOOL_NAME,
            "description" to "Select the applicable EveryTalk capability cards before answering. Choose exactly one task card and any needed format or safety cards.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "capabilities" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "string",
                            "enum" to cards.map(CapabilityCard::id),
                        ),
                        "minItems" to 1,
                        "maxItems" to 5,
                        "description" to "Capability card IDs selected for this answer.",
                    ),
                    "language" to mapOf(
                        "type" to "string",
                        "enum" to listOf("zh", "en"),
                        "description" to "Answer language, used to return the matching instruction text.",
                    ),
                ),
                "required" to listOf("capabilities", "language"),
                "additionalProperties" to false,
            ),
        ),
    )

    fun executeSelection(arguments: JsonObject): JsonObject {
        val requested = arguments["capabilities"]
            ?.let { it as? JsonArray }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
            .distinct()

        val unknown = requested.filterNot(cardsById::containsKey)
        if (unknown.isNotEmpty()) {
            return errorResult("unknown capability IDs: ${unknown.joinToString()}")
        }
        if (requested.size !in 1..5) {
            return errorResult("select between 1 and 5 capability IDs")
        }

        val selected = requested.map(cardsById::getValue)
        val selectedTasks = selected.filter { it.kind == CapabilityKind.TASK }
        if (selectedTasks.size > 1) {
            return errorResult("select exactly one task card; choose only one of: ${taskIds.joinToString()}")
        }

        val effective = if (selectedTasks.isEmpty()) {
            listOf(cardsById.getValue("general-answer")) + selected
        } else {
            selected
        }
        val instructionsKey = arguments["language"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.lowercase()
            ?.takeIf { it == "zh" || it == "en" }
            ?: "en"
        val instructions = effective.joinToString("\n") { card ->
            val text = if (instructionsKey == "zh") card.instructionZh else card.instructionEn
            "- ${card.id}: $text"
        }
        return buildJsonObject {
            put("ok", true)
            putJsonArray("selected") {
                effective.forEach { add(JsonPrimitive(it.id)) }
            }
            put("instructions", instructions)
        }
    }

    private fun errorResult(message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", message)
        put("retry", "Call $SELECT_TOOL_NAME again with valid capability IDs.")
    }
}
