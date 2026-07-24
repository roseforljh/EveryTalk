package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.WebSearchResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object WebSearchToolResultExtractor {
    private val explicitSearchToolNames = setOf(
        "web_search",
        "web_search_exa",
        "firecrawl_search",
        "brave_web_search",
        "tavily_search",
        "serpapi_search",
    )
    private val plainTextUrlRegex = Regex("""https?://[^\s)>]+""")

    fun extract(toolName: String, result: JsonElement): List<WebSearchResult> {
        val normalizedToolName = toolName.trim().lowercase()
        if (!isSearchToolName(normalizedToolName)) return emptyList()

        val structuredResults = extractStructuredResults(result)
        if (structuredResults.isNotEmpty()) {
            return structuredResults
        }
        if (!allowsPlainTextFallback(normalizedToolName)) {
            return emptyList()
        }
        return extractPlainTextUrlsFromJson(result)
    }

    private fun isSearchToolName(normalizedToolName: String): Boolean {
        return normalizedToolName == "search" || allowsPlainTextFallback(normalizedToolName)
    }

    private fun allowsPlainTextFallback(normalizedToolName: String): Boolean {
        return normalizedToolName in explicitSearchToolNames ||
            normalizedToolName.endsWith("_search") ||
            normalizedToolName.contains("web_search") ||
            normalizedToolName.contains("search_web")
    }

    private fun extractStructuredResults(result: JsonElement): List<WebSearchResult> {
        val candidates = when (result) {
            is JsonArray -> listOf(result)
            is JsonObject -> listOfNotNull(
                result["results"]?.asJsonArrayOrNull(),
                result["data"]?.asJsonObjectOrNull()?.get("results")?.asJsonArrayOrNull(),
                result["data"]?.asJsonObjectOrNull()
                    ?.get("webPages")?.asJsonObjectOrNull()
                    ?.get("value")?.asJsonArrayOrNull(),
            )
            else -> emptyList()
        }

        val extracted = mutableListOf<WebSearchResult>()
        candidates.forEach { array ->
            array.forEach { element ->
                val item = element.asJsonObjectOrNull() ?: return@forEach
                val href = cleanUrl(
                    firstString(item, "url", "href", "link")
                )
                if (href.isBlank()) return@forEach
                val title = firstString(item, "title", "name").ifBlank { href }
                val snippet = firstString(item, "snippet", "content", "text", "summary")
                extracted.add(
                    WebSearchResult(
                        index = 0,
                        title = title,
                        href = href,
                        snippet = snippet,
                    )
                )
            }
        }
        return reindexAndDeduplicate(extracted)
    }

    private fun extractPlainTextUrlsFromJson(result: JsonElement): List<WebSearchResult> {
        val texts = mutableListOf<String>()
        collectStringPrimitives(result, texts)
        val results = texts.flatMap { text ->
            extractPlainTextUrls(text)
        }
        return reindexAndDeduplicate(results)
    }

    private fun collectStringPrimitives(element: JsonElement, output: MutableList<String>) {
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.let { output.add(it) }
            is JsonArray -> element.forEach { collectStringPrimitives(it, output) }
            is JsonObject -> element.values.forEach { collectStringPrimitives(it, output) }
        }
    }

    private fun extractPlainTextUrls(text: String): List<WebSearchResult> {
        val results = plainTextUrlRegex.findAll(text).mapNotNull { match ->
            val href = cleanUrl(match.value)
            if (href.isBlank()) return@mapNotNull null
            WebSearchResult(
                index = 0,
                title = href,
                href = href,
                snippet = "",
            )
        }.toList()
        return reindexAndDeduplicate(results)
    }

    private fun reindexAndDeduplicate(results: List<WebSearchResult>): List<WebSearchResult> {
        return results
            .filter { it.href.isNotBlank() }
            .distinctBy { it.href }
            .mapIndexed { index, result -> result.copy(index = index + 1) }
    }

    private fun firstString(item: JsonObject, vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            item[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun cleanUrl(raw: String): String {
        return raw.trim().trimEnd(
            '.', ',', ';', ':', ')', ']', '}', '"', '\'',
            '。', '，', '；', '：', '）', '】', '》'
        )
    }

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
}
