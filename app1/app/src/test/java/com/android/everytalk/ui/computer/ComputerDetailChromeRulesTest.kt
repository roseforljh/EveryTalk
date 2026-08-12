package com.android.everytalk.ui.computer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定服务器详情页与应用现有页面一致的顶部浮层和系统栏沉浸规则。 */
class ComputerDetailChromeRulesTest {
    @Test
    fun `详情页使用浮动返回按钮和名称胶囊`() {
        val source = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)

        assertFalse("详情页不得恢复整块普通顶栏", source.contains("TopAppBar("))
        assertTrue(source.contains("TopCircleButton("))
        assertTrue(source.contains("iconRes = R.drawable.ic_arrow_back"))
        assertTrue(source.contains("computer?.displayName"))
        assertTrue(source.contains("RoundedCornerShape(percent = 50)"))
        assertTrue(source.contains(".floatingEdgeGradient(screenBackground, fromTop = true)"))
    }

    @Test
    fun `详情页背景延伸到系统栏且内容主动避让`() {
        val source = sourceFile("ui/screens/computer/ComputerDetailScreen.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("contentWindowInsets = WindowInsets(0.dp)"))
        assertTrue(source.contains("WindowInsets.statusBars.asPaddingValues().calculateTopPadding()"))
        assertTrue(source.contains("WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()"))
        assertTrue(source.contains(".align(Alignment.BottomCenter)"))
        assertTrue(source.contains(".floatingEdgeGradient(screenBackground, fromTop = false)"))
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
