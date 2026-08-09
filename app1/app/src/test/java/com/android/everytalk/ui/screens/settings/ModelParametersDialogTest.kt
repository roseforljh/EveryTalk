package com.android.everytalk.ui.screens.settings

import com.android.everytalk.R
import com.android.everytalk.data.DataClass.CustomModelParameter
import com.android.everytalk.data.DataClass.CustomParameterType
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelParametersDialogTest {
    @Test
    fun `模型能力来源显示为可区分的用户文本`() {
        assertEquals(R.string.model_capability_source_user_override, modelCapabilitySourceLabelRes(ModelCapabilitySource.USER_OVERRIDE))
        assertEquals(R.string.model_capability_source_live_endpoint, modelCapabilitySourceLabelRes(ModelCapabilitySource.LIVE_ENDPOINT))
        assertEquals(R.string.model_capability_source_official_catalog, modelCapabilitySourceLabelRes(ModelCapabilitySource.OFFICIAL_CATALOG))
        assertEquals(R.string.model_capability_source_local_cache, modelCapabilitySourceLabelRes(ModelCapabilitySource.LOCAL_CACHE))
        assertEquals(R.string.model_capability_source_community_catalog, modelCapabilitySourceLabelRes(ModelCapabilitySource.COMMUNITY_CATALOG))
        assertEquals(R.string.model_capability_source_family_fallback, modelCapabilitySourceLabelRes(ModelCapabilitySource.FAMILY_FALLBACK))
        assertEquals(R.string.model_capability_source_conservative_default, modelCapabilitySourceLabelRes(ModelCapabilitySource.CONSERVATIVE_DEFAULT))
    }

    @Test
    fun `token文本参数可解析并校验`() {
        val limits = parseModelTokenLimits("8192", "200000")

        assertEquals(8192, limits.maxOutputTokens)
        assertEquals(200000, limits.maxContextTokens)
    }

    @Test
    fun `各渠道使用各自支持的官方参数`() {
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
            thinkingLevelOptions(ModelParameterProtocol.CODEX),
        )
        assertEquals(
            listOf("none", "low", "medium", "high", "max"),
            thinkingLevelOptions(ModelParameterProtocol.ANTHROPIC),
        )
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high"),
            thinkingLevelOptions(ModelParameterProtocol.GEMINI),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `官方渠道拒绝列表之外的自定义参数`() {
        applyThinkingLevelSelection(
            protocol = ModelParameterProtocol.ANTHROPIC,
            parameters = ModelParameters(),
            selectedValue = "ultra",
        )
    }

    @Test
    fun `已有手动预算在未修改参数时原样保留`() {
        val parameters = ModelParameters(
            reasoningMode = ReasoningMode.BUDGET,
            reasoningEffort = "medium",
            thinkingBudget = 4096,
        )

        assertEquals(
            parameters,
            applyThinkingLevelSelection(
                protocol = ModelParameterProtocol.ANTHROPIC,
                parameters = parameters,
                selectedValue = "medium",
            ),
        )
    }

    @Test
    fun `OpenAI兼容渠道接受自定义值并保留其他参数`() {
        val parameters = ModelParameters(
            customParameters = listOf(
                CustomModelParameter("temperature", "0.7", CustomParameterType.NUMBER),
                CustomModelParameter("reasoning_effort", "low"),
                CustomModelParameter("REASONING_EFFORT", "high"),
            ),
        )

        val updated = applyThinkingLevelSelection(
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            parameters = parameters,
            selectedValue = "ultra",
        )
        val reasoningParameters = updated.customParameters.orEmpty().filter {
            it.name.equals("reasoning_effort", ignoreCase = true)
        }

        assertEquals("ultra", updated.reasoningEffort)
        assertEquals(1, reasoningParameters.size)
        assertEquals("ultra", reasoningParameters.single().value)
        assertTrue(reasoningParameters.single().enabled)
        assertTrue(updated.customParameters.orEmpty().any { it.name == "temperature" })
        assertEquals(listOf("ultra"), updated.customReasoningEfforts)
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh", "max", "ultra"),
            thinkingLevelMenuOptions(ModelParameterProtocol.OPENAI_COMPATIBLE, "ultra"),
        )
    }

    @Test
    fun `OpenAI兼容渠道合并已保存自定义参数并过滤空值和预设值`() {
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh", "max", "ultra", "extreme"),
            thinkingLevelMenuOptions(
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                currentValue = "extreme",
                customValues = listOf(" ultra ", "", "medium", "ultra"),
            ),
        )
    }

    @Test
    fun `Gemini的none参数映射为关闭思考`() {
        val updated = applyThinkingLevelSelection(
            protocol = ModelParameterProtocol.GEMINI,
            parameters = ModelParameters(),
            selectedValue = "none",
        )

        assertEquals(ReasoningMode.DISABLED, updated.reasoningMode)
        assertEquals("none", selectedThinkingLevelValue(ModelParameterProtocol.GEMINI, updated))
    }

    @Test
    fun `自动获取时兼容渠道只在端点明确声明后调整思考参数`() {
        assertEquals(
            "ultra",
            automaticThinkingLevelValue(
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                currentValue = "ultra",
                supportsReasoning = null,
            ),
        )
        assertEquals(
            "medium",
            automaticThinkingLevelValue(
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                currentValue = "ultra",
                supportsReasoning = true,
            ),
        )
        assertEquals(
            "none",
            automaticThinkingLevelValue(
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                currentValue = "ultra",
                supportsReasoning = false,
            ),
        )
    }

    @Test
    fun `模型级推理等级覆盖渠道通用预置并选择有效默认值`() {
        val modelEfforts = setOf("high", "max")

        assertEquals(
            listOf("high", "max"),
            effectiveThinkingLevelOptions(ModelParameterProtocol.OPENAI_COMPATIBLE, modelEfforts),
        )
        assertEquals(
            "high",
            automaticThinkingLevelValue(
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                currentValue = "medium",
                supportsReasoning = true,
                modelEfforts = modelEfforts,
            ),
        )
        val updated = applyThinkingLevelSelection(
            protocol = ModelParameterProtocol.ANTHROPIC,
            parameters = ModelParameters(),
            selectedValue = "max",
            modelEfforts = modelEfforts,
        )
        assertEquals("max", updated.reasoningEffort)
    }
}
