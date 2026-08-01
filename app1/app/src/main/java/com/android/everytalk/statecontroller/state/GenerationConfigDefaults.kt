package com.android.everytalk.statecontroller

/** 根据模型系列给出默认推理令牌预算。 */
fun defaultReasoningBudgetForModel(model: String): Int {
    val normalized = model.lowercase()
    return when {
        "flash" in normalized -> 1024
        "pro" in normalized -> 8192
        else -> 24576
    }
}
