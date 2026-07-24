package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FunctionPanelShadowAnimationRulesTest {
    @Test
    fun `功能面板阴影与内容处于同一缩放层`() {
        val source = chatInputSource().readText(Charsets.UTF_8) + "\n" +
            chatInputPanelsSource().readText(Charsets.UTF_8)
        val popupBlock = source
            .substringAfterLast("if (renderFunctionPanel) {")
            .substringBefore("if (renderImageSelectionPanel) {")
        val functionPanel = source
            .substringAfter("fun FunctionPanelContent(")
            .substringBefore("fun FunctionPanelRow(")
        val alphaIndex = popupBlock.indexOf("alpha = functionPanelAlpha.value")
        val scaleIndex = popupBlock.indexOf("scaleX = functionPanelScale.value")
        val shadowIndex = functionPanel.indexOf(".shadow(8.dp, RoundedCornerShape(28.dp))")

        assertTrue("功能面板内容必须保留 8.dp 阴影", shadowIndex >= 0)
        assertTrue("功能面板弹出层必须保留透明度动画", alphaIndex >= 0)
        assertTrue("功能面板弹出层必须保留缩放动画", scaleIndex >= 0)
        assertTrue("打开时透明度必须立即生效，避免阴影晚于内容出现", source.contains("functionPanelAlpha.snapTo(1f)"))
        assertFalse(
            "Popup 外层不能单独创建阴影，否则会先于缩放中的面板出现",
            popupBlock.contains(".shadow(8.dp, RoundedCornerShape(28.dp))")
        )
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
}
