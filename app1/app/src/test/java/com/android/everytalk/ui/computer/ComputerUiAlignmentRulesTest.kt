package com.android.everytalk.ui.computer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定服务器页面与配置页面已经确认的关键视觉和键盘布局规则。 */
class ComputerUiAlignmentRulesTest {
    @Test
    fun `服务器页顶部保持左返回和右侧双按钮胶囊`() {
        val source = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val topBar = source.substringAfter(".floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)")
            .substringBefore("if (showAddCard)")

        assertTrue(topBar.contains("iconRes = R.drawable.ic_arrow_back"))
        assertTrue(topBar.contains("modifier = Modifier.align(Alignment.CenterStart)"))
        assertTrue(topBar.contains(".width(topButtonSize * 2)"))
        assertTrue(topBar.contains("RoundedCornerShape(percent = 50)"))
        assertTrue(topBar.contains("R.drawable.ic_plus"))
        assertTrue(topBar.contains("R.drawable.ic_dots_horizontal"))
        assertTrue(topBar.contains("modifier = Modifier.align(Alignment.CenterEnd)"))
        assertTrue(topBar.contains("SettingsTabMenu("))
        assertFalse("服务器顶栏不应继续显示居中标题", topBar.contains("computer_screen_title"))
    }

    @Test
    fun `服务器三点菜单能够切回目标设置页签`() {
        val computerSource = sourceFile("ui/screens/computer/ComputerScreen.kt")
            .readText(Charsets.UTF_8)
        val settingsSource = sourceFile("ui/screens/settings/SettingsScreen.kt")
            .readText(Charsets.UTF_8)

        assertTrue(computerSource.contains("getBackStackEntry(Screen.SETTINGS_SCREEN)"))
        assertTrue(computerSource.contains("Screen.SETTINGS_TAB_REQUEST_KEY"))
        assertTrue(computerSource.contains("Screen.SETTINGS_IMPORT_EXPORT_REQUEST_KEY"))
        assertTrue(computerSource.contains("popBackStack(Screen.SETTINGS_SCREEN, inclusive = false)"))
        assertTrue(settingsSource.contains("getStateFlow(Screen.SETTINGS_TAB_REQUEST_KEY, -1)"))
        assertTrue(settingsSource.contains("currentTabIndex = requestedTabIndex"))
        assertTrue(settingsSource.contains("showImportExportDialog = true"))
    }

    @Test
    fun `添加服务器对话框复用配置页样式和输入法收缩规则`() {
        val source = sourceFile("ui/screens/computer/ComputerAddCard.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("usePlatformDefaultWidth = false"))
        assertTrue(source.contains("decorFitsSystemWindows = false"))
        assertTrue(source.contains(".statusBarsPadding()"))
        assertTrue(source.contains(".navigationBarsPadding()"))
        assertTrue(source.contains(".imePadding()"))
        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(source.contains(".padding(horizontal = 16.dp)"))
        assertTrue(source.contains(".padding(24.dp)"))
        assertTrue(source.contains("shape = AppDialogShape"))
        assertTrue(source.contains("shape = AppDialogTextFieldShape"))
        assertTrue(source.contains("SettingsFieldLabel(label)"))
        assertTrue(source.contains("OutlinedButton("))
    }

    @Test
    fun `服务器入口固定跟在第三项MCP之后`() {
        val source = sourceFile("ui/screens/settings/SettingsScreen.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("private fun SettingsTabMenu(")

        val tabsIndex = source.indexOf("tabs.forEachIndexed")
        val serverIndex = source.indexOf("R.string.settings_servers")
        val importExportIndex = source.indexOf("R.string.settings_import_export")

        assertTrue("设置页签必须按声明顺序绘制", tabsIndex >= 0)
        assertTrue("服务器入口必须紧跟设置页签", serverIndex > tabsIndex)
        assertTrue("导入导出入口必须放在服务器之后", importExportIndex > serverIndex)
        assertFalse("菜单不得再按文字长度重排", source.contains("sortedBy { it.second.length }"))
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
