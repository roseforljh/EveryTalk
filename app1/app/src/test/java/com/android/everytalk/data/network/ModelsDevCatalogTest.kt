package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevCatalogTest {

    @Test
    fun `models dev解析限制模态与推理等级`() {
        val index = requireNotNull(parseModelsDevCatalog(sampleCatalog(), 1234L))
        val entry = index.byModelId.getValue("shared-model").first()

        assertEquals(200_000, entry.capability.contextWindowTokens)
        assertEquals(32_000, entry.capability.maxOutputTokens)
        assertEquals("gpt", entry.capability.family)
        assertEquals(setOf("text", "image"), entry.capability.inputModalities)
        assertEquals(setOf("high", "max"), entry.capability.reasoningEfforts)
        assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, entry.capability.source)
        assertEquals(1234L, entry.capability.sourceUpdatedAt)
    }

    @Test
    fun `provider精确匹配优先于同名模型安全合并`() = runBlocking {
        val directory = Files.createTempDirectory("models-dev-provider").toFile()
        try {
            val catalog = ModelsDevCatalog(directory.resolve("catalog.json"), nowEpochMillis = { 10_000L })
            val exact = catalog.findCapabilities(
                modelId = "shared-model",
                providerHint = "openai",
                apiAddress = "https://custom.example/v1",
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                fetchRemote = ::sampleCatalog,
            ).single()

            assertEquals(200_000, exact.contextWindowTokens)
            assertEquals(32_000, exact.maxOutputTokens)

            val ambiguousCatalog = ModelsDevCatalog(
                directory.resolve("ambiguous.json"),
                nowEpochMillis = { 10_000L },
            )
            val ambiguous = ambiguousCatalog.findCapabilities(
                modelId = "shared-model",
                providerHint = "",
                apiAddress = "https://unknown.example/v1",
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                fetchRemote = ::sampleCatalog,
            ).single()

            assertEquals(128_000, ambiguous.contextWindowTokens)
            assertEquals(16_000, ambiguous.maxOutputTokens)
            assertEquals(setOf("text"), ambiguous.inputModalities)
            assertTrue(ambiguous.reasoningEfforts.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `网络失败时允许读取过期磁盘缓存`() = runBlocking {
        val directory = Files.createTempDirectory("models-dev-stale").toFile()
        try {
            val cacheFile = directory.resolve("catalog.json")
            cacheFile.writeText(sampleCatalog(), Charsets.UTF_8)
            cacheFile.setLastModified(1_000L)
            val catalog = ModelsDevCatalog(
                cacheFile = cacheFile,
                nowEpochMillis = { 100_000L },
                ttlMillis = 1_000L,
            )

            val result = catalog.findCapabilities(
                modelId = "shared-model",
                providerHint = "openai",
                apiAddress = "https://api.openai.com/v1",
                protocol = ModelParameterProtocol.CODEX,
            ) { error("网络不可用") }

            assertFalse(result.isEmpty())
            assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, result.single().source)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `损坏缓存与网络同时失败时返回空结果`() = runBlocking {
        val directory = Files.createTempDirectory("models-dev-broken").toFile()
        try {
            val cacheFile = directory.resolve("catalog.json")
            cacheFile.writeText("not-json", Charsets.UTF_8)
            val catalog = ModelsDevCatalog(cacheFile, nowEpochMillis = { 100_000L }, ttlMillis = 1L)

            val result = catalog.findCapabilities(
                modelId = "shared-model",
                providerHint = "",
                apiAddress = "https://unknown.example",
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            ) { error("网络不可用") }

            assertTrue(result.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sampleCatalog(): String =
        """
        {
          "openai": {
            "id": "openai",
            "name": "OpenAI",
            "api": "https://api.openai.com/v1",
            "models": {
              "shared-model": {
                "id": "shared-model",
                "family": "gpt",
                "reasoning": true,
                "reasoning_options": [{"type":"effort","values":["high","max"]}],
                "modalities": {"input":["text","image"],"output":["text"]},
                "limit": {"context":200000,"output":32000}
              }
            }
          },
          "proxy": {
            "id": "proxy",
            "name": "Proxy",
            "api": "https://proxy.example/v1",
            "models": {
              "shared-model": {
                "id": "shared-model",
                "reasoning": false,
                "modalities": {"input":["text"],"output":["text"]},
                "limit": {"context":128000,"output":16000}
              }
            }
          }
        }
        """.trimIndent()
}
