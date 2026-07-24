package com.android.everytalk.util.text

import com.android.everytalk.data.network.PromptCapabilityCatalog

/**
 * 清理模型误输出到正文中的能力选择卡。
 *
 * 能力选择结果属于模型上下文内部协议，不能进入用户可见的回答正文。
 */
object CapabilityCardOutputSanitizer {
    private const val MAX_PENDING_CHARS = 2048

    private val capabilityIds = PromptCapabilityCatalog.capabilityIds()
    private val capabilityIdPattern = capabilityIds.joinToString("|") { Regex.escape(it) }
    private val headerPattern = Regex(
        """^\s*(?:[-*•]\s*)?(?:#{1,6}\s*)?(?:(?:本次|当前|本轮|局方|所选|已选)\s*)?(?:能力卡?选择|capability(?:\s+cards?)?\s+selection|selected\s+capabilities)\s*[:：]?\s*$""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val cardLinePattern = Regex(
        """^\s*(?:(?:[-*•]|\d+[.)])\s*)?(?:`|\*\*)?(?:$capabilityIdPattern)(?:`|\*\*)?(?:\s*[,，、]\s*(?:`|\*\*)?(?:$capabilityIdPattern)(?:`|\*\*)?)*\s*$""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val horizontalRulePattern = Regex("""^\s*(?:-{3,}|_{3,}|\*{3,})\s*$""")
    private val headerPrefixes = listOf(
        "能力选择",
        "能力卡选择",
        "本次能力选择",
        "当前能力选择",
        "本轮能力选择",
        "局方能力选择",
        "所选能力选择",
        "已选能力选择",
        "capability selection",
        "capability cards selection",
        "selected capabilities",
    )
    private val detectionMarkers = listOf(
        "能力选择",
        "能力卡选择",
        "capability selection",
        "capability cards selection",
        "selected capabilities",
    )

    /** 清理已完整接收的正文，保留卡片之后的正常回答。 */
    fun sanitize(text: String): String {
        if (text.isEmpty() || detectionMarkers.none { text.contains(it, ignoreCase = true) }) return text

        val lines = text.lines()
        val output = ArrayList<String>(lines.size)
        var index = 0
        while (index < lines.size) {
            val standaloneMarker = index + 1 < lines.size && isStandaloneMarker(lines[index])
            val headerIndex = when {
                isHeader(lines[index]) -> index
                standaloneMarker && isHeader(lines[index + 1]) -> index + 1
                else -> -1
            }
            if (headerIndex < 0) {
                output += lines[index]
                index++
                continue
            }

            var cursor = headerIndex + 1
            while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            var cardLineCount = 0
            while (cursor < lines.size && isCardLine(lines[cursor])) {
                cardLineCount++
                cursor++
                while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            }
            if (cardLineCount == 0) {
                output += lines[index]
                index++
                continue
            }

            while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            if (cursor < lines.size && isHorizontalRule(lines[cursor])) {
                cursor++
                while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            }
            index = cursor
        }

        return output.joinToString("\n")
    }

    private fun isHeader(line: String): Boolean = headerPattern.matches(line)

    private fun isCardLine(line: String): Boolean = cardLinePattern.matches(line)

    private fun isPotentialCardLine(line: String): Boolean {
        val normalized = line.trim()
            .replaceFirst(Regex("""^(?:[-*•]|\d+[.)])\s*"""), "")
            .trim('`', '*', ' ', '\t')
            .lowercase()
        if (normalized.isEmpty()) return true
        return capabilityIds.any { id ->
            val knownId = id.lowercase()
            knownId.startsWith(normalized) || normalized.startsWith(knownId)
        }
    }

    private fun isHorizontalRule(line: String): Boolean = horizontalRulePattern.matches(line)

    private fun isStandaloneMarker(line: String): Boolean = line.trim() in setOf("-", "*", "•")

    private fun isPotentialHeaderPrefix(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart() ?: return true
        if (isStandaloneMarker(firstLine)) return true
        val normalized = firstLine.removePrefix("#").trimStart().lowercase()
        return headerPrefixes.any { prefix ->
            val normalizedPrefix = prefix.lowercase()
            normalizedPrefix.startsWith(normalized) || normalized.startsWith(normalizedPrefix)
        }
    }

    private fun hasCompleteHeader(text: String): Boolean {
        val lines = text.lines()
        val firstContentIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstContentIndex < 0) return false
        val firstLine = lines[firstContentIndex]
        return isHeader(firstLine) ||
            (isStandaloneMarker(firstLine) && firstContentIndex + 1 < lines.size && isHeader(lines[firstContentIndex + 1]))
    }

    private fun hasNonCardContentAfterHeader(text: String): Boolean {
        val lines = text.lines()
        val headerIndex = lines.indices.firstOrNull { index ->
            isHeader(lines[index]) ||
                (isStandaloneMarker(lines[index]) && index + 1 < lines.size && isHeader(lines[index + 1]))
        } ?: return false
        val actualHeaderIndex = if (isHeader(lines[headerIndex])) headerIndex else headerIndex + 1
        var cursor = actualHeaderIndex + 1
        while (cursor < lines.size && lines[cursor].isBlank()) cursor++
        while (cursor < lines.size && (isCardLine(lines[cursor]) || isPotentialCardLine(lines[cursor]))) {
            cursor++
            while (cursor < lines.size && lines[cursor].isBlank()) cursor++
        }
        return cursor < lines.size && !isHorizontalRule(lines[cursor]) && lines[cursor].isNotBlank()
    }

    /**
     * 流式清理器。卡片头和能力 ID 可能跨多个网络块，确认前暂存这段前缀。
     */
    class StreamingDetector {
        private val pending = StringBuilder()
        private var decided = false
        private var enabled = false

        fun enable() {
            enabled = true
        }

        fun isEnabled(): Boolean = enabled

        fun appendAndSanitize(chunk: String): String {
            if (chunk.isEmpty()) return chunk
            if (!enabled) return chunk
            if (decided) return sanitize(chunk)

            pending.append(chunk)
            val current = pending.toString()
            if (!isPotentialHeaderPrefix(current)) {
                return releaseCurrent()
            }

            if (hasCompleteHeader(current)) {
                val cleaned = sanitize(current)
                if (cleaned != current) {
                    pending.clear()
                    decided = true
                    return cleaned
                }
                if (hasNonCardContentAfterHeader(current)) {
                    return releaseCurrent()
                }
            }

            if (pending.length > MAX_PENDING_CHARS) {
                return releaseCurrent()
            }
            return ""
        }

        fun flush(): String {
            if (!enabled || pending.isEmpty()) return ""
            return releaseCurrent()
        }

        fun reset() {
            pending.clear()
            decided = false
            enabled = false
        }

        private fun releaseCurrent(): String {
            val current = pending.toString()
            pending.clear()
            decided = true
            return sanitize(current)
        }
    }
}
