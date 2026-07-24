package com.android.everytalk.data.network

internal enum class PromptPrimary {
    GENERAL_QA,
    EXPLANATION_TUTORING,
    CODING_IMPLEMENTATION,
    CODING_DEBUGGING,
    CODE_REVIEW,
    SOFTWARE_ARCHITECTURE,
    MATH_SYMBOLIC,
    DATA_ANALYSIS,
    RESEARCH_REALTIME,
    TECHNICAL_DOCS_LOOKUP,
    WEB_CONTENT_ANALYSIS,
    DOCUMENT_ANALYSIS,
    SUMMARIZATION_EXTRACTION,
    WRITING_EDITING,
    TRANSLATION,
    DECISION_SUPPORT,
    CREATIVE_IDEATION,
    MULTIMODAL_ANALYSIS,
}

internal enum class PromptOutputModule {
    CODE_BLOCK,
    MATHJAX,
    TABLE,
    PROCEDURE,
    SOURCES,
    STRICT_STRUCTURE,
    LONG_FORM,
    COMPACT_MOBILE,
}

internal enum class PromptRiskModule {
    REALTIME_CAUTION,
    MEDICAL_CAUTION,
    LEGAL_CAUTION,
    FINANCIAL_CAUTION,
    SECURITY_CAUTION,
    PRIVACY_CAUTION,
}

internal data class PromptSelection(
    val primary: PromptPrimary,
    val outputModules: Set<PromptOutputModule>,
    val riskModules: Set<PromptRiskModule>,
    val suppressedModules: Set<PromptOutputModule>,
)

/** 纯本地提示词路由，不访问网络，也不保存会话状态。 */
internal object SystemPromptRouter {

    private val primaryPriority = listOf(
        PromptPrimary.TRANSLATION,
        PromptPrimary.CODING_DEBUGGING,
        PromptPrimary.CODE_REVIEW,
        PromptPrimary.CODING_IMPLEMENTATION,
        PromptPrimary.SOFTWARE_ARCHITECTURE,
        PromptPrimary.MATH_SYMBOLIC,
        PromptPrimary.DATA_ANALYSIS,
        PromptPrimary.RESEARCH_REALTIME,
        PromptPrimary.TECHNICAL_DOCS_LOOKUP,
        PromptPrimary.WEB_CONTENT_ANALYSIS,
        PromptPrimary.DOCUMENT_ANALYSIS,
        PromptPrimary.SUMMARIZATION_EXTRACTION,
        PromptPrimary.WRITING_EDITING,
        PromptPrimary.DECISION_SUPPORT,
        PromptPrimary.CREATIVE_IDEATION,
        PromptPrimary.MULTIMODAL_ANALYSIS,
        PromptPrimary.EXPLANATION_TUTORING,
        PromptPrimary.GENERAL_QA,
    )

    fun select(
        currentText: String,
        recentUserTexts: List<String> = emptyList(),
        attachmentNames: List<String> = emptyList(),
        attachmentMimeTypes: List<String> = emptyList(),
        queryIntentName: String? = null,
    ): PromptSelection {
        val normalized = currentText.lowercase().trim()
        val suppressed = detectSuppressedModules(normalized)
        val primary = selectPrimary(
            normalized = normalized,
            recentUserTexts = recentUserTexts,
            attachmentNames = attachmentNames,
            attachmentMimeTypes = attachmentMimeTypes,
            queryIntentName = queryIntentName,
        )
        val outputs = selectOutputModules(primary, normalized, suppressed)
        val risks = selectRiskModules(primary, normalized)
        return PromptSelection(
            primary = primary,
            outputModules = outputs,
            riskModules = risks,
            suppressedModules = suppressed,
        )
    }

    private fun selectPrimary(
        normalized: String,
        recentUserTexts: List<String>,
        attachmentNames: List<String>,
        attachmentMimeTypes: List<String>,
        queryIntentName: String?,
    ): PromptPrimary {
        val scores = PromptPrimary.entries.associateWith { 0 }.toMutableMap()
        scores[PromptPrimary.GENERAL_QA] = 1

        addIf(scores, PromptPrimary.TRANSLATION, normalized, 100,
            "翻译", "译成", "英译中", "中译英", "translate", "translation", "localize")
        addIf(scores, PromptPrimary.CODING_DEBUGGING, normalized, 100,
            "报错", "异常", "崩溃", "卡顿", "日志", "修复", "定位", "error", "exception", "crash", "bug", "slow", "log", "fix", "diagnose")
        addIf(scores, PromptPrimary.CODE_REVIEW, normalized, 100,
            "代码审查", "审查代码", "review", "检查改动", "线程安全", "安全审查")
        addIf(scores, PromptPrimary.CODING_IMPLEMENTATION, normalized, 100,
            "实现", "编写代码", "写代码", "写函数", "新增功能", "生成脚本", "implement", "write code", "create function", "build feature")
        addIf(scores, PromptPrimary.SOFTWARE_ARCHITECTURE, normalized, 100,
            "架构", "重构方案", "技术方案", "实施计划", "模块设计", "architecture", "refactor plan", "technical design")
        addIf(scores, PromptPrimary.MATH_SYMBOLIC, normalized, 100,
            "证明", "推导", "方程", "积分", "导数", "矩阵", "定理", "公式", "prove", "proof", "derivative", "integral", "matrix", "theorem", "equation")
        addIf(scores, PromptPrimary.DATA_ANALYSIS, normalized, 100,
            "数据分析", "统计分析", "趋势", "指标", "同比", "环比", "csv", "statistics", "analyze data", "trend", "metric")
        addIf(scores, PromptPrimary.SUMMARIZATION_EXTRACTION, normalized, 100,
            "总结", "摘要", "提取", "行动项", "会议纪要", "summarize", "summary", "extract", "action items")
        addIf(scores, PromptPrimary.WRITING_EDITING, normalized, 100,
            "润色", "改写", "写一封", "写文章", "写报告", "邮件", "polish", "rewrite", "draft email", "write an article")
        addIf(scores, PromptPrimary.DECISION_SUPPORT, normalized, 100,
            "对比", "怎么选", "选型", "利弊", "值得买吗", "compare", "which should", "pros and cons", "choose")
        addIf(scores, PromptPrimary.CREATIVE_IDEATION, normalized, 100,
            "创意", "起名", "命名", "脑暴", "故事", "idea", "brainstorm", "name ideas", "story")
        addIf(scores, PromptPrimary.EXPLANATION_TUTORING, normalized, 80,
            "解释", "讲解", "为什么", "原理", "入门", "教我", "怎么理解", "explain", "teach", "why", "how does")
        addIf(scores, PromptPrimary.WEB_CONTENT_ANALYSIS, normalized, 80,
            "总结这个网页", "分析这个网页", "读取网页", "read this page", "analyze this url")
        addIf(scores, PromptPrimary.DOCUMENT_ANALYSIS, normalized, 80,
            "分析附件", "分析文档", "合同", "财报", "pdf", "document", "contract", "report file")
        addIf(scores, PromptPrimary.MULTIMODAL_ANALYSIS, normalized, 80,
            "截图", "图片中", "图表", "看图", "image", "screenshot", "chart")

        when (queryIntentName?.uppercase()) {
            "DOCS_LOOKUP" -> scores.add(PromptPrimary.TECHNICAL_DOCS_LOOKUP, 90)
            "REALTIME_INFO" -> scores.add(PromptPrimary.RESEARCH_REALTIME, 90)
            "WEB_CONTENT_READ" -> scores.add(PromptPrimary.WEB_CONTENT_ANALYSIS, 90)
        }

        if (normalized.containsAny("最新", "今天", "实时", "当前", "新闻", "股价", "天气", "latest", "today", "realtime", "news", "stock", "weather")) {
            scores.add(PromptPrimary.RESEARCH_REALTIME, 70)
        }
        if (normalized.containsAny("官方文档", "api 文档", "sdk", "documentation", "docs", "migration guide")) {
            scores.add(PromptPrimary.TECHNICAL_DOCS_LOOKUP, 70)
        }
        if ("http://" in normalized || "https://" in normalized) {
            scores.add(PromptPrimary.WEB_CONTENT_ANALYSIS, 60)
        }

        val normalizedNames = attachmentNames.map { it.lowercase() }
        val normalizedMimes = attachmentMimeTypes.map { it.lowercase() }
        if (normalizedNames.any { it.endsWithAny(".log", ".stacktrace") }) {
            scores.add(PromptPrimary.CODING_DEBUGGING, 90)
        }
        if (normalizedNames.any { it.endsWithAny(".kt", ".java", ".js", ".ts", ".py", ".go", ".rs", ".swift", ".xml", ".gradle") }) {
            scores.add(PromptPrimary.CODING_IMPLEMENTATION, 70)
        }
        if (normalizedNames.any { it.endsWithAny(".csv", ".xlsx", ".xls") }) {
            scores.add(PromptPrimary.DATA_ANALYSIS, 90)
        }
        if (normalizedNames.any { it.endsWithAny(".pdf", ".doc", ".docx", ".txt", ".md") } ||
            normalizedMimes.any { it.contains("pdf") || it.contains("word") || it.startsWith("text/") }
        ) {
            scores.add(PromptPrimary.DOCUMENT_ANALYSIS, 80)
        }
        if (normalizedMimes.any { it.startsWith("image/") }) {
            scores.add(PromptPrimary.MULTIMODAL_ANALYSIS, 80)
        }

        if (isShortFollowUp(normalized) && recentUserTexts.isNotEmpty()) {
            val inherited = select(
                currentText = recentUserTexts.last(),
                recentUserTexts = emptyList(),
            ).primary
            scores.add(inherited, 40)
        }

        val maxScore = scores.values.maxOrNull() ?: 0
        if (maxScore < 30) return PromptPrimary.GENERAL_QA
        return primaryPriority.first { scores.getValue(it) == maxScore }
    }

    private fun selectOutputModules(
        primary: PromptPrimary,
        normalized: String,
        suppressed: Set<PromptOutputModule>,
    ): Set<PromptOutputModule> {
        val outputs = linkedSetOf<PromptOutputModule>()
        if (primary in setOf(PromptPrimary.CODING_IMPLEMENTATION, PromptPrimary.CODING_DEBUGGING, PromptPrimary.CODE_REVIEW)) {
            outputs.addUnlessSuppressed(PromptOutputModule.CODE_BLOCK, suppressed)
        }
        if (primary == PromptPrimary.MATH_SYMBOLIC) {
            outputs.addUnlessSuppressed(PromptOutputModule.MATHJAX, suppressed)
        }
        if (primary in setOf(PromptPrimary.DATA_ANALYSIS, PromptPrimary.DECISION_SUPPORT) || normalized.containsAny("表格", "table", "对比表")) {
            outputs.addUnlessSuppressed(PromptOutputModule.TABLE, suppressed)
        }
        if (primary in setOf(PromptPrimary.CODING_IMPLEMENTATION, PromptPrimary.CODING_DEBUGGING) || normalized.containsAny("步骤", "教程", "怎么做", "step", "tutorial")) {
            outputs.addUnlessSuppressed(PromptOutputModule.PROCEDURE, suppressed)
        }
        if (primary in setOf(
                PromptPrimary.RESEARCH_REALTIME,
                PromptPrimary.TECHNICAL_DOCS_LOOKUP,
                PromptPrimary.WEB_CONTENT_ANALYSIS,
                PromptPrimary.DOCUMENT_ANALYSIS,
            )
        ) {
            outputs.addUnlessSuppressed(PromptOutputModule.SOURCES, suppressed)
        }
        if (primary in setOf(
                PromptPrimary.CODE_REVIEW,
                PromptPrimary.SOFTWARE_ARCHITECTURE,
                PromptPrimary.DOCUMENT_ANALYSIS,
                PromptPrimary.SUMMARIZATION_EXTRACTION,
                PromptPrimary.TRANSLATION,
                PromptPrimary.DECISION_SUPPORT,
                PromptPrimary.CREATIVE_IDEATION,
                PromptPrimary.MULTIMODAL_ANALYSIS,
            )
        ) {
            outputs.addUnlessSuppressed(PromptOutputModule.STRICT_STRUCTURE, suppressed)
        }
        if (normalized.containsAny("详细报告", "长文", "深入分析", "long form", "detailed report")) {
            outputs.addUnlessSuppressed(PromptOutputModule.LONG_FORM, suppressed)
        }
        if (primary == PromptPrimary.GENERAL_QA || normalized.containsAny("简短", "简单说", "简洁", "brief", "concise")) {
            outputs.addUnlessSuppressed(PromptOutputModule.COMPACT_MOBILE, suppressed)
        }
        if (normalized.containsAny("代码", "code", "脚本", "配置") && primary != PromptPrimary.EXPLANATION_TUTORING) {
            outputs.addUnlessSuppressed(PromptOutputModule.CODE_BLOCK, suppressed)
        }
        if (normalized.containsAny("公式", "latex", "mathjax", "方程")) {
            outputs.addUnlessSuppressed(PromptOutputModule.MATHJAX, suppressed)
        }
        return outputs
    }

    private fun selectRiskModules(primary: PromptPrimary, normalized: String): Set<PromptRiskModule> {
        val risks = linkedSetOf<PromptRiskModule>()
        if (primary == PromptPrimary.RESEARCH_REALTIME) risks += PromptRiskModule.REALTIME_CAUTION
        if (normalized.containsAny("症状", "疾病", "药物", "诊断", "检查结果", "medical", "symptom", "medicine", "diagnosis")) {
            risks += PromptRiskModule.MEDICAL_CAUTION
        }
        if (normalized.containsAny("法律", "合同", "诉讼", "合规", "law", "legal", "lawsuit", "compliance")) {
            risks += PromptRiskModule.LEGAL_CAUTION
        }
        if (normalized.containsAny("股票", "投资", "财报", "收益", "估值", "stock", "investment", "earnings", "valuation")) {
            risks += PromptRiskModule.FINANCIAL_CAUTION
        }
        if (normalized.containsAny("漏洞", "渗透", "密钥", "权限", "恶意代码", "vulnerability", "exploit", "secret", "malware")) {
            risks += PromptRiskModule.SECURITY_CAUTION
        }
        if (normalized.containsAny("身份证", "手机号", "聊天记录", "隐私", "密码", "identity", "phone number", "privacy", "password")) {
            risks += PromptRiskModule.PRIVACY_CAUTION
        }
        return risks
    }

    private fun detectSuppressedModules(normalized: String): Set<PromptOutputModule> {
        val suppressed = linkedSetOf<PromptOutputModule>()
        if (normalized.containsAny("不要表格", "别用表格", "不用表格", "用列表回答", "no table", "without a table")) {
            suppressed += PromptOutputModule.TABLE
        }
        if (normalized.containsAny("不要公式", "不用 latex", "纯文本公式", "no formula", "no latex")) {
            suppressed += PromptOutputModule.MATHJAX
        }
        if (normalized.containsAny("不要代码", "只解释思路", "no code", "explain only")) {
            suppressed += PromptOutputModule.CODE_BLOCK
        }
        if (normalized.containsAny("不要引用", "不要来源", "只总结", "no sources", "without citations")) {
            suppressed += PromptOutputModule.SOURCES
        }
        if (normalized.containsAny("简短回答", "不要展开", "brief answer", "do not elaborate")) {
            suppressed += PromptOutputModule.LONG_FORM
            suppressed += PromptOutputModule.PROCEDURE
        }
        return suppressed
    }

    private fun addIf(
        scores: MutableMap<PromptPrimary, Int>,
        primary: PromptPrimary,
        text: String,
        points: Int,
        vararg keywords: String,
    ) {
        if (text.containsAny(*keywords)) scores.add(primary, points)
    }

    private fun MutableMap<PromptPrimary, Int>.add(primary: PromptPrimary, points: Int) {
        this[primary] = getValue(primary) + points
    }

    private fun MutableSet<PromptOutputModule>.addUnlessSuppressed(
        module: PromptOutputModule,
        suppressed: Set<PromptOutputModule>,
    ) {
        if (module !in suppressed) add(module)
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { it in this }

    private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any(::endsWith)

    private fun isShortFollowUp(text: String): Boolean {
        if (text.length > 24) return false
        return text.containsAny(
            "继续", "再来", "详细一点", "开始修复", "开始实施", "然后呢", "下一步",
            "continue", "go on", "more detail", "start fixing", "next",
        )
    }
}
