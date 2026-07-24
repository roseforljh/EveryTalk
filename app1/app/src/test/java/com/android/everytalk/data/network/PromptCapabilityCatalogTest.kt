package com.android.everytalk.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCapabilityCatalogTest {

    @Test
    fun `catalog exposes unique capability ids and a selection tool`() {
        val ids = PromptCapabilityCatalog.capabilityIds()
        val tool = PromptCapabilityCatalog.selectionToolDefinition()
        val function = tool["function"] as Map<*, *>
        val parameters = function["parameters"] as Map<*, *>

        assertTrue(ids.contains("general-answer"))
        assertTrue(ids.contains("markdown-table"))
        assertTrue(ids.contains("financial-caution"))
        assertEquals("everytalk_select_capabilities", function["name"])
        assertEquals(false, parameters["additionalProperties"])
    }

    @Test
    fun `model selected cards return only validated instructions`() {
        val result = PromptCapabilityCatalog.executeSelection(
            buildJsonObject {
                put("language", "zh")
                put("capabilities", Json.parseToJsonElement("[\"document-analysis\",\"markdown-table\",\"financial-caution\"]"))
            },
        )

        assertTrue(result["ok"]?.toString() == "true")
        val instructions = result["instructions"].toString()
        assertTrue(instructions.contains("markdown-table"))
        assertTrue(instructions.contains("正文结束后留空行"))
        assertTrue(instructions.contains("非官方材料"))
    }

    @Test
    fun `format-only selection gets a safe general task without guessing intent`() {
        val result = PromptCapabilityCatalog.executeSelection(
            buildJsonObject {
                put("capabilities", Json.parseToJsonElement("[\"markdown-table\"]"))
            },
        )

        assertTrue(result["ok"]?.toString() == "true")
        assertTrue(result["selected"].toString().contains("general-answer"))
    }

    @Test
    fun `unknown and conflicting cards are rejected`() {
        val unknown = PromptCapabilityCatalog.executeSelection(
            buildJsonObject {
                put("capabilities", Json.parseToJsonElement("[\"not-registered\"]"))
            },
        )
        val conflicting = PromptCapabilityCatalog.executeSelection(
            buildJsonObject {
                put("capabilities", Json.parseToJsonElement("[\"coding\",\"document-analysis\"]"))
            },
        )

        assertFalse(unknown["ok"]?.toString() == "true")
        assertFalse(conflicting["ok"]?.toString() == "true")
    }

    @Test
    fun `stable catalog does not contain the user's current text`() {
        val catalog = PromptCapabilityCatalog.systemCatalog("zh-CN")

        assertTrue(catalog.contains("markdown-table"))
        assertFalse(catalog.contains("OpenAI最新财报"))
    }
}
