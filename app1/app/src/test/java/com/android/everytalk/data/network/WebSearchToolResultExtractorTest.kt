package com.android.everytalk.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolResultExtractorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `extracts built in web search results`() {
        val result = json.parseToJsonElement(
            """
            {
              "ok": true,
              "results": [
                {"title": "Title", "url": "https://example.com/a", "snippet": "Snippet"}
              ]
            }
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("web_search", result)

        assertEquals(1, extracted.size)
        assertEquals(1, extracted[0].index)
        assertEquals("Title", extracted[0].title)
        assertEquals("https://example.com/a", extracted[0].href)
        assertEquals("Snippet", extracted[0].snippet)
    }

    @Test
    fun `extracts structured search tool named search`() {
        val result = json.parseToJsonElement(
            """
            {"results":[{"title":"Title","url":"https://example.com/a","snippet":"Snippet"}]}
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("search", result)

        assertEquals(listOf("https://example.com/a"), extracted.map { it.href })
    }

    @Test
    fun `does not extract plain text urls for generic search tool`() {
        val extracted = WebSearchToolResultExtractor.extract(
            "search",
            JsonPrimitive("https://example.com/a")
        )

        assertTrue(extracted.isEmpty())
    }

    @Test
    fun `extracts href and text fields from exa results`() {
        val result = json.parseToJsonElement(
            """
            {"results":[{"title":"Exa","href":"https://example.com/exa","text":"Body"}]}
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("web_search_exa", result)

        assertEquals("Exa", extracted[0].title)
        assertEquals("https://example.com/exa", extracted[0].href)
        assertEquals("Body", extracted[0].snippet)
    }

    @Test
    fun `extracts plain text urls for firecrawl search`() {
        val extracted = WebSearchToolResultExtractor.extract(
            "firecrawl_search",
            JsonPrimitive("Source: https://example.com/a and https://example.com/b")
        )

        assertEquals(listOf("https://example.com/a", "https://example.com/b"), extracted.map { it.href })
        assertEquals(listOf(1, 2), extracted.map { it.index })
    }

    @Test
    fun `extracts plain text urls from json string primitives without json syntax`() {
        val result = json.parseToJsonElement(
            """
            {"answer":"Source: https://example.com/a","other":"x"}
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("firecrawl_search", result)

        assertEquals(listOf("https://example.com/a"), extracted.map { it.href })
    }

    @Test
    fun `trims chinese punctuation from urls`() {
        val extracted = WebSearchToolResultExtractor.extract(
            "firecrawl_search",
            JsonPrimitive("https://example.com。 https://example.com/news，")
        )

        assertEquals(listOf("https://example.com", "https://example.com/news"), extracted.map { it.href })
    }

    @Test
    fun `extracts bocha web pages structure`() {
        val result = json.parseToJsonElement(
            """
            {
              "data": {
                "webPages": {
                  "value": [
                    {"name": "Bocha", "url": "https://example.com/bocha", "snippet": "Snippet"}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("web_search", result)

        assertEquals("Bocha", extracted[0].title)
        assertEquals("https://example.com/bocha", extracted[0].href)
    }

    @Test
    fun `deduplicates duplicate urls and reindexes`() {
        val result = json.parseToJsonElement(
            """
            {"results":[
              {"title":"A","url":"https://example.com/a"},
              {"title":"A2","url":"https://example.com/a"},
              {"title":"B","url":"https://example.com/b"}
            ]}
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("web_search", result)

        assertEquals(listOf("https://example.com/a", "https://example.com/b"), extracted.map { it.href })
        assertEquals(listOf(1, 2), extracted.map { it.index })
        assertEquals("A", extracted[0].title)
    }

    @Test
    fun `does not extract webfetch results`() {
        val result = json.parseToJsonElement(
            """
            {"results":[{"title":"Title","url":"https://example.com/a"}]}
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("webfetch", result)

        assertTrue(extracted.isEmpty())
    }

    @Test
    fun `skips empty urls`() {
        val result = json.parseToJsonElement(
            """
            {"results":[{"title":"Empty","url":""}]}
            """.trimIndent()
        )

        val extracted = WebSearchToolResultExtractor.extract("web_search", result)

        assertTrue(extracted.isEmpty())
    }
}
