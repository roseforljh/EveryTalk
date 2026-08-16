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

    @Test
    fun `当前模型位于中间时只显示上下各三个`() {
        val configs = ('a'..'i').map { config(model = it.toString(), name = it.toString()) }

        val window = centeredModelWindow(configs, configs[4].id)

        assertEquals(listOf("b", "c", "d", "e", "f", "g", "h"), window.map { it.model })
    }

    @Test
    fun `当前模型靠近首尾时不从另一侧补齐`() {
        val configs = ('a'..'i').map { config(model = it.toString(), name = it.toString()) }

        assertEquals(
            listOf("a", "b", "c", "d"),
            centeredModelWindow(configs, configs.first().id).map { it.model },
        )
        assertEquals(
            listOf("f", "g", "h", "i"),
            centeredModelWindow(configs, configs.last().id).map { it.model },
        )
    }

    @Test
    fun `找不到当前模型时最多显示前七个`() {
        val configs = ('a'..'i').map { config(model = it.toString(), name = it.toString()) }

        assertEquals(7, centeredModelWindow(configs, "missing").size)
    }

    private fun config(model: String, name: String): ApiConfig = ApiConfig(
        address = "https://example.com",
        key = "key",
        model = model,
        provider = "test",
        name = name
    )
}
