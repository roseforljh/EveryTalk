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

        return WebMarkdownSourcesExtraction(displayText = text, sources = emptyList())
    }

    private fun parseSources(lines: List<String>): List<WebSearchResult> {
        val sources = mutableListOf<WebSearchResult>()
        val seenUrls = mutableSetOf<String>()

        lines.forEach { line ->
            val linkMatch = markdownLinkRegex.find(line)
            if (linkMatch != null) {
                val title = linkMatch.groupValues[1].trim().ifBlank { linkMatch.groupValues[2] }
                val url = linkMatch.groupValues[2].trim().trimEnd('.', ',', ';')
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

            val plainUrl = plainUrlRegex.find(line)?.value?.trimEnd('.', ',', ';')
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
}
