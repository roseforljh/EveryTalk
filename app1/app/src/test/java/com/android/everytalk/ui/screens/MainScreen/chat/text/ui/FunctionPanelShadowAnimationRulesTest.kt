package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FunctionPanelShadowAnimationRulesTest {
    @Test
    fun `功能面板优先显示图片相机和附件`() {
        val functionPanel = chatInputPanelsSource().readText(Charsets.UTF_8)
            .substringAfter("fun FunctionPanelContent(")
            .substringBefore("fun FunctionPanelRow(")
        val labels = listOf(
            "R.string.chat_input_image",
            "R.string.chat_input_camera",
            "R.string.chat_input_attachment",
            "R.string.chat_input_web_search",
            "label = \"MCP\"",
            "R.string.chat_input_prompt",
        )
        val positions = labels.map(functionPanel::indexOf)

        assertTrue("功能面板选项缺失", positions.all { it >= 0 })
        assertTrue("功能面板选项顺序错误", positions.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun `功能面板使用统一同步阴影入场和快速淡出`() {
        val source = chatInputSource().readText(Charsets.UTF_8)
        val popupBlock = source
            .substringAfter("AppFloatingCardPopup(")
            .substringBefore("AppFloatingCardPopup(")

        assertTrue("功能面板必须使用统一动画悬浮卡片", popupBlock.contains("visible = showFunctionPanel"))
        assertTrue("功能面板不得在关闭时直接移除", !popupBlock.contains("if (showFunctionPanel) {"))
        assertTrue("功能面板不得保留透明度动画", !source.contains("functionPanelAlpha"))
        assertTrue("功能面板不得保留缩放动画", !source.contains("functionPanelScale"))
    }

    @Test
    fun `MCP颜色与紧凑标签布局保持一致`() {
        val inputSource = chatInputSource().readText(Charsets.UTF_8)
        val panelSource = chatInputPanelsSource().readText(Charsets.UTF_8)
        val componentSource = chatInputComponentsSource().readText(Charsets.UTF_8)
        val activeTagSource = componentSource
            .substringAfter("internal fun ActiveFunctionTag(")
            .substringBefore("fun OptimizedSelectedItemPreview(")

        assertTrue(panelSource.contains("iconTint = if (isMcpEnabled) ChatMcpColor else iconTint"))
        assertTrue(inputSource.contains("tint = ChatMcpColor"))
        assertTrue(inputSource.contains("val activeTagCount ="))
        assertTrue(inputSource.contains("maxItemsInEachRow = 3"))
        assertTrue(inputSource.contains(".padding(top = 4.dp, end = 11.dp, bottom = 2.dp)"))
        assertTrue(inputSource.contains("if (activeTagCount == 3)"))
        assertTrue(inputSource.contains("Arrangement.SpaceBetween"))
        assertTrue(inputSource.contains("Arrangement.spacedBy(2.dp)"))
        assertTrue(activeTagSource.contains(".padding(horizontal = 5.dp, vertical = 5.dp)"))
        assertTrue(activeTagSource.contains("fontSize = 13.sp"))
        assertTrue(activeTagSource.contains(".clip(RoundedCornerShape(percent = 50))"))
        assertTrue(panelSource.contains(".clip(RoundedCornerShape(16.dp))"))
    }

    private fun chatInputSource(): File {
        val relativePath = "ui/screens/MainScreen/chat/text/ui/ChatInputArea.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 ChatInputArea.kt" }
    }

    private fun chatInputPanelsSource(): File {
        val relativePath = "ui/screens/MainScreen/chat/text/ui/ChatInputPanels.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 ChatInputPanels.kt" }
    }

    private fun chatInputComponentsSource(): File {
        val relativePath = "ui/screens/MainScreen/chat/text/ui/ChatInputComponents.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 ChatInputComponents.kt" }
    }
}
