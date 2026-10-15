package com.android.everytalk.data.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖合同的字段名扫描和本地字段改名规则。 */
class SyncContractSanitizerTest {
    @Test fun `合同名称扫描覆盖所有第一批 kind 的真实安全字段`() {
        val values = mapOf(
            "api_configs" to buildJsonObject { put("name", "model"); put("address", "https://models.example/v1"); put("outputLimit", 4096); put("contextLimit", 128000) },
            "selected_models" to buildJsonObject { put("configId", "model-1") },
            "web_search_configs" to buildJsonObject { put("providerId", "tavily") },
            "voice_configs" to buildJsonObject { put("sttEndpoint", "https://stt.example") },
            "voice_llm_platforms" to buildJsonObject { put("platform", "openai") },
            "voice_custom_models" to buildJsonObject { put("platform", "openai"); put("models", "[]") },
            "mcp_servers" to buildJsonObject { put("endpoint", "https://mcp.example"); put("transport", "SSE") },
            "skills" to buildJsonObject { put("contentHash", "abc") },
            "skill_variables" to buildJsonObject { put("name", "region") },
            "computers" to buildJsonObject { put("serverFingerprint", "abc") },
            "computer_workspaces" to buildJsonObject { put("remotePath", "/work") },
            "conversation_settings" to buildJsonObject { put("modelId", "model-1"); put("contextLimit", 128000) },
            "memory_switches" to buildJsonObject { put("memoryEnabled", true); put("autoCaptureEnabled", false) },
            "group_members" to buildJsonObject { put("groupName", "Work"); put("conversationId", "00000000-0000-4000-8000-000000000001") },
            "pinned_items" to buildJsonObject { },
        )
        values.forEach { (_, value) -> SyncContractSanitizer.validate(value) }
        assertFalse(SyncContractSanitizer.isForbiddenFieldName("outputLimit"))
        assertFalse(SyncContractSanitizer.isForbiddenFieldName("contextLimit"))
        assertTrue(SyncContractSanitizer.isForbiddenFieldName("maxTokens"))
        assertTrue(SyncContractSanitizer.isForbiddenFieldName("maxContextTokens"))
    }

    @Test fun `本地限制字段只在传输层改名`() {
        assertEquals("outputLimit", SyncWireFieldNames.fromLocal("maxTokens"))
        assertEquals("contextLimit", SyncWireFieldNames.fromLocal("maxContextTokens"))
        assertEquals("maxTokens", SyncWireFieldNames.toLocal("outputLimit"))
        assertEquals("maxContextTokens", SyncWireFieldNames.toLocal("contextLimit"))
    }
}
