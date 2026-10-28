package com.android.everytalk.data.DataClass

/** 两端使用相同的内置模型身份；旧随机 ID 保留，避免破坏会话里的模型引用。 */
val builtinImageModels: List<ApiConfig> = listOf(
    ApiConfig(id = "builtin-z-image-turbo", name = "Z-Image-Turbo (Modal)", model = "z-image-turbo-modal", numInferenceSteps = 4,
        provider = "默认", address = "", key = "", channel = "", modalityType = ModalityType.IMAGE),
    ApiConfig(id = "builtin-qwen-image-edit", name = "Qwen Image Edit", model = "qwen-image-edit-modal", numInferenceSteps = 30,
        provider = "默认", address = "", key = "", channel = "", modalityType = ModalityType.IMAGE),
    ApiConfig(id = "builtin-kolors", name = "Kwai-Kolors/Kolors", model = "Kwai-Kolors/Kolors",
        provider = "默认", address = "", key = "", channel = "", modalityType = ModalityType.IMAGE),
)

/** 公共删除和持久化入口都使用此判断，导入、清空和远端墓碑不能删掉内置项。 */
fun ApiConfig.builtinModelId(): String? = builtinImageModels.firstOrNull { builtin ->
    id == builtin.id || (provider.trim().lowercase() in listOf("默认", "default") && model == builtin.model)
}?.id

fun ApiConfig.isBuiltinModel(): Boolean = builtinModelId() != null

/**
 * 按内置身份合并不同设备的随机 ID；优先保留固定 ID，否则按 ID 排序，保证拉取顺序不影响结果。
 * 这里只规范对外列表，存储层保留旧 ID 作为历史引用的别名，自定义模型始终独立保留。
 */
fun withBuiltinImageModels(configs: List<ApiConfig>): List<ApiConfig> {
    val retained = configs.filter { it.isBuiltinModel() }.groupBy { it.builtinModelId() }.mapValues { (builtinId, values) ->
        values.minWith(compareBy<ApiConfig> { it.id != builtinId }.thenBy { it.id })
    }
    val seen = hashSetOf<String>()
    val unique = configs.filter { config ->
        val builtinId = config.builtinModelId()
        builtinId == null || (retained[builtinId]?.id == config.id && seen.add(builtinId))
    }
    return unique + builtinImageModels.filter { it.id !in retained }
}
