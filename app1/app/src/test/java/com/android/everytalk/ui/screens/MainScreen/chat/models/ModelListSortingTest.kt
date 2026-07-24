package com.android.everytalk.ui.screens.MainScreen.chat.models

import com.android.everytalk.data.DataClass.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelListSortingTest {
    @Test
    fun `模型列表按模型名称首字母排序，空模型使用配置名称`() {
        val configs = listOf(
            config(model = "zeta", name = "Zeta"),
            config(model = "GPT-4o", name = "GPT"),
            config(model = "", name = "Alpha fallback"),
            config(model = "gemini-2.5-flash", name = "Gemini")
        )

        val sorted = sortModelConfigs(configs)

        assertEquals(
            listOf("", "gemini-2.5-flash", "GPT-4o", "zeta"),
            sorted.map { it.model }
        )
        assertEquals("Alpha fallback", sorted.first().name)
    }

    private fun config(model: String, name: String): ApiConfig = ApiConfig(
        address = "https://example.com",
        key = "key",
        model = model,
        provider = "test",
        name = name
    )
}
