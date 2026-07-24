package com.android.everytalk.data.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpToolExecutorLifecycleTest {

    @Test
    fun `MCP 执行器按拥有者清理并保留兼容入口`() {
        listOf(
            "data/network/llm/GeminiDirectClient.kt",
            "data/network/llm/OpenAIDirectClient.kt",
            "data/network/llm/OpenAIResponsesClient.kt",
        ).forEach { relativePath ->
            val source = source(relativePath)
            assertTrue(source.contains("private var mcpToolExecutorOwner: Any? = null"))
            assertTrue(source.contains("fun setMcpToolExecutor(\n        owner: Any,"))
            assertTrue(source.contains("fun setMcpToolExecutor(\n        executor:"))
            assertTrue(source.contains("if (mcpToolExecutorOwner === owner)"))
            assertTrue(source.contains("mcpToolExecutor = null"))
        }

        val viewModel = source("statecontroller/viewmodel/AppViewModel.kt")
        assertTrue(viewModel.contains("private val mcpToolExecutorOwner = Any()"))
        assertTrue(viewModel.contains("GeminiDirectClient.clearMcpToolExecutor(mcpToolExecutorOwner)"))
        assertTrue(viewModel.contains("OpenAIDirectClient.clearMcpToolExecutor(mcpToolExecutorOwner)"))
        assertTrue(viewModel.contains("OpenAIResponsesClient.clearMcpToolExecutor(mcpToolExecutorOwner)"))
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 $relativePath"
        }.readText(Charsets.UTF_8)
    }
}
