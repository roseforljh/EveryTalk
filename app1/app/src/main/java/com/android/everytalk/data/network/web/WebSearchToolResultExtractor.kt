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

/**
 * 从模型供应商的原生搜索元数据中提取网页来源。
 *
 * 只读取明确的引用、搜索结果和 grounding 字段，不扫描正文 URL，避免普通链接触发来源按钮。
 */
internal object NativeWebSearchResultExtractor {
    private val sourceCollectionKeys = setOf(
        "citations",
        "inline_citations",
        "annotations",
        "sources",
        "references",
        "search_results",
        "searchResults",
        "groundingChunks",
        "grounding_chunks",
    )
    private val sourceItemKeys = listOf(
        "url_citation",
        "web_citation",
        "x_citation",
        "citation",
        "source",
        "web",
    )

    fun extract(event: JsonElement): List<WebSearchResult> {
        val extracted = mutableListOf<WebSearchResult>()
        collectExplicitSources(event, extracted)
        return extracted
            .filter { it.href.isNotBlank() }
            .distinctBy { it.href }
            .mapIndexed { index, result -> result.copy(index = index + 1) }
    }

    private fun collectExplicitSources(
        element: JsonElement,
        output: MutableList<WebSearchResult>,
    ) {
        when (element) {
            is JsonArray -> element.forEach { collectExplicitSources(it, output) }
            is JsonObject -> element.forEach { (key, value) ->
                when {
                    key in sourceCollectionKeys -> collectSourceCollection(value, output)
                    key == "citation" || key == "annotation" -> collectSourceItem(value, output)
                    else -> collectExplicitSources(value, output)
                }
            }
            is JsonPrimitive -> Unit
        }
    }

    private fun collectSourceCollection(
        element: JsonElement,
        output: MutableList<WebSearchResult>,
    ) {
        when (element) {
            is JsonArray -> element.forEach { collectSourceItem(it, output) }
            is JsonObject -> {
                val directResult = sourceResult(element)
                if (directResult != null) {
                    output.add(directResult)
                } else {
                    element.values.forEach { collectSourceItem(it, output) }
                }
            }
            is JsonPrimitive -> sourceResult(element)?.let(output::add)
        }
    }

    private fun collectSourceItem(
        element: JsonElement,
        output: MutableList<WebSearchResult>,
    ) {
        val result = sourceResult(element)
        if (result != null) {
            output.add(result)
        } else if (element is JsonObject) {
            collectExplicitSources(element, output)
        }
    }

    private fun sourceResult(element: JsonElement): WebSearchResult? {
        if (element is JsonPrimitive) {
            val href = cleanNativeSourceUrl(element.contentOrNull.orEmpty())
            return href.takeIf(::isHttpUrl)?.let {
                WebSearchResult(index = 0, title = it, href = it, snippet = "")
            }
        }
        val item = element as? JsonObject ?: return null
        val directHref = cleanNativeSourceUrl(firstNativeString(item, "url", "uri", "href", "link"))
        if (isHttpUrl(directHref)) {
            return WebSearchResult(
                index = 0,
                title = nativeSourceTitle(item, directHref),
                href = directHref,
                snippet = firstNativeString(item, "snippet", "content", "text", "summary"),
            )
        }

        sourceItemKeys.forEach { key ->
            val nested = item[key] ?: return@forEach
            val nestedResult = sourceResult(nested) ?: return@forEach
            val outerTitle = firstNativeString(item, "title", "name", "site_name", "label")
            return if (outerTitle.isBlank() || outerTitle.all(Char::isDigit)) {
                nestedResult
            } else {
                nestedResult.copy(title = outerTitle)
            }
        }
        return null
    }

    private fun nativeSourceTitle(item: JsonObject, href: String): String {
        val title = firstNativeString(item, "title", "name", "site_name", "label")
        return title.takeUnless { it.isBlank() || it.all(Char::isDigit) } ?: href
    }

    private fun firstNativeString(item: JsonObject, vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            (item[key] as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun cleanNativeSourceUrl(raw: String): String {
        return raw.trim().trimEnd(
            '.', ',', ';', ':', ')', ']', '}', '"', '\'',
            '。', '，', '；', '：', '）', '】', '》'
        )
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
    }
}
