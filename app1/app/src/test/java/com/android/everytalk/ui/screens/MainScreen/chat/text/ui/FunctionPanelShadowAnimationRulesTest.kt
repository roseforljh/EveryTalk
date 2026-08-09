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
        val floatingCard = floatingCardSource().readText(Charsets.UTF_8)
        val popupBlock = source
            .substringAfter("AppFloatingCardPopup(")
            .substringBefore("AppFloatingCardPopup(")

        assertTrue("功能面板必须使用统一动画悬浮卡片", popupBlock.contains("visible = showFunctionPanel"))
        assertTrue("功能面板不得在关闭时直接移除", !popupBlock.contains("if (showFunctionPanel) {"))
        assertTrue("功能面板不得保留透明度动画", !source.contains("functionPanelAlpha"))
        assertTrue("功能面板不得保留缩放动画", !source.contains("functionPanelScale"))
        assertTrue("入场动画必须集中在统一悬浮卡片", floatingCard.contains("val scale = remember { Animatable(0.8f) }"))
        assertTrue("退出动画必须集中在统一悬浮卡片", floatingCard.contains("AppFloatingCardExitDurationMillis = 80"))
        assertTrue("统一悬浮卡片必须直接淡出自身图层", floatingCard.contains("targetValue = 0f"))
        assertTrue("阴影外不得套矩形动画图层", !floatingCard.contains("AnimatedVisibility"))
        assertTrue(
            "阴影必须随卡片同步入场",
            floatingCard.indexOf(".shadow(AppFloatingCardElevation, AppFloatingCardShape)") >
                floatingCard.indexOf(".graphicsLayer {"),
        )
        assertTrue("不得延迟启用阴影", !floatingCard.contains("enterAnimationFinished"))
        assertTrue("统一悬浮卡片不得使用离屏合成策略", !floatingCard.contains("CompositingStrategy"))
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

    private fun floatingCardSource(): File {
        val relativePath = "ui/components/popup/AppFloatingCard.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 AppFloatingCard.kt" }
    }
}
