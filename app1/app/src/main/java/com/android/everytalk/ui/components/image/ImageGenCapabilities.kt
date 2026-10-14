package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*
import com.android.everytalk.data.DataClass.ImageRatio

/**
 * 图像生成“模型家族”能力注册表（数据驱动，不依赖具体单一模型名）。
 *
 * 目标：
 * - 通过关键字特征归一化模型家族（Gemini/Seedream/Qwen/Kolors/Unknown）
 * - 提供各家族支持的比例候选（Aspect Ratio）
 * - 提供（仅 Seedream 家族）2K/4K 清晰度下“比例 → 精确尺寸”映射
 * - Qwen/Kolors 提供可用分辨率/比例集；Gemini 仅使用 aspect_ratio，不下发 image_size
 *
 * 注意：
 * - 本文件仅提供纯数据与检测逻辑，方便 UI/请求层按家族动态展示或生成参数；
 * - 尚未改动现有 UI 代码，后续可在 ImageRatioSelector / InputArea / Screen 等处按需接线。
 */
object ImageGenCapabilities {

    // -------- 家族定义与归一化 --------

    enum class ModelFamily {
        GEMINI,    // Google Gemini image family（使用 aspect_ratio）
        SEEDREAM,  // 火山系 即梦 Seedream 家族（支持 2K/4K，需 size）
        QWEN,      // 通义千问图像家族（固定分辨率集合，通常用 image_size）
        KOLORS,    // Kolors/Kwai/SiliconFlow 默认图像家族（固定分辨率集合）
        MODAL_Z_IMAGE, // Modal Z-Image-Turbo 家族
        GPT_IMAGE, // OpenAI GPT-Image-2 家族（支持 quality: low/medium/high/auto，灵活尺寸）
        UNKNOWN
    }

    /**
     * 质量档（Seedream 家族和 Gemini 3 Pro Image 使用）
     */
    enum class QualityTier(val label: String) {
        HD("HD"),
        Q1K("1K"), // Seedream 默认/兼容
        Q2K("2K"),
        Q4K("4K")
    }

    /**
     * GPT-Image-2 质量档位
     */
    enum class GptImageQuality(val apiValue: String, val displayName: String) {
        AUTO("auto", "Auto"),
        LOW("low", "Low"),
        MEDIUM("medium", "Medium"),
        HIGH("high", "High")
    }

    /**
     * Gemini 图像尺寸档位（仅 gemini-3-pro-image-preview 支持 2K/4K）
     */
    enum class GeminiImageSizeTier(val apiValue: String, val displayName: String) {
        SIZE_2K("2K", "2K"),
        SIZE_4K("4K", "4K")
    }

    /**
     * 比例选项（以 "W:H" 字符串表达，例如 "16:9"）
     */
    data class AspectRatioOption(
        val ratio: String,      // 例 "16:9"
        val displayName: String // UI 显示文案（与 ratio 一般一致，可兼容本地化）
    )

    /**
     * 精确尺寸（像素）
     */
    data class SizeOption(
        val width: Int,
        val height: Int,
        val label: String // 例 "1024x1024"
    )

    /**
     * 家族能力汇总：
     * - ratios: 家族可展示的比例枚举；为空表示"沿用应用内既有默认比例集"
     * - supportsQuality: 是否显示清晰度（2K/4K），Seedream = true
     * - supportsGeminiImageSize: 是否显示 Gemini 专用尺寸档位（1K/2K/4K）
     * - maxInputImages: 最大输入图片数量（Gemini 3 Pro Image = 14，其他默认 1 或按需）
     */
    data class FamilyCapabilities(
        val ratios: List<AspectRatioOption>,
        val supportsQuality: Boolean,
        // 是否显示 Gemini 专用尺寸档位（2K/4K），如果为 false 则 UI 隐藏选择器
        val supportsGeminiImageSize: Boolean = false,
        val maxInputImages: Int = 1
    )

    /**
     * 由三处信息进行家族归一化判断：
     * - modelName（模型标识）
     * - provider（平台/渠道标识）
     * - apiAddress（可用于识别 volces、google 等域特征）
     *
     * 规则（大小写不敏感，命中任一关键词即归属对应家族）：
     * - Gemini 家族: "gemini", "google"
     * - Seedream 家族: "doubao", "seedream", "volces"
     * - Qwen 家族: "qwen", "qwen-image", "qwen-vl"
     * - Kolors 家族: "kolors", "kwai", "siliconflow"
     * - Modal Z-Image: "z-image-turbo"
     */
    @JvmStatic
    fun detectFamily(
        modelName: String?,
        provider: String?,
        apiAddress: String?
    ): ModelFamily {
        // 模型名优先于平台和地址；同一个平台代理可能同时提供不同家族的模型。
        for (value in listOfNotNull(modelName, provider, apiAddress)) {
            val name = value.lowercase()
            val family = when {
                name.contains("z-image-turbo") || name.contains("z_image_turbo") -> ModelFamily.MODAL_Z_IMAGE
                name.contains("gemini") || name.contains("google") -> ModelFamily.GEMINI
                name.contains("doubao") || name.contains("seedream") || name.contains("volces") -> ModelFamily.SEEDREAM
                name.contains("qwen") -> ModelFamily.QWEN
                name.contains("kolors") || name.contains("kwai") || name.contains("siliconflow") -> ModelFamily.KOLORS
                name.contains("gpt-image") || name.contains("gpt_image") || name.contains("chatgpt-image") -> ModelFamily.GPT_IMAGE
                else -> ModelFamily.UNKNOWN
            }
            if (family != ModelFamily.UNKNOWN) return family
        }
        return ModelFamily.UNKNOWN
    }

    // -------- 家族 → 比例候选 --------

    private val RATIOS_GEMINI: List<AspectRatioOption> = listOf(
        ar("1:1"),
        ar("2:3"),
        ar("3:2"),
        ar("3:4"),
        ar("4:3"),
        ar("4:5"),
        ar("5:4"),
        ar("9:16"),
        ar("16:9"),
        ar("21:9")
    )

    private val RATIOS_SEEDREAM: List<AspectRatioOption> = listOf(
        ar("1:1"),
        ar("4:3"),
        ar("3:4"),
        ar("16:9"),
        ar("9:16"),
        ar("3:2"),
        ar("2:3"),
        ar("21:9")
    )

    private val RATIOS_QWEN: List<AspectRatioOption> = listOf(
        ar("1:1"),
        ar("16:9"),
        ar("9:16"),
        ar("4:3"),
        ar("3:4"),
        ar("3:2"),
        ar("2:3")
    )

    // Kolors（参考用户提供分辨率对应的常见比例集合，未提供 16:9）
    private val RATIOS_KOLORS: List<AspectRatioOption> = listOf(
        ar("1:1"),
        ar("3:4"),
        ar("1:2"),
        ar("9:16")
    )

    // Modal Z-Image-Turbo (合并了分辨率档位与比例)
    // 注意：displayName 必须包含 "2K"/"HD" 和比例，以便后端解析
    private val RATIOS_MODAL_Z_IMAGE: List<AspectRatioOption> = listOf(
        ar("2K 1:1"),
        ar("2K 16:9"),
        ar("2K 9:16"),
        ar("2K 4:3"),
        ar("HD 1:1"),
        ar("HD 16:9"),
        ar("HD 9:16")
    )

    // GPT-Image-2
    private val RATIOS_GPT_IMAGE: List<AspectRatioOption> = listOf(
        ar("1:1"),
        ar("3:2"),
        ar("2:3"),
        ar("16:9"),
        ar("9:16"),
        ar("4:3"),
        ar("3:4")
    )

    // UNKNOWN：保留空表示“走应用现有默认比例集”
    private val RATIOS_UNKNOWN_USE_DEFAULT: List<AspectRatioOption> = emptyList()

    /**
     * 获取家族能力（比例候选 + 是否支持清晰度 + Gemini 尺寸档位 + 最大输入图片数）
     */
    @JvmStatic
    fun getCapabilities(family: ModelFamily): FamilyCapabilities = when (family) {
        ModelFamily.GEMINI -> FamilyCapabilities(
            ratios = RATIOS_GEMINI,
            supportsQuality = false,
            supportsGeminiImageSize = true, // Gemini 3 Pro Image 支持 1K/2K/4K
            maxInputImages = 14 // Gemini 3 Pro Image 最多支持 14 张图片
        )
        ModelFamily.SEEDREAM -> FamilyCapabilities(
            ratios = RATIOS_SEEDREAM,
            supportsQuality = true,
            supportsGeminiImageSize = false,
            maxInputImages = 1
        )
        ModelFamily.QWEN -> FamilyCapabilities(
            ratios = RATIOS_QWEN,
            supportsQuality = false,
            supportsGeminiImageSize = false,
            maxInputImages = 1
        )
        ModelFamily.KOLORS -> FamilyCapabilities(
            ratios = RATIOS_KOLORS,
            supportsQuality = false,
            supportsGeminiImageSize = false,
            maxInputImages = 1
        )
        ModelFamily.MODAL_Z_IMAGE -> FamilyCapabilities(
            ratios = RATIOS_MODAL_Z_IMAGE,
            supportsQuality = false, // 不需要二级分组，直接在列表中展示
            supportsGeminiImageSize = false,
            maxInputImages = 0 // Modal Z-Image 不支持图像编辑
        )
        ModelFamily.GPT_IMAGE -> FamilyCapabilities(
            ratios = RATIOS_GPT_IMAGE,
            supportsQuality = false,
            supportsGeminiImageSize = false,
            maxInputImages = 1
        )
        ModelFamily.UNKNOWN -> FamilyCapabilities(
            ratios = RATIOS_UNKNOWN_USE_DEFAULT,
            supportsQuality = false,
            supportsGeminiImageSize = false,
            maxInputImages = 1
        )
    }

    /**
     * 判断模型是否为 Gemini 3 Pro Image（支持 2K/4K 和最多 14 张图片）
     */
    @JvmStatic
    fun isGemini3ProImage(modelName: String?): Boolean {
        if (modelName == null) return false
        val lower = modelName.lowercase()
        return lower.contains("gemini-3-pro-image") || lower.contains("gemini-3-pro-image-preview")
    }

    /**
     * 判断模型是否为 Gemini 2.5 Flash Image（仅支持 1K）
     */
    @JvmStatic
    fun isGemini25FlashImage(modelName: String?): Boolean {
        if (modelName == null) return false
        val lower = modelName.lowercase()
        return lower.contains("gemini-2.5-flash-image") || lower.contains("gemini-2-5-flash-image")
    }

    /**
     * 获取 Gemini 模型支持的尺寸档位列表
     * - gemini-3-pro-image-preview: 支持 1K/2K/4K
     * - gemini-2.5-flash-image: 仅支持 1K
     */
    @JvmStatic
    fun getGeminiSupportedSizes(modelName: String?): List<GeminiImageSizeTier> {
        return when {
            isGemini3ProImage(modelName) -> listOf(
                GeminiImageSizeTier.SIZE_2K,
                GeminiImageSizeTier.SIZE_4K
            )
            // 其他模型不支持尺寸选择（2.5 Flash 仅 1K，无 UI）
            else -> emptyList()
        }
    }

    /**
     * 获取 Gemini 模型的最大输入图片数量
     * - gemini-3-pro-image-preview: 最多 14 张
     * - gemini-2.5-flash-image: 最多 3 张
     */
    @JvmStatic
    fun getGeminiMaxInputImages(modelName: String?): Int {
        return when {
            isGemini3ProImage(modelName) -> 14
            isGemini25FlashImage(modelName) -> 3
            else -> 1
        }
    }

    // -------- 尺寸映射（仅 Seedream 家族使用）--------

    /**
     * Seedream 2K / 4K 清晰度下，不同比例对应的推荐精确尺寸。
     * 来源（官方文档）：
     * 1:1   -> 2048x2048
     * 4:3   -> 2304x1728
     * 3:4   -> 1728x2304
     * 16:9  -> 2560x1440
     * 9:16  -> 1440x2560
     * 3:2   -> 2496x1664
     * 2:3   -> 1664x2496
     * 21:9  -> 3024x1296
     */
    /**
     * Seedream 2K / 4K 清晰度下，不同比例对应的推荐精确尺寸。
     * 来源（官方文档）：
     * 1:1   -> 2048x2048
     * 4:3   -> 2304x1728
     * 3:4   -> 1728x2304
     * 16:9  -> 2560x1440
     * 9:16  -> 1440x2560
     * 3:2   -> 2496x1664
     * 2:3   -> 1664x2496
     * 21:9  -> 3024x1296
     */
    private val SEEDREAM_SIZES_2K: Map<String, List<SizeOption>> = mapOf(
        "1:1" to listOf(sz(2048, 2048)),
        "4:3" to listOf(sz(2304, 1728)),
        "3:4" to listOf(sz(1728, 2304)),
        "16:9" to listOf(sz(2560, 1440)),
        "9:16" to listOf(sz(1440, 2560)),
        "3:2" to listOf(sz(2496, 1664)),
        "2:3" to listOf(sz(1664, 2496)),
        "21:9" to listOf(sz(3024, 1296))
    )

    // 4K 推荐值 (基于 2K 推荐值翻倍，总像素需在 [921600, 16777216] 范围内)
    private val SEEDREAM_SIZES_4K: Map<String, List<SizeOption>> = mapOf(
        "1:1" to listOf(sz(4096, 4096)),
        "4:3" to listOf(sz(4608, 3456)), // 按2倍长宽计算，而非1.414倍
        "3:4" to listOf(sz(3456, 4608)),
        "16:9" to listOf(sz(5120, 2880)),
        "9:16" to listOf(sz(2880, 5120)),
        "3:2" to listOf(sz(4992, 3328)),
        "2:3" to listOf(sz(3328, 4992)),
        "21:9" to listOf(sz(6048, 2592))
    )

    /**
     * 返回 Seedream 指定清晰度下，某比例的精确尺寸列表（可能为空）。
     */
    @JvmStatic
    fun getSeedreamSizes(
        quality: QualityTier,
        aspectRatio: String
    ): List<SizeOption> {
        val key = aspectRatio.trim()
        return when (quality) {
            QualityTier.Q2K -> SEEDREAM_SIZES_2K[key] ?: emptyList()
            QualityTier.Q4K -> SEEDREAM_SIZES_4K[key] ?: emptyList()
            QualityTier.HD -> emptyList() // Seedream 不使用 HD
            QualityTier.Q1K -> emptyList() // Seedream 不使用 1K
        }
    }

    /** 从已保存的清晰度或像素尺寸识别 Seedream 档位，供弹窗显示和请求换算共用。 */
    fun getSeedreamQuality(size: String?): QualityTier {
        val normalized = size?.trim()?.lowercase().orEmpty()
        val dimensions = normalized.split("x")
        val pixels = if (dimensions.size == 2) {
            (dimensions[0].toIntOrNull()?.coerceAtLeast(0)?.toLong() ?: 0L) *
                (dimensions[1].toIntOrNull()?.coerceAtLeast(0)?.toLong() ?: 0L)
        } else 0L
        return if (normalized == "4k" || pixels > 2048L * 2048L) QualityTier.Q4K else QualityTier.Q2K
    }

    /**
     * Qwen 家族的分辨率集合（用户提供示例中列出的是各比例对应分辨率的一组集合）。
     * 这里将各分辨率枚举为 size 候选，供需要时（非必须）按比例筛选或直接展示。
     *
     * Qwen-Image 例（来自用户）：
     * - 1328x1328 (1:1)
     * - 1664x928  (16:9)
     * - 928x1664  (9:16)
     * - 1472x1140 (4:3)
     * - 1140x1472 (3:4)
     * - 1584x1056 (3:2)
     * - 1056x1584 (2:3)
     */
    private val QWEN_SIZES_BY_RATIO: Map<String, SizeOption> = mapOf(
        "1:1" to sz(1328, 1328),
        "16:9" to sz(1664, 928),
        "9:16" to sz(928, 1664),
        "4:3" to sz(1472, 1140),
        "3:4" to sz(1140, 1472),
        "3:2" to sz(1584, 1056),
        "2:3" to sz(1056, 1584)
    )

    /**
     * 按比例名称返回 Qwen 官方推荐的像素尺寸，不要求推荐尺寸与名称在数学上严格等比。
     */
    @JvmStatic
    fun getQwenSizesByRatio(aspectRatio: String): List<SizeOption> {
        // 官方推荐尺寸经过像素对齐，不能用数学上的严格等比比较，否则横竖图会查不到。
        return listOfNotNull(QWEN_SIZES_BY_RATIO[aspectRatio.trim()])
    }

    /**
     * 将一次发送时选定的比例换算成请求尺寸。
     * 精确尺寸模型使用推荐表；自动模式保留各平台语义，图像编辑不发送不支持的尺寸字段。
     */
    fun resolveRequestImageSize(
        family: ModelFamily,
        modelName: String,
        ratio: ImageRatio,
        configuredSize: String?,
    ): String? {
        val fallback = configuredSize?.takeIf { it.isNotBlank() && !it.contains("<") } ?: "1024x1024"
        return when (family) {
            ModelFamily.KOLORS -> {
                val sizes = getKolorsSizesByRatio(ratio.displayName)
                val exact = sizes.firstOrNull { it.width == ratio.width && it.height == ratio.height }
                (exact ?: sizes.firstOrNull())?.label ?: fallback
            }
            ModelFamily.QWEN -> if (modelName.contains("edit", ignoreCase = true)) {
                null
            } else {
                getQwenSizesByRatio(ratio.displayName).firstOrNull()?.label ?: "1328x1328"
            }
            ModelFamily.GPT_IMAGE -> if (ratio.isAuto) "auto" else getGptImageSize(ratio.displayName)?.label ?: fallback
            ModelFamily.UNKNOWN -> if (ratio.isAuto) fallback else "${ratio.width}x${ratio.height}"
            // Gemini、Seedream 和 Modal 在直连客户端把比例与清晰度一起处理。
            else -> fallback
        }
    }

    /**
     * Kolors 家族（用户提供示例）：
     * - 1024x1024 (1:1)
     * - 960x1280  (3:4)
     * - 768x1024  (3:4)
     * - 720x1440  (1:2)
     * - 720x1280  (9:16)
     */
    private val KOLORS_ALL_SIZES: List<SizeOption> = listOf(
        sz(1024, 1024),
        sz(960, 1280),
        sz(768, 1024),
        sz(720, 1440),
        sz(720, 1280)
    )

    /**
     * 返回 Kolors 家族在给定比例下的 size 候选（如有需要）。
     */
    @JvmStatic
    fun getKolorsSizesByRatio(aspectRatio: String): List<SizeOption> {
        val ratio = aspectRatio.trim()
        return KOLORS_ALL_SIZES.filter { so ->
            gcdEqualRatio(so.width, so.height, ratio)
        }
    }

    // -------- GPT-Image-2 尺寸映射 --------

    private val GPT_IMAGE_SIZES: Map<String, SizeOption> = mapOf(
        "1:1" to sz(1024, 1024),
        "3:2" to sz(1536, 1024),
        "2:3" to sz(1024, 1536),
        "16:9" to sz(2048, 1152),
        "9:16" to sz(1152, 2048),
        "4:3" to sz(2048, 1536),
        "3:4" to sz(1536, 2048)
    )

    private val GPT_IMAGE_SIZES_2K: Map<String, SizeOption> = mapOf(
        "1:1" to sz(2048, 2048),
        "3:2" to sz(2048, 1152),
        "2:3" to sz(1152, 2048),
        "16:9" to sz(3840, 2160),
        "9:16" to sz(2160, 3840),
        "4:3" to sz(2048, 1536),
        "3:4" to sz(1536, 2048)
    )

    /**
     * 返回 GPT-Image-2 在给定比例下的推荐尺寸。
     */
    @JvmStatic
    fun getGptImageSize(aspectRatio: String): SizeOption? {
        return GPT_IMAGE_SIZES[aspectRatio.trim()]
    }

    /**
     * 返回 GPT-Image-2 所有支持的质量档位。
     */
    @JvmStatic
    fun getGptImageQualities(): List<GptImageQuality> {
        return GptImageQuality.values().toList()
    }

    // -------- 工具方法 --------

    private fun ar(ratio: String) = AspectRatioOption(ratio = ratio, displayName = ratio)

    private fun sz(w: Int, h: Int): SizeOption = SizeOption(
        width = w,
        height = h,
        label = "${w}x${h}"
    )

    /**
     * 判断 (w,h) 是否与 "W:H" 等比。
     * 例如 2048x1152 与 "16:9" => true
     */
    private fun gcdEqualRatio(w: Int, h: Int, ratio: String): Boolean {
        val parts = ratio.split(":")
        if (parts.size != 2) return false
        val rw = parts[0].toIntOrNull() ?: return false
        val rh = parts[1].toIntOrNull() ?: return false
        val g1 = gcd(w, h)
        val g2 = gcd(rw, rh)
        return (w / g1 == rw / g2) && (h / g1 == rh / g2)
    }

    private tailrec fun gcd(a: Int, b: Int): Int {
        if (b == 0) return kotlin.math.abs(a)
        return gcd(b, a % b)
    }
}
