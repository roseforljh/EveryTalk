package com.android.everytalk.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTopBarModelDisplayTest {

    @Test
    fun `默认配置只展示内置模型且自定义配置只展示同组文本图片模型`() {
        val text = com.android.everytalk.data.DataClass.ApiConfig("https://proxy.example/v1", "key", "text-model", "服务", id = "text", name = "文本")
        val image = text.copy(id = "image", name = "图片", model = "image-model", modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE)
        val other = text.copy(id = "other", address = "https://other.example/v1")
        val builtin = com.android.everytalk.data.DataClass.builtinImageModels
        val all = builtin + text + image + other
        assertEquals(builtin, all.modelsInSelectedConfig(builtin.first()))
        assertEquals(listOf(text, image), all.modelsInSelectedConfig(text))
        assertEquals(builtin, all.modelsInSelectedConfig(null))
        assertEquals(emptyList<com.android.everytalk.data.DataClass.ApiConfig>(), emptyList<com.android.everytalk.data.DataClass.ApiConfig>().modelsInSelectedConfig(null))
    }

    @Test
    fun `模型列表初始位置让选中模型处于七项窗口中央`() {
        assertEquals(2, modelSelectionInitialFirstVisibleIndex(modelCount = 10, selectedIndex = 5))
        assertEquals(0, modelSelectionInitialFirstVisibleIndex(modelCount = 10, selectedIndex = 1))
        assertEquals(3, modelSelectionInitialFirstVisibleIndex(modelCount = 10, selectedIndex = 9))
        assertEquals(0, modelSelectionInitialFirstVisibleIndex(modelCount = 7, selectedIndex = 3))
    }

    @Test
    fun `gpt text is black in light theme and white in dark theme`() {
        assertEquals(
            TopBarModelDisplayInfo(label = "GPT", textColor = Color.Black),
            resolveTopBarModelDisplayInfo("gpt-5", isDark = false, otherLabel = "Other")
        )
        assertEquals(
            TopBarModelDisplayInfo(label = "GPT", textColor = Color.White),
            resolveTopBarModelDisplayInfo("gpt-5", isDark = true, otherLabel = "Other")
        )
    }

    @Test
    fun `grok text matches gpt colors`() {
        assertEquals(
            TopBarModelDisplayInfo(label = "Grok", textColor = Color.Black),
            resolveTopBarModelDisplayInfo("grok-4", isDark = false, otherLabel = "Other")
        )

        val darkInfo = resolveTopBarModelDisplayInfo("grok-4", isDark = true, otherLabel = "Other")

        assertEquals(TopBarModelDisplayInfo(label = "Grok", textColor = Color.White), darkInfo)
    }

    @Test
    fun `grok and gpt use same theme colors`() {
        assertEquals(
            resolveTopBarModelDisplayInfo("gpt-5", isDark = false, otherLabel = "Other").textColor,
            resolveTopBarModelDisplayInfo("grok-4", isDark = false, otherLabel = "Other").textColor
        )
        assertEquals(
            resolveTopBarModelDisplayInfo("gpt-5", isDark = true, otherLabel = "Other").textColor,
            resolveTopBarModelDisplayInfo("grok-4", isDark = true, otherLabel = "Other").textColor
        )
    }

    @Test
    fun `常用模型保留可读的品牌原色并纠正旧分组色`() {
        // 这些原色在白底上已足够清晰，不应被换成通用蓝、绿或紫。
        val brandColors = mapOf(
            "qwen3" to Color(0xFF615CED),
            "glm-4" to Color(0xFF3859FF),
            "hunyuan-t1" to Color(0xFF0053E0),
            "llama-4" to Color(0xFF1D65C1),
            "doubao-1.5" to Color(0xFF1E37FC),
        )
        brandColors.forEach { (model, expected) ->
            assertEquals(model, expected, resolveTopBarModelDisplayInfo(model, false, "其他").textColor)
        }
        // DeepSeek 原色在白底的对比度约 4.33，需要略微加深，但仍应保留品牌蓝。
        val deepseek = resolveTopBarModelDisplayInfo("deepseek-v3", false, "其他").textColor
        assertTrue(deepseek.blue > deepseek.green && deepseek.green > deepseek.red)
        assertReadable(deepseek, false, "DeepSeek")
        val mistral = resolveTopBarModelDisplayInfo("mistral-large", false, "其他").textColor
        assertTrue("Mistral 应显示品牌橙色", mistral.red > mistral.green && mistral.green > mistral.blue)
        val kolors = resolveTopBarModelDisplayInfo("kolors", true, "其他").textColor
        assertEquals("可图的绿色原色在深色主题可直接使用", Color(0xFF83FF63), kolors)
    }

    @Test
    fun `Gemini 渐变遵循本地图标色相且整个渐变在两种主题均清晰`() {
        listOf(false, true).forEach { isDark ->
            val info = resolveTopBarModelDisplayInfo("gemini-3-pro", isDark, "其他")
            assertEquals(4, info.gradientColors.size)
            val (blue, red, yellow, green) = info.gradientColors
            assertTrue(blue.blue > blue.red && blue.blue > blue.green)
            assertTrue(red.red > red.green && red.red > red.blue)
            assertTrue(yellow.red > yellow.blue && yellow.green > yellow.blue)
            assertTrue(green.green > green.red && green.green > green.blue)
            // 检查渐变中间的实际混合色，避免端点合格但文字中间发虚。
            info.gradientColors.zipWithNext().forEach { (start, end) ->
                (0..100).forEach { step ->
                    // Brush 的渐变在 sRGB 通道间混合，不能用 Color.lerp 的 Oklab 混合代替。
                    val fraction = step / 100f
                    val mixed = Color(
                        red = start.red + (end.red - start.red) * fraction,
                        green = start.green + (end.green - start.green) * fraction,
                        blue = start.blue + (end.blue - start.blue) * fraction,
                    )
                    assertReadable(mixed, isDark, "Gemini 渐变")
                }
            }
        }
    }

    @Test
    fun `未知模型显示自身名称且空名称保留本地化兜底`() {
        assertEquals(
            "unknown-model",
            resolveTopBarModelDisplayInfo(
                selectedConfigName = "unknown-model",
                isDark = false,
                otherLabel = "其他",
            ).label,
        )
        assertEquals("my-model-v2", resolveTopBarModelDisplayInfo("我的配置", false, "其他", "vendor/my-model-v2").label)
        assertEquals("其他", resolveTopBarModelDisplayInfo("  ", false, "其他").label)
    }

    @Test
    fun `真实模型ID优先于自定义配置昵称`() {
        assertEquals("Qwen", resolveTopBarModelDisplayInfo("日常聊天", false, "其他", "Qwen/Qwen3-235B-A22B").label)
        assertEquals("Claude", resolveTopBarModelDisplayInfo("GPT备用", false, "其他", "anthropic/claude-sonnet-4-6").label)
        assertEquals("Gemini", resolveTopBarModelDisplayInfo("我的模型", false, "其他", "google/gemini-3-pro-preview").label)
    }

    @Test
    fun `常见文本图片视频模型及中文别名均可识别`() {
        val cases = mapOf(
            "Tongyi-MAI/Z-Image-Turbo" to "Z-Image", "Kwai-Kolors/Kolors" to "Kolors",
            "doubao-seedream-4-0" to "Seedream", "doubao-seedance-1-0" to "Seedance",
            "zhipu/cogview-4" to "CogView", "zhipu/cogvideoX" to "CogVideo",
            "google/gemma-3-27b-it" to "Gemma", "google/imagen-4" to "Imagen", "google/veo-3.1" to "Veo",
            "openai/sora-2" to "Sora", "dall-e-3" to "DALL-E", "black-forest-labs/FLUX.1-dev" to "FLUX",
            "stabilityai/stable-diffusion-3.5" to "Stable Diffusion", "sdxl-turbo" to "Stable Diffusion",
            "recraft-v3" to "Recraft", "ideogram-v3" to "Ideogram", "wan-ai/wan2.2" to "Wan",
            "kling-v2" to "Kling", "minimax/hailuo-02" to "Hailuo", "x-ai/grok-4" to "Grok",
            "openai/o3-mini" to "GPT", "o1-preview" to "GPT", "o4-mini" to "GPT",
            "深度求索" to "DeepSeek", "moonshotai/kimi-k2" to "Kimi", "MiniMax-M2" to "MiniMax",
            "智谱清言 GLM-4" to "GLM", "QwQ-32B" to "Qwen", "通义千问" to "Qwen", "Qwen/QVQ-72B" to "Qwen",
            "doubao-seed-1.6" to "Doubao", "mistralai/devstral-small" to "Mistral", "codestral-latest" to "Mistral",
            "meta-llama/llama-4-maverick" to "Llama", "tencent/hunyuan-t1" to "Hunyuan", "文心 ERNIE-4.5" to "ERNIE",
            "stepfun-ai/step3" to "Step", "01-ai/yi-lightning" to "Yi", "internlm/internvl3" to "InternLM",
            "baichuan4" to "Baichuan", "cohere/command-a-03-2025" to "Cohere", "ai21/jamba-large" to "Jamba",
            "amazon.nova-pro-v1" to "Nova", "amazon.titan-text-premier" to "Titan", "microsoft/phi-4" to "Phi",
            "allenai/OLMo-2" to "OLMo", "tiiuae/falcon-180B" to "Falcon", "databricks/dbrx-instruct" to "DBRX",
            "xiaomi/mimo-v2-flash" to "MiMo", "meituan/longcat-flash-chat" to "LongCat", "arcee-ai/trinity-large" to "Trinity",
            "nousresearch/hermes-4" to "Hermes", "deepcogito/cogito-v2" to "Cogito", "perplexity/sonar-pro" to "Sonar",
        )
        cases.forEach { (modelId, expected) ->
            assertEquals(modelId, expected, resolveTopBarModelDisplayInfo("我的配置", false, "其他", modelId).label)
            listOf(false, true).forEach { isDark ->
                val info = resolveTopBarModelDisplayInfo("我的配置", isDark, "其他", modelId)
                assertReadable(info.textColor, isDark, modelId)
                assertTrue("已收录模型不能仍用默认灰色：$modelId",
                    info.textColor != resolveTopBarModelDisplayInfo("unknown-model", isDark, "其他").textColor)
            }
        }
        // 短模型名只在词边界匹配，避免把普通自定义名称误认为 Yi、Phi、Nova 或 Step。
        listOf("myimage", "philosophy", "innovation", "footsteps").forEach { modelId ->
            assertEquals(modelId, resolveTopBarModelDisplayInfo(modelId, false, "其他").label)
        }
    }

    private fun assertReadable(color: Color, isDark: Boolean, model: String) {
        val background = if (isDark) Color(0xFF303030) else Color.White
        val foregroundLuminance = color.luminance()
        val backgroundLuminance = background.luminance()
        val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
        assertTrue("$model 在 isDark=$isDark 时对比度不足：$contrast", contrast >= 4.5f)
    }
}
