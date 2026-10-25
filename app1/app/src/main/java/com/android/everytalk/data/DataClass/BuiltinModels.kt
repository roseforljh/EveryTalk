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
fun ApiConfig.isBuiltinModel(): Boolean = builtinImageModels.any { builtin ->
    id == builtin.id || (provider.trim().lowercase() in listOf("默认", "default") && model == builtin.model)
}

/** 每次读取/保存都补齐缺失项，不依赖旧版的一次性初始化标记。 */
fun withBuiltinImageModels(configs: List<ApiConfig>): List<ApiConfig> = configs +
    builtinImageModels.filter { builtin -> configs.none { it.isBuiltinModel() && it.model == builtin.model } }
