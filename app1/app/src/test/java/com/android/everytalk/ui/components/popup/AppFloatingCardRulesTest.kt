package com.android.everytalk.ui.components.popup

import java.io.File
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFloatingCardRulesTest {
    @Test
    fun `统一悬浮卡片使用当前黑白主题背景`() {
        assertEquals(Color.White, resolveAppFloatingCardContainerColor(isDarkTheme = false))
        assertEquals(Color(0xFF242424), resolveAppFloatingCardContainerColor(isDarkTheme = true))
    }

    @Test
    fun `所有自定义 Popup 使用统一动画悬浮卡片`() {
        val sourceFiles = mainSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val popupComponent = sourceFiles.single { it.name == "AppFloatingCard.kt" }
        val directPopupFiles = sourceFiles
            .filterNot { it == popupComponent }
            .mapNotNull { file ->
                val lines = file.readLines(Charsets.UTF_8)
                val hasDirectPopup = lines.any { line ->
                    val code = line.trimStart()
                    code.startsWith("Popup(") ||
                        code.startsWith("androidx.compose.ui.window.Popup(")
                }
                file.takeIf { hasDirectPopup }
            }
            .toList()
        val animatedPopupCount = sourceFiles
            .filterNot { it == popupComponent }
            .sumOf { file ->
                file.readLines(Charsets.UTF_8).count { line ->
                    line.trimStart().startsWith("AppFloatingCardPopup(")
                }
            }

        assertTrue("自定义 Popup 只能由 AppFloatingCardPopup 创建：$directPopupFiles", directPopupFiles.isEmpty())
        assertTrue("至少应存在一个统一动画悬浮卡片", animatedPopupCount > 0)
    }

    @Test
    fun `Material 下拉浮层复用统一视觉参数`() {
        val menuFiles = mainSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines(Charsets.UTF_8).any { line ->
                    val code = line.trimStart()
                    code.startsWith("DropdownMenu(") || code.startsWith("ExposedDropdownMenu(")
                }
            }
            .toList()

        assertTrue("至少应存在一个 Material 下拉浮层", menuFiles.isNotEmpty())
        menuFiles.forEach { file ->
            val source = file.readText(Charsets.UTF_8)
            assertTrue("${file.name} 未统一圆角", source.contains("shape = AppFloatingCardShape"))
            assertTrue("${file.name} 未统一背景", source.contains("appFloatingCardContainerColor()"))
            assertTrue("${file.name} 未统一阴影", source.contains("shadowElevation = AppFloatingCardElevation"))
        }
    }

    @Test
    fun `悬浮卡片复用正确浮层的入场和快速淡出实现`() {
        val source = File(
            mainSourceRoot(),
            "ui/components/popup/AppFloatingCard.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(source.contains("AppFloatingCardShape = RoundedCornerShape(28.dp)"))
        assertTrue("浅色悬浮卡片必须使用当前白色背景", source.contains("else Color.White"))
        assertTrue(source.contains("fun AppFloatingCardContainer("))
        assertTrue(source.contains("AppFloatingCardElevation = 8.dp"))
        assertTrue("统一悬浮卡片必须从顶部同步展开", source.contains("TransformOrigin(0.5f, 0f)"))
        assertTrue("统一悬浮卡片必须复用 0.8 起始缩放", source.contains("val scale = remember { Animatable(0.8f) }"))
        assertTrue("统一悬浮卡片必须复用 30ms 透明度动画", source.contains("durationMillis = 30"))
        assertTrue("统一悬浮卡片必须复用 120ms 缩放动画", source.contains("durationMillis = 120"))
        assertTrue("统一悬浮卡片必须使用 80ms 快速淡出", source.contains("AppFloatingCardExitDurationMillis = 80"))
        assertTrue("统一悬浮卡片必须直接把自身透明度降为零", source.contains("targetValue = 0f"))
        assertTrue("Popup 必须保留到淡出完成", source.contains("onExitAnimationFinished()"))
        assertTrue("圆角阴影外不得套矩形 AnimatedVisibility 图层", !source.contains("AnimatedVisibility"))

        val layerIndex = source.indexOf(".graphicsLayer {")
        val shadowIndex = source.indexOf(".dropShadow(AppFloatingCardShape)")
        val borderIndex = source.indexOf(".border(1.dp, appFloatingCardBorderColor(), AppFloatingCardShape)")
        assertTrue(
            "阴影必须和卡片处于同一个入场动画链",
            layerIndex >= 0 && shadowIndex > layerIndex,
        )
        assertTrue(
            "描边必须跟随同步阴影之后绘制",
            borderIndex > shadowIndex,
        )
        assertTrue("不得延迟启用阴影", !source.contains("enterAnimationFinished"))
        assertTrue("不得改用 Surface 延迟阴影", !source.contains("shadowElevation ="))
        assertTrue(
            "卡片与阴影必须共用透明度图层",
            source.contains("this.alpha = alpha.value"),
        )
        assertTrue(
            "圆角阴影必须保持零偏移对称绘制",
            source.contains("offset = Offset.Zero"),
        )
        assertTrue("统一悬浮卡片不得使用逐绘制透明度", !source.contains("CompositingStrategy"))
    }

    @Test
    fun `AI 气泡悬浮卡片关闭时走统一淡出`() {
        val source = File(
            mainSourceRoot(),
            "ui/screens/MainScreen/chat/text/ui/AiContextUsagePopup.kt",
        ).readText(Charsets.UTF_8)
        val popupFunction = source.substringAfter("internal fun AiMessageFloatingPopupCard(")

        assertTrue("AI 气泡悬浮卡片必须使用统一动画容器", popupFunction.contains("AppFloatingCardPopup("))
        assertTrue("AI 气泡悬浮卡片必须由 expanded 控制", popupFunction.contains("visible = expanded"))
        assertTrue("AI 气泡悬浮卡片不得在退出前直接移除", !popupFunction.contains("if (!expanded) return"))
    }

    @Test
    fun `固定头尾悬浮卡片只绘制一层统一背景`() {
        val popupSource = File(
            mainSourceRoot(),
            "ui/components/popup/AppFloatingCard.kt",
        ).readText(Charsets.UTF_8)
        val scaffoldSource = popupSource.substringAfter("fun AppFloatingCardScaffold(")
            .substringBefore("fun AppFloatingCardPopup(")
        val confirmationSource = File(
            mainSourceRoot(),
            "ui/screens/MainScreen/chat/text/ui/ChatInputPanels.kt",
        ).readText(Charsets.UTF_8)
            .substringAfter("internal fun ComputerHostCommandConfirmationCard(")
            .substringBefore("internal fun computerStatusLabelRes")

        assertTrue(scaffoldSource.contains("AppFloatingCardContainer("))
        assertTrue(scaffoldSource.contains("fun AppFloatingCardScaffoldPopup("))
        assertTrue(scaffoldSource.contains("AppFloatingCardPopup("))
        assertTrue(scaffoldSource.contains(".background(containerColor)"))
        assertTrue(scaffoldSource.contains("heightIn(max = 220.dp)"))
        assertTrue(scaffoldSource.contains("verticalScroll(rememberScrollState())"))
        assertTrue(confirmationSource.contains("AppFloatingCardScaffoldPopup("))
        assertTrue(confirmationSource.contains("header = {"))
        assertTrue(confirmationSource.contains("footer = {"))
        assertTrue("确认卡必须保留到统一退场动画完成", confirmationSource.contains("visible = request != null"))
        assertTrue(confirmationSource.contains("onExitAnimationFinished = {"))
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk"),
            File("app/src/main/java/com/android/everytalk"),
            File("app1/app/src/main/java/com/android/everytalk"),
        )
        return requireNotNull(candidates.firstOrNull(File::isDirectory)) { "找不到主源码目录" }
    }
}
