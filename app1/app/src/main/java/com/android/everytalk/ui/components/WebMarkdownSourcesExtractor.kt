package com.android.everytalk.ui.components

import com.android.everytalk.data.DataClass.WebSearchResult

internal data class WebMarkdownSourcesExtraction(
    val displayText: String,
    val sources: List<WebSearchResult>
)

internal object WebMarkdownSourcesExtractor {
    private val sourcesHeaderRegex = Regex(
        pattern = """^\s{0,3}(?:#{1,6}\s*)?(?:sources?|参考来源|来源)\s*:?\s*$""",
        option = RegexOption.IGNORE_CASE
    )
    private val markdownLinkRegex = Regex("""\[(.+?)]\((https?://[^\s)]+)[^)]*\)""")
    private val plainUrlRegex = Regex("""https?://\S+""")
    private val numberedCitationRegex = Regex("""^\s*\[\d+]\s+(.*?)(https?://\S+)\s*$""")
    private val footnoteCitationRegex = Regex("""^\s*\[\^(\d+)]:\s+(.*?)(https?://\S+)\s*$""")
    private val bracketMarkdownCitationRegex = Regex("""^\s*\[\[\d+]]\((https?://[^\s)]+)[^)]*\)\s*$""")

    fun extract(text: String): WebMarkdownSourcesExtraction {
        if (text.isBlank()) {
            return WebMarkdownSourcesExtraction(displayText = text, sources = emptyList())
        }

        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')

        for (index in lines.indices.reversed()) {
            if (!sourcesHeaderRegex.matches(lines[index].trim())) continue

            val sources = parseSources(lines.drop(index + 1))
            if (sources.isEmpty()) continue

            return WebMarkdownSourcesExtraction(
                displayText = lines.take(index).joinToString("\n").trimEnd(),
                sources = sources
            )
        }

        val citationExtraction = extractTrailingCitations(lines)
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

    private fun extractTrailingCitations(lines: List<String>): WebMarkdownSourcesExtraction? {
        var endExclusive = lines.size
        while (endExclusive > 0 && lines[endExclusive - 1].isBlank()) {
            endExclusive--
        }
        if (endExclusive == 0) return null

        var start = endExclusive
        val citationLines = mutableListOf<String>()
        while (start > 0) {
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

        val displayText = removeInlineFootnoteMarkers(
            text = lines.take(start).joinToString("\n").trimEnd(),
            citationLines = citationLines
        )
        return WebMarkdownSourcesExtraction(
            displayText = displayText,
            sources = sources
        )
    }

    private fun isCitationLine(line: String): Boolean {
        return numberedCitationRegex.matches(line) ||
            footnoteCitationRegex.matches(line) ||
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
                footnoteCitationRegex.matches(line) -> footnoteCitationRegex.matchEntire(line)?.groupValues?.get(3).orEmpty()
                else -> ""
            }.let(::cleanUrl)
            if (url.isBlank() || !seenUrls.add(url)) return@forEach
            val title = numberedCitationRegex.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: footnoteCitationRegex.matchEntire(line)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }
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

    private fun removeInlineFootnoteMarkers(text: String, citationLines: List<String>): String {
        var result = text
        citationLines.forEach { line ->
            val footnoteIndex = footnoteCitationRegex.matchEntire(line)?.groupValues?.get(1) ?: return@forEach
            result = result.replace("[^$footnoteIndex]", "")
        }
        return result
    }

    private fun cleanUrl(raw: String): String {
        return raw.trim().trimEnd(
            '.', ',', ';', ':', ')', ']', '}', '"', '\'',
            '。', '，', '；', '：', '）', '】', '》'
        )
    }
}
