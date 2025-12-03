package com.android.everytalk.ui.components

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
        UNKNOWN
    }

    /**
     * 质量档（仅 Seedream 家族显示）
     */
    enum class QualityTier(val label: String) {
        HD("HD"),
        Q2K("2K"),
        Q4K("4K")
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
     * - ratios: 家族可展示的比例枚举；为空表示“沿用应用内既有默认比例集”
     * - supportsQuality: 是否显示清晰度（2K/4K），仅 Seedream = true
     */
    data class FamilyCapabilities(
        val ratios: List<AspectRatioOption>,
        val supportsQuality: Boolean
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
        val parts = sequenceOf(modelName, provider, apiAddress)
            .filterNotNull()
            .map { it.lowercase() }
            .toList()

        fun containsAny(vararg keys: String): Boolean {
            return parts.any { p -> keys.any { k -> p.contains(k) } }
        }

        return when {
            containsAny("z-image-turbo") -> ModelFamily.MODAL_Z_IMAGE
            containsAny("gemini", "google") -> ModelFamily.GEMINI
            containsAny("doubao", "seedream", "volces") -> ModelFamily.SEEDREAM
            containsAny("qwen", "qwen-image", "qwen-vl") -> ModelFamily.QWEN
            containsAny("kolors", "kwai", "siliconflow") -> ModelFamily.KOLORS
            else -> ModelFamily.UNKNOWN
        }
    }

    // -------- 家族 → 比例候选 --------

    private val RATIOS_GEMINI_USE_DEFAULT: List<AspectRatioOption> = emptyList() // 走现有默认 ImageRatio.DEFAULT_RATIOS

    private val RATIOS_SEEDREAM: List<AspectRatioOption> = listOf(
        ar("1:1"),
        ar("16:9"),
        ar("9:16"),
        ar("4:3"),
        ar("3:4")
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

    // UNKNOWN：保留空表示“走应用现有默认比例集”
    private val RATIOS_UNKNOWN_USE_DEFAULT: List<AspectRatioOption> = emptyList()

    /**
     * 获取家族能力（比例候选 + 是否支持清晰度）
     */
    @JvmStatic
    fun getCapabilities(family: ModelFamily): FamilyCapabilities = when (family) {
        ModelFamily.GEMINI -> FamilyCapabilities(
            ratios = RATIOS_GEMINI_USE_DEFAULT,
            supportsQuality = false
        )
        ModelFamily.SEEDREAM -> FamilyCapabilities(
            ratios = RATIOS_SEEDREAM,
            supportsQuality = true
        )
        ModelFamily.QWEN -> FamilyCapabilities(
            ratios = RATIOS_QWEN,
            supportsQuality = false
        )
        ModelFamily.KOLORS -> FamilyCapabilities(
            ratios = RATIOS_KOLORS,
            supportsQuality = false
        )
        ModelFamily.MODAL_Z_IMAGE -> FamilyCapabilities(
            ratios = RATIOS_MODAL_Z_IMAGE,
            supportsQuality = false // 不需要二级分组，直接在列表中展示
        )
        ModelFamily.UNKNOWN -> FamilyCapabilities(
            ratios = RATIOS_UNKNOWN_USE_DEFAULT,
            supportsQuality = false
        )
    }

    // -------- 尺寸映射（仅 Seedream 家族使用）--------

    /**
     * Seedream 2K / 4K 清晰度下，不同比例对应的推荐精确尺寸。
     * 来源（用户提供）：
     * - 1:1  -> 2K: 2048x2048, 4K: 4096x4096
     * - 16:9 -> 2K: 2048x1152, 4K: 3840x2160
     * - 9:16 -> 2K: 1152x2048, 4K: 2160x3840
     * - 4:3  -> 2K: 2048x1536, 4K: 4096x3072
     * - 3:4  -> 2K: 1536x2048, 4K: 3072x4096
     */
    private val SEEDREAM_SIZES_2K: Map<String, List<SizeOption>> = mapOf(
        "1:1" to listOf(sz(2048, 2048)),
        "16:9" to listOf(sz(2048, 1152)),
        "9:16" to listOf(sz(1152, 2048)),
        "4:3" to listOf(sz(2048, 1536)),
        "3:4" to listOf(sz(1536, 2048))
    )

    private val SEEDREAM_SIZES_4K: Map<String, List<SizeOption>> = mapOf(
        "1:1" to listOf(sz(4096, 4096)),
        "16:9" to listOf(sz(3840, 2160)),
        "9:16" to listOf(sz(2160, 3840)),
        "4:3" to listOf(sz(4096, 3072)),
        "3:4" to listOf(sz(3072, 4096))
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
        }
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
    private val QWEN_ALL_SIZES: List<SizeOption> = listOf(
        sz(1328, 1328),
        sz(1664, 928),
        sz(928, 1664),
        sz(1472, 1140),
        sz(1140, 1472),
        sz(1584, 1056),
        sz(1056, 1584)
    )

    /**
     * 返回 Qwen 家族在给定比例下的 size 候选（如果需要按比例精确过滤）。
     * 默认不强制 UI 使用，交由上层按需。
     */
    @JvmStatic
    fun getQwenSizesByRatio(aspectRatio: String): List<SizeOption> {
        val ratio = aspectRatio.trim()
        return QWEN_ALL_SIZES.filter { so ->
            gcdEqualRatio(so.width, so.height, ratio)
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