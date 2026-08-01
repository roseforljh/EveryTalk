package com.android.everytalk.data

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.ResolvedModelCapability
import com.android.everytalk.data.DataClass.resolveModelCapability
import com.android.everytalk.data.DataClass.officialModelCapability
import com.android.everytalk.data.DataClass.withUserTokenLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCapabilityResolverTest {

    @Test
    fun `同名模型在不同端点的能力互不污染`() {
        val candidates = listOf(
            ModelCapabilityCandidate(
                modelId = "shared-model",
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                endpointIdentity = "https://first.example/v1",
                contextWindowTokens = 111_000,
                maxOutputTokens = 11_000,
                source = ModelCapabilitySource.LIVE_ENDPOINT,
            ),
            ModelCapabilityCandidate(
                modelId = "shared-model",
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                endpointIdentity = "https://second.example/v1",
                contextWindowTokens = 222_000,
                maxOutputTokens = 22_000,
                source = ModelCapabilitySource.LIVE_ENDPOINT,
            ),
        )

        val resolved = resolveModelCapability(
            modelId = "shared-model",
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "https://first.example/v1/",
            candidates = candidates,
        )

        assertEquals(111_000, resolved.contextWindowTokens)
        assertEquals(11_000, resolved.maxOutputTokens)
    }

    @Test
    fun `输出上限不小于上下文时使用下一有效来源`() {
        val resolved = resolveModelCapability(
            modelId = "example-model",
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "https://api.example/v1",
            candidates = listOf(
                ModelCapabilityCandidate(
                    modelId = "example-model",
                    protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    endpointIdentity = "https://api.example/v1",
                    contextWindowTokens = 10_000,
                    maxOutputTokens = 20_000,
                    source = ModelCapabilitySource.LIVE_ENDPOINT,
                ),
                ModelCapabilityCandidate(
                    modelId = "example-model",
                    protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    maxOutputTokens = 8_000,
                    source = ModelCapabilitySource.COMMUNITY_CATALOG,
                ),
            ),
        )

        assertEquals(8_000, resolved.maxOutputTokens)
        assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, resolved.maxOutputSource)
    }

    @Test
    fun `实时端点值优先于社区目录和家族兜底`() {
        val resolved = resolveModelCapability(
            modelId = "example-model",
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "https://api.example.com/v1/",
            candidates = listOf(
                ModelCapabilityCandidate(
                    modelId = "example-model",
                    protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    contextWindowTokens = 128_000,
                    maxOutputTokens = 8_192,
                    source = ModelCapabilitySource.COMMUNITY_CATALOG,
                ),
                ModelCapabilityCandidate(
                    modelId = "example-model",
                    protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    endpointIdentity = "https://api.example.com/v1",
                    contextWindowTokens = 256_000,
                    maxOutputTokens = 16_384,
                    source = ModelCapabilitySource.LIVE_ENDPOINT,
                ),
                ModelCapabilityCandidate(
                    modelId = "example-model",
                    protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    contextWindowTokens = 64_000,
                    maxOutputTokens = 4_096,
                    source = ModelCapabilitySource.FAMILY_FALLBACK,
                ),
            ),
        )

        assertEquals(256_000, resolved.contextWindowTokens)
        assertEquals(16_384, resolved.maxOutputTokens)
        assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, resolved.contextWindowSource)
        assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, resolved.maxOutputSource)
    }

    @Test
    fun `用户只修改最大输出时保留上下文原始来源`() {
        val liveCapability = ResolvedModelCapability(
            modelId = "example-model",
            endpointIdentity = "https://api.example.com/v1",
            contextWindowTokens = 256_000,
            maxOutputTokens = 16_384,
            contextWindowSource = ModelCapabilitySource.LIVE_ENDPOINT,
            maxOutputSource = ModelCapabilitySource.LIVE_ENDPOINT,
            inputModalities = setOf("text"),
            outputModalities = setOf("text"),
            supportsReasoning = true,
        )
        val config = ApiConfig(
            address = "https://api.example.com/v1",
            key = "secret",
            model = "example-model",
            provider = "provider",
            name = "example-model",
            maxTokens = liveCapability.maxOutputTokens,
            modelParameters = ModelParameters(
                maxContextTokens = liveCapability.contextWindowTokens,
                resolvedCapability = liveCapability,
            ),
        )

        val updated = config.withUserTokenLimits(
            ModelTokenLimits(maxOutputTokens = 8_192, maxContextTokens = 256_000)
        )

        assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, updated.modelParameters.resolvedCapability?.contextWindowSource)
        assertEquals(ModelCapabilitySource.USER_OVERRIDE, updated.modelParameters.resolvedCapability?.maxOutputSource)
        assertEquals(8_192, updated.maxTokens)
    }

    @Test
    fun `GPT 56 官方目录提供当前 token 限制与模态`() {
        val capability = officialModelCapability(
            modelId = "gpt-5.6",
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
        )

        requireNotNull(capability)
        assertEquals(1_050_000, capability.contextWindowTokens)
        assertEquals(128_000, capability.maxOutputTokens)
        assertEquals(setOf("text", "image"), capability.inputModalities)
        assertEquals(setOf("text"), capability.outputModalities)
        assertEquals(ModelCapabilitySource.OFFICIAL_CATALOG, capability.source)
    }

    @Test
    fun `Claude Opus 48官方目录提供当前token限制与模态`() {
        val capability = officialModelCapability(
            modelId = "claude-opus-4-8",
            protocol = ModelParameterProtocol.ANTHROPIC,
        )

        requireNotNull(capability)
        assertEquals(1_000_000, capability.contextWindowTokens)
        assertEquals(128_000, capability.maxOutputTokens)
        assertEquals(setOf("text", "image"), capability.inputModalities)
        assertEquals(setOf("text"), capability.outputModalities)
        assertEquals(ModelCapabilitySource.OFFICIAL_CATALOG, capability.source)
    }
}
