package com.android.everytalk.ui.screens.MainScreen.chat.models
import com.android.everytalk.statecontroller.*

import com.android.everytalk.data.DataClass.ApiConfig
import java.util.Locale

internal fun sortModelConfigs(configs: List<ApiConfig>): List<ApiConfig> =
    configs.sortedWith(
        compareBy<ApiConfig> {
            it.model.trim().ifEmpty { it.name.trim() }.lowercase(Locale.ROOT)
        }
            .thenBy { it.name.trim().lowercase(Locale.ROOT) }
            .thenBy { it.id }
    )

/**
 * 模型卡片默认只展示当前模型附近的内容。
 * 当前模型上方、下方各保留 radius 个，靠近列表首尾时不额外补齐另一侧。
 */
internal fun centeredModelWindow(
    configs: List<ApiConfig>,
    selectedConfigId: String?,
    radius: Int = 3,
): List<ApiConfig> {
    val sorted = sortModelConfigs(configs)
    val safeRadius = radius.coerceAtLeast(0)
    val selectedIndex = sorted.indexOfFirst { it.id == selectedConfigId }
    if (selectedIndex < 0) return sorted.take(safeRadius * 2 + 1)
    return sorted.subList(
        fromIndex = (selectedIndex - safeRadius).coerceAtLeast(0),
        toIndex = (selectedIndex + safeRadius + 1).coerceAtMost(sorted.size),
    )
}
