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
