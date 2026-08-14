package com.android.everytalk.ui.screens.mcp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定 MCP 开关和 Agent 首次提示框已经确认的主题规则。 */
class McpUiRegressionTest {
    @Test
    fun `关闭状态开关在浅色和深色主题下保持可见`() {
        val source = sourceFile("ui/screens/mcp/McpDialogs.kt").readText(Charsets.UTF_8)
            .substringAfter("Switch(")
            .substringBefore("if (!config.enabled")

        assertTrue(source.contains("uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(source.contains("uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest"))
        assertTrue(source.contains("uncheckedBorderColor = MaterialTheme.colorScheme.outline"))
    }

    @Test
    fun `Agent首次提示框复用统一黑白对话框样式`() {
        val source = sourceFile("ui/screens/MainScreen/chat/text/ui/ChatInputDialogs.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("internal fun AgentDisclosureDialog(")

        assertTrue(source.contains("containerColor = appDialogContainerColor()"))
        assertTrue(source.contains("titleContentColor = appDialogContentColor()"))
        assertTrue(source.contains("textContentColor = appDialogContentColor()"))
        assertTrue(source.contains("shape = AppDialogButtonShape"))
        assertTrue(source.contains("containerColor = appDialogContentColor()"))
        assertTrue(source.contains("contentColor = appDialogContainerColor()"))
    }

    @Test
    fun `持久化关闭状态不会被旧连接状态覆盖`() {
        val source = sourceFile("ui/screens/settings/SettingsScreen.kt").readText(Charsets.UTF_8)
            .substringAfter("serverStates = allMcpConfigs.mapValues")
            .substringBefore("onAddServer =")

        assertTrue(source.contains("if (persistedState.config.enabled)"))
        assertTrue(source.contains("persistedState.copy(tools = runtimeState?.tools.orEmpty())"))
    }

    private fun sourceFile(relativePath: String): File {
        val roots = listOf(
            File("src/main/java/com/android/everytalk"),
            File("app/src/main/java/com/android/everytalk"),
            File("app1/app/src/main/java/com/android/everytalk"),
        )
        val root = requireNotNull(roots.firstOrNull(File::isDirectory)) { "找不到主源码目录" }
        return File(root, relativePath)
    }
}
