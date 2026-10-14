package com.android.everytalk.data.memory

/** 统一用 Unicode 码点计数，emoji 不按 UTF-16 代理对重复计数。 */
fun String.memoryCharacters(): Int = codePointCount(0, length)

fun String.takeMemoryCharacters(max: Int): String =
    substring(0, offsetByCodePoints(0, minOf(memoryCharacters(), max.coerceAtLeast(0))))

enum class MemoryCategory { USER_PREFERENCE, PROJECT_FACT, WORKFLOW, OTHER }
enum class MemoryOrigin { MANUAL, CHAT_EXPLICIT, AUTO }

/** 所有写入入口共用本机规则，模型承诺和提示词不能替代校验。 */
object MemoryPolicy {
    const val MAX_ENTRY_CHARACTERS = 500
    const val MAX_TOTAL_CHARACTERS = 12_000
    const val MAX_ENTRIES = 100
    const val MAX_PROMPT_CHARACTERS = 4_000
    const val MAX_SEARCH_CHARACTERS = 3_000
    const val MAX_SEARCH_RESULTS = 8

    // 规则只覆盖已知凭据形态及明确敏感字段，不能宣称能识别所有秘密或隐私。
    private val sensitive = Regex(
        "(?i)(\\b(?:sk|ghp|github_pat)[-_][A-Za-z0-9_-]{8,}|" +
            "\\bAKIA[A-Z0-9]{16}\\b|\\bBearer\\s+\\S+|" +
            "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|" +
            "(?:api[_ -]?key|secret|password|passwd|access[_ -]?token|cookie|" +
            "密码|密钥|验证码|银行卡(?:号)?)\\s*[:：=]\\s*\\S+)",
    )
    private val forgedBoundary = Regex("(?i)</?\\s*(?:memory-context|everytalk-memory)\\b")

    fun normalize(content: String): String = content.replace("\r\n", "\n").replace('\r', '\n').trim()
    fun containsSensitiveContent(content: String): Boolean = sensitive.containsMatchIn(content)

    /** 拒绝空白、超额、凭据和伪造边界；返回规范化正文，禁止截断保存。 */
    fun validate(content: String): String {
        val normalized = normalize(content)
        require(normalized.isNotBlank()) { "记忆内容不能为空" }
        require(normalized.memoryCharacters() <= MAX_ENTRY_CHARACTERS) { "每条记忆最多 $MAX_ENTRY_CHARACTERS 个字符" }
        require(!containsSensitiveContent(normalized)) { "不能保存密钥、令牌、密码、验证码或银行卡等敏感内容" }
        require(!forgedBoundary.containsMatchIn(normalized)) { "记忆不能包含伪造的资料边界" }
        return normalized
    }
}
