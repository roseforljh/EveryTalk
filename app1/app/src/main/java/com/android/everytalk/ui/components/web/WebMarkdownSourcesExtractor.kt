package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import com.android.everytalk.data.DataClass.WebSearchResult

internal data class WebMarkdownSourcesExtraction(
    val displayText: String,
    val sources: List<WebSearchResult>
)

internal object WebMarkdownSourcesExtractor {
    private data class SourceFence(
        val marker: Char,
        val length: Int,
    )

    private val sourcesHeaderRegex = Regex(
        pattern = """^\s{0,3}(?:#{1,6}\s*)?(?:sources?|参考来源|来源)\s*:?\s*$""",
        option = RegexOption.IGNORE_CASE
    )
    private val sourceFenceRegex = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val detailsTagRegex = Regex("""<\s*(/?)\s*details\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val sourcesMetadataRegex = Regex("""^\s*\[[^]]*sources?[^]]*]:\s*#\s*$""", RegexOption.IGNORE_CASE)
    private val markdownLinkRegex = Regex("""\[(.+?)]\((https?://[^\s)]+)[^)]*\)""")
    private val plainUrlRegex = Regex("""https?://\S+""")
    private val numberedCitationRegex = Regex("""^\s*\[\d+]\s+(.*?)(https?://\S+)\s*$""")
    private val bracketMarkdownCitationRegex = Regex("""^\s*\[\[\d+]]\((https?://[^\s)]+)[^)]*\)\s*$""")

    fun extract(text: String): WebMarkdownSourcesExtraction {
        if (text.isBlank()) {
            return WebMarkdownSourcesExtraction(displayText = text, sources = emptyList())
        }

        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        val protectedLines = findProtectedSourceLines(lines)

        for (index in lines.indices.reversed()) {
            if (protectedLines[index]) continue
            if (!sourcesHeaderRegex.matches(lines[index].trim())) continue

            val sources = parseExplicitSources(
                lines = lines,
                protectedLines = protectedLines,
                startIndex = index + 1,
            ) ?: continue
            if (sources.isEmpty()) continue

            return WebMarkdownSourcesExtraction(
                displayText = lines.take(index).joinToString("\n").trimEnd(),
                sources = sources
            )
        }

        val citationExtraction = extractTrailingCitations(lines, protectedLines)
        if (citationExtraction != null) {
            return citationExtraction
        }

        return WebMarkdownSourcesExtraction(displayText = text, sources = emptyList())
    }

    private fun parseSources(lines: List<String>): List<WebSearchResult> {
        val sources = mutableListOf<WebSearchResult>()
        val seenUrls = mutableSetOf<String>()

        lines.forEach { line ->
            val linkMatch = markdownLinkRegex.find(line)
            if (linkMatch != null) {
                val title = linkMatch.groupValues[1].trim().ifBlank { linkMatch.groupValues[2] }
                val url = cleanUrl(linkMatch.groupValues[2])
                if (seenUrls.add(url)) {
                    sources.add(
                        WebSearchResult(
                            index = sources.size + 1,
                            title = title,
                            href = url,
                            snippet = ""
                        )
                    )
                }
                return@forEach
            }

            val plainUrl = plainUrlRegex.find(line)?.value?.let(::cleanUrl)
            if (!plainUrl.isNullOrBlank() && seenUrls.add(plainUrl)) {
                sources.add(
                    WebSearchResult(
                        index = sources.size + 1,
                        title = plainUrl,
                        href = plainUrl,
                        snippet = ""
                    )
                )
            }
        }

        return sources
    }

    /**
     * 显式来源区必须位于消息末尾，且只能包含来源链接和已知元数据。
     * 只要后缀仍有代码、details 或普通正文，就保守保留完整消息。
     */
    private fun parseExplicitSources(
        lines: List<String>,
        protectedLines: BooleanArray,
        startIndex: Int,
    ): List<WebSearchResult>? {
        val sourceLines = lines.subList(startIndex, lines.size)
        sourceLines.forEachIndexed { relativeIndex, line ->
            if (protectedLines[startIndex + relativeIndex]) return null
            if (line.isBlank() || sourcesMetadataRegex.matches(line)) return@forEachIndexed
            if (!plainUrlRegex.containsMatchIn(line)) return null
        }
        return parseSources(sourceLines)
    }

    private fun extractTrailingCitations(
        lines: List<String>,
        protectedLines: BooleanArray,
    ): WebMarkdownSourcesExtraction? {
        var endExclusive = lines.size
        while (endExclusive > 0 && lines[endExclusive - 1].isBlank()) {
            endExclusive--
        }
        if (endExclusive == 0) return null

        var start = endExclusive
        val citationLines = mutableListOf<String>()
        while (start > 0) {
            if (protectedLines[start - 1]) break
            val line = lines[start - 1]
            if (line.isBlank()) {
                if (citationLines.isEmpty()) {
                    start--
                    continue
                }
                break
            }
            if (!isCitationLine(line)) break
            citationLines.add(0, line)
            start--
        }

        if (citationLines.isEmpty()) return null
        val sources = parseCitationSources(citationLines)
        if (sources.isEmpty()) return null

        return WebMarkdownSourcesExtraction(
            displayText = lines.take(start).joinToString("\n").trimEnd(),
            sources = sources
        )
    }

    /**
     * 来源抽取只处理顶层正文，代码区和 details 内的 URL 必须继续交给 Markdown 渲染。
     */
    private fun findProtectedSourceLines(lines: List<String>): BooleanArray {
        val protected = BooleanArray(lines.size)
        var fence: SourceFence? = null
        var detailsDepth = 0

        lines.forEachIndexed { index, line ->
            val activeFence = fence
            if (activeFence != null) {
                protected[index] = true
                if (isSourceFenceClosingLine(line, activeFence)) fence = null
                return@forEachIndexed
            }

            val openingFence = parseSourceFenceOpeningLine(line)
            if (openingFence != null) {
                protected[index] = true
                fence = openingFence
                return@forEachIndexed
            }

            if (isIndentedSourceCodeLine(line)) {
                protected[index] = true
                return@forEachIndexed
            }

            val detailsTags = findDetailsTagsOutsideInlineCode(line)
            val wasInsideDetails = detailsDepth > 0
            detailsTags.forEach { match ->
                if (match.groupValues[1].isEmpty()) {
                    detailsDepth++
                } else if (detailsDepth > 0) {
                    detailsDepth--
                }
            }
            if (wasInsideDetails || detailsDepth > 0 || detailsTags.isNotEmpty()) {
                protected[index] = true
            }
        }
        return protected
    }

    private fun parseSourceFenceOpeningLine(line: String): SourceFence? {
        val match = sourceFenceRegex.matchEntire(line) ?: return null
        val marker = match.groupValues[1]
        if (marker.first() == '`' && match.groupValues[2].contains('`')) return null
        return SourceFence(marker = marker.first(), length = marker.length)
    }

    private fun isSourceFenceClosingLine(line: String, fence: SourceFence): Boolean {
        val trimmed = line.dropWhile { it == ' ' }.takeIf { line.length - it.length <= 3 } ?: return false
        if (trimmed.firstOrNull() != fence.marker) return false
        val markerLength = trimmed.takeWhile { it == fence.marker }.length
        return markerLength >= fence.length && trimmed.substring(markerLength).isBlank()
    }

    private fun isIndentedSourceCodeLine(line: String): Boolean {
        var columns = 0
        line.forEach { character ->
            when (character) {
                ' ' -> columns++
                '\t' -> columns += 4 - columns % 4
                else -> return columns >= 4
            }
            if (columns >= 4) return true
        }
        return columns >= 4
    }

    private fun findDetailsTagsOutsideInlineCode(line: String): List<MatchResult> {
        val visibleText = maskInlineCodeSpans(line)
        return detailsTagRegex.findAll(visibleText)
            .filterNot { match -> isEscaped(visibleText, match.range.first) }
            .toList()
    }

    private fun maskInlineCodeSpans(line: String): String {
        val masked = line.toCharArray()
        var openingStart = 0
        while (openingStart < line.length) {
            openingStart = line.indexOf('`', openingStart)
            if (openingStart < 0) break

            val openingLength = line.countRun(openingStart, '`')
            if (isEscaped(line, openingStart)) {
                openingStart += openingLength
                continue
            }

            var closingStart = line.indexOf('`', openingStart + openingLength)
            while (closingStart >= 0) {
                val closingLength = line.countRun(closingStart, '`')
                if (!isEscaped(line, closingStart) && closingLength == openingLength) break
                closingStart = line.indexOf('`', closingStart + closingLength)
            }

            if (closingStart < 0) {
                openingStart += openingLength
                continue
            }

            (openingStart until closingStart + openingLength).forEach { masked[it] = ' ' }
            openingStart = closingStart + openingLength
        }
        return masked.concatToString()
    }

    private fun String.countRun(startIndex: Int, character: Char): Int {
        var endIndex = startIndex
        while (endIndex < length && this[endIndex] == character) endIndex++
        return endIndex - startIndex
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashCount = 0
        var currentIndex = index - 1
        while (currentIndex >= 0 && text[currentIndex] == '\\') {
            slashCount++
            currentIndex--
        }
        return slashCount % 2 == 1
    }

    private fun isCitationLine(line: String): Boolean {
        return numberedCitationRegex.matches(line) ||
            bracketMarkdownCitationRegex.matches(line)
    }

    private fun parseCitationSources(lines: List<String>): List<WebSearchResult> {
        val sources = mutableListOf<WebSearchResult>()
        val seenUrls = mutableSetOf<String>()
        lines.forEach { line ->
            val markdownMatch = bracketMarkdownCitationRegex.matchEntire(line)
            val url = when {
                markdownMatch != null -> markdownMatch.groupValues[1]
                numberedCitationRegex.matches(line) -> numberedCitationRegex.matchEntire(line)?.groupValues?.get(2).orEmpty()
                else -> ""
            }.let(::cleanUrl)
            if (url.isBlank() || !seenUrls.add(url)) return@forEach
            val title = numberedCitationRegex.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: url
            sources.add(
                WebSearchResult(
                    index = sources.size + 1,
                    title = title,
                    href = url,
                    snippet = ""
                )
            )
        }
        return sources
    }

    private fun cleanUrl(raw: String): String {
        return raw.trim().trimEnd(
            '.', ',', ';', ':', ')', ']', '}', '"', '\'',
            '。', '，', '；', '：', '）', '】', '》'
        )
    }
}
