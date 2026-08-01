package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCapabilityCacheTest {

    @Test
    fun `缓存按协议端点和模型隔离并保留原始来源`() {
        val directory = Files.createTempDirectory("model-capability-cache-test").toFile()
        try {
            val cache = ModelCapabilityCache(
                cacheFile = directory.resolve("catalog.json"),
                nowEpochMillis = { 2_000L },
                ttlMillis = 10_000L,
            )
            cache.put(
                listOf(
                    candidate("model-a", "https://first.example/v1", 111_000),
                    candidate("model-a", "https://second.example/v1", 222_000),
                )
            )

            val cached = cache.get(
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                apiAddress = "https://FIRST.example/v1/",
            ).single()

            assertEquals(111_000, cached.contextWindowTokens)
            assertEquals(ModelCapabilitySource.LOCAL_CACHE, cached.source)
            assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, cached.cachedSource)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `过期能力不会从缓存返回`() {
        val directory = Files.createTempDirectory("model-capability-cache-expiry-test").toFile()
        var now = 2_000L
        try {
            val cache = ModelCapabilityCache(
                cacheFile = directory.resolve("catalog.json"),
                nowEpochMillis = { now },
                ttlMillis = 1_000L,
            )
            cache.put(listOf(candidate("model-a", "https://api.example/v1", 128_000)))

            now = 3_001L

            assertEquals(
                emptyList<ModelCapabilityCandidate>(),
                cache.get(ModelParameterProtocol.OPENAI_COMPATIBLE, "https://api.example/v1"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun candidate(modelId: String, endpoint: String, contextTokens: Int) =
        ModelCapabilityCandidate(
            modelId = modelId,
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            endpointIdentity = endpoint,
            contextWindowTokens = contextTokens,
            maxOutputTokens = 8_192,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
            sourceUpdatedAt = 1_000L,
        )
}
