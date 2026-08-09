package com.android.everytalk.util

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

enum class AiContentSafetyCategory {
    CHILD_SEXUAL_ABUSE,
    NON_CONSENSUAL_INTIMATE_CONTENT,
    SEXUALLY_EXPLICIT_CONTENT,
    SELF_HARM_INSTRUCTIONS,
    GRAPHIC_VIOLENCE,
    HATE_OR_HARASSMENT,
    DANGEROUS_ACTIVITIES,
    FRAUD_OR_IMPERSONATION,
    MALICIOUS_CODE,
}

sealed interface AiContentSafetyDecision {
    data object Allowed : AiContentSafetyDecision

    data class Blocked(
        val category: AiContentSafetyCategory,
        val userMessage: String,
    ) : AiContentSafetyDecision
}

/** Google Play 生成式 AI 内容政策的本地纵深防护。 */
object AiContentSafetyPolicy {
    private val minorTerms = terms(
        "未成年人", "未成年", "儿童", "幼童", "小学生", "初中生", "萝莉",
        "child", "children", "underage", "preteen", "loli",
    )
    private val sexualTerms = terms(
        "色情", "裸照", "裸体", "性行为", "性爱", "露点", "淫秽", "成人视频",
        "porn", "nude", "naked", "sexual", "sex video", "explicit sex",
    )
    private val nonConsensualTerms = terms(
        "未经同意", "非自愿", "强奸", "迷奸", "偷拍", "换脸色情", "深度伪造色情",
        "non-consensual", "without consent", "rape", "drugged", "sexual deepfake",
    )
    private val selfHarmTerms = terms(
        "自杀", "自残", "结束生命", "轻生", "suicide", "self-harm", "kill myself",
    )
    private val graphicViolenceTerms = terms(
        "血腥", "肢解", "斩首", "断肢", "开膛", "虐杀", "gore", "dismemberment",
        "beheading", "decapitation", "disembowelment",
    )
    private val hateOrHarassmentTerms = terms(
        "仇恨言论", "种族清洗", "纳粹宣传", "霸凌", "人肉骚扰", "死亡威胁",
        "hate speech", "ethnic cleansing", "nazi propaganda", "bullying", "doxxing",
        "death threat",
    )
    private val dangerousActivityTerms = terms(
        "制作炸弹", "制造炸弹", "炸弹配方", "爆炸物配方", "毒药配方", "自制枪支",
        "bomb making", "build a bomb", "bomb recipe", "explosive recipe", "poison recipe",
        "homemade gun",
    )
    private val deceptiveTerms = terms(
        "伪造", "仿造", "假冒", "冒充", "假护照", "假证件", "诈骗", "钓鱼", "骗取",
        "fake", "forge",
        "counterfeit", "impersonate", "scam", "phishing",
    )
    private val deceptiveTargets = terms(
        "身份证", "护照", "驾照", "证件", "银行流水", "官方文件", "语音", "视频",
        "identity card", "passport", "driver license", "bank statement", "official document",
        "voice recording", "video recording",
    )
    private val malwareTerms = terms(
        "勒索软件", "键盘记录器", "木马", "恶意代码", "ransomware", "keylogger",
        "trojan", "malware",
    )
    private val harmfulCodeIntentTerms = terms(
        "窃取", "绕过检测", "逃避检测", "投放", "入侵", "攻击", "远程控制",
        "steal", "exfiltrate", "evade detection", "bypass detection", "deploy", "attack",
        "remote control",
    )
    private val requestTerms = terms(
        "如何", "怎么", "方法", "步骤", "教程", "生成", "制作", "创建", "写", "写出", "描写",
        "绘制", "画出", "合成", "换脸", "编写", "开发", "how to", "method", "steps",
        "tutorial", "generate", "create", "make", "write", "draw", "depict", "produce", "build",
    )
    private val productionTerms = terms(
        "生成", "制作", "创建", "写", "写出", "描写", "绘制", "画出", "合成", "换脸", "编写",
        "开发", "generate", "create", "make", "write", "draw", "depict", "produce", "build",
    )
    private val protectiveTerms = terms(
        "识别", "检测", "防止", "防范", "预防", "劝阻", "求助", "帮助", "危害", "法律",
        "新闻", "教育", "研究", "分析", "防御", "修复", "热线", "健康", "医学", "医疗",
        "科普", "identify", "detect", "prevent", "protect", "support", "help", "research",
        "analysis", "education", "defend", "news", "recovery", "health", "medical",
    )

    fun evaluateUserInput(
        text: String,
        isImageGeneration: Boolean,
    ): AiContentSafetyDecision {
        if (text.isBlank()) return AiContentSafetyDecision.Allowed

        val normalized = NormalizedText.from(text)
        val requestsContent = isImageGeneration || normalized.containsAny(requestTerms)
        val explicitlyProducesContent = isImageGeneration || normalized.containsAny(productionTerms)
        val protectiveContext = normalized.containsAny(protectiveTerms)

        if (
            requestsContent &&
            normalized.containsAny(minorTerms) &&
            normalized.containsAny(sexualTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.CHILD_SEXUAL_ABUSE,
                "该请求可能涉及未成年人性剥削内容，已被安全过滤器拦截。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(nonConsensualTerms) &&
            normalized.containsAny(sexualTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.NON_CONSENSUAL_INTIMATE_CONTENT,
                "该请求可能涉及非自愿私密内容，已被安全过滤器拦截。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(sexualTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.SEXUALLY_EXPLICIT_CONTENT,
                "该请求可能生成露骨色情内容，已被安全过滤器拦截。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(selfHarmTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.SELF_HARM_INSTRUCTIONS,
                "该请求可能包含危险的自伤操作指导，已被安全过滤器拦截。如有人正处于紧急危险，请立即联系当地急救服务。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(graphicViolenceTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.GRAPHIC_VIOLENCE,
                "该请求可能生成血腥暴力内容，已被安全过滤器拦截。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(hateOrHarassmentTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.HATE_OR_HARASSMENT,
                "该请求可能生成仇恨、霸凌或骚扰内容，已被安全过滤器拦截。",
            )
        }

        if (
            normalized.containsAny(dangerousActivityTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.DANGEROUS_ACTIVITIES,
                "该请求可能提供危险行为指导，已被安全过滤器拦截。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(deceptiveTerms) &&
            normalized.containsAny(deceptiveTargets) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.FRAUD_OR_IMPERSONATION,
                "该请求可能用于欺诈、冒充或伪造，已被安全过滤器拦截。",
            )
        }

        if (
            requestsContent &&
            normalized.containsAny(malwareTerms) &&
            normalized.containsAny(harmfulCodeIntentTerms) &&
            !(protectiveContext && !explicitlyProducesContent)
        ) {
            return blocked(
                AiContentSafetyCategory.MALICIOUS_CODE,
                "该请求可能用于制作或投放恶意代码，已被安全过滤器拦截。",
            )
        }

        return AiContentSafetyDecision.Allowed
    }

    fun systemInstruction(language: String): String = if (language.startsWith("zh")) {
        """
        # AI 内容安全
        禁止生成或协助生成儿童性剥削、露骨色情、非自愿私密内容、欺诈伪造、自伤及危险行为指导、血腥暴力、仇恨骚扰或恶意代码。允许预防、识别、教育、研究、新闻和求助场景。简短拒绝危险请求并提供安全替代帮助。
        """.trimIndent()
    } else {
        """
        # AI content safety
        Do not facilitate child sexual abuse, explicit sexual or non-consensual intimate content, fraud, self-harm or dangerous instructions, graphic violence, hate, harassment, or malware. Allow prevention, education, research, news, and support. Briefly refuse unsafe requests and offer safer help.
        """.trimIndent()
    }

    /** Gemini 原生安全阈值，作为系统指令之外的提供商侧防线。 */
    fun geminiSafetySettings(): JsonArray = buildJsonArray {
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        ).forEach { category ->
            addJsonObject {
                put("category", category)
                put("threshold", "BLOCK_MEDIUM_AND_ABOVE")
            }
        }
    }

    private fun blocked(
        category: AiContentSafetyCategory,
        message: String,
    ): AiContentSafetyDecision.Blocked = AiContentSafetyDecision.Blocked(category, message)

    private data class SafetyTerm(
        val spaced: String,
        val compact: String,
    )

    private data class NormalizedText(
        val spaced: String,
        val compact: String,
    ) {
        fun containsAny(terms: List<SafetyTerm>): Boolean = terms.any { term ->
            spaced.contains(term.spaced) || compact.contains(term.compact)
        }

        companion object {
            fun from(value: String): NormalizedText {
                val spaced = buildString(value.length) {
                    var previousWasSpace = true
                    value.lowercase(Locale.ROOT).forEach { character ->
                        if (character.isLetterOrDigit()) {
                            append(character)
                            previousWasSpace = false
                        } else if (!previousWasSpace) {
                            append(' ')
                            previousWasSpace = true
                        }
                    }
                }.trim()
                return NormalizedText(
                    spaced = spaced,
                    compact = spaced.filterNot(Char::isWhitespace),
                )
            }
        }
    }

    private fun terms(vararg values: String): List<SafetyTerm> = values.map { value ->
        val normalized = NormalizedText.from(value)
        SafetyTerm(normalized.spaced, normalized.compact)
    }
}
