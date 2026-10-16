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

    @Test fun `敏感字段只允许完整合法信封且不能用伪造信封绕过扫描`() {
        val envelope = syncJson.encodeToJsonElement(SyncEncryptedEnvelope.serializer(),
            SyncCrypto.encrypt(ByteArray(32), "value".toByteArray(), "aad".toByteArray(), 1, ByteArray(12)))
        SyncContractSanitizer.validate(buildJsonObject { put("apiKey", envelope) })
        assertTrue(runCatching { SyncContractSanitizer.validate(buildJsonObject { put("apiKey", "plain") }) }.isFailure)
        val invalid = kotlinx.serialization.json.JsonObject(envelope.let { it as kotlinx.serialization.json.JsonObject } +
            ("nonce" to kotlinx.serialization.json.JsonPrimitive("bad")))
        assertFalse(SyncContractSanitizer.isValidEnvelope(invalid))
        assertTrue(runCatching { SyncContractSanitizer.validate(buildJsonObject { put("headers", invalid) }) }.isFailure)
        assertEquals("owner/repo", syncSafeSkillRepository("owner/repo"))
        assertEquals(null, syncSafeSkillRepository("/data/private/skill"))
        assertEquals(null, syncSafeSkillRepository("owner/.."))
    }

    @Test fun `非法本地设置ID映射为稳定UUID且敏感地址不上传`() {
        val first = syncStableSettingKey("mcp_servers", "mcp-cloudflare")
        assertEquals(first, syncStableSettingKey("mcp_servers", "mcp-cloudflare"))
        assertTrue(first.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals("00000000-0000-4000-8000-000000000001", syncStableSettingKey("mcp_servers", "00000000-0000-4000-8000-000000000001"))
        assertEquals("https://mcp.example/path", syncSafeEndpoint("https://mcp.example/path"))
        assertEquals(null, syncSafeEndpoint("https://mcp.example/path?exaApiKey=abc"))
        assertEquals(null, syncSafeEndpoint("https://user:password@mcp.example/path"))
        assertEquals(null, syncSafeEndpoint("https://mcp.example/path?api%4Bey=local"))
        assertEquals(null, syncSafeEndpoint("https://mcp.example/path?X-Signature=local"))
        assertEquals(null, syncSafeEndpoint("https://mcp.example/path?Authorization=local"))
        assertEquals(null, syncSafeEndpoint("file:///private/device/path"))
        assertEquals(null, syncSafeEndpoint("https://mcp.example/path?bad%=local"))
        assertEquals("https://mcp.example/path?tools=search", syncSafeEndpoint("https://mcp.example/path?tools=search"))
        assertEquals(null, syncSafeEndpoint("https://mcp.example/fc-0123456789abcdef0123456789abcdef/v2/mcp"))
        assertEquals("https://mcp.example/path", syncSafeEndpoint("https://mcp.example/path#local-only"))
    }

    @Test fun `配置比较忽略对象顺序和等价数字但保留真实差异`() {
        val original = syncJson.parseToJsonElement("""{"temperature":0.0,"params":{"contextLimit":128000},"models":["a","b"]}""")
        val reordered = syncJson.parseToJsonElement("""{"models":["a","b"],"params":{"contextLimit":128000.0},"temperature":0}""")
        assertTrue(syncSettingValuesEqual(original, reordered))
        assertFalse(syncSettingValuesEqual(original, syncJson.parseToJsonElement(original.toString().replace("0.0", "0.7"))))
        assertFalse(syncSettingValuesEqual(original, syncJson.parseToJsonElement(original.toString().replace("[\"a\",\"b\"]", "[\"b\",\"a\"]"))))
    }
}
