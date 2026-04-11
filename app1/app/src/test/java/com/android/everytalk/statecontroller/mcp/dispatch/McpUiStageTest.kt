package com.android.everytalk.statecontroller.mcp.dispatch

import org.junit.Assert.assertEquals
import org.junit.Test

class McpUiStageTest {

    @Test
    fun `routing stage is exposed for ui`() {
        val stage = McpUiStage.ROUTING

        assertEquals("正在选择工具…", stage.userVisibleText)
    }
}
