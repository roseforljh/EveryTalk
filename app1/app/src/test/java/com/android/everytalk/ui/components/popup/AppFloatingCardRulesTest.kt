package com.android.everytalk.ui.components.popup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFloatingCardRulesTest {
    @Test
    fun `所有自定义 Popup 使用统一悬浮卡片`() {
        val popupFiles = mainSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val lines = file.readLines(Charsets.UTF_8)
                val popupCount = lines.count { line ->
                    val code = line.trimStart()
                    code.startsWith("Popup(") ||
                        code.startsWith("androidx.compose.ui.window.Popup(")
                }
                if (popupCount == 0) null else Triple(file, lines, popupCount)
            }
            .toList()

        assertTrue("至少应存在一个自定义 Popup", popupFiles.isNotEmpty())
        popupFiles.forEach { (file, lines, popupCount) ->
            val cardCount = lines.count { line ->
                val code = line.trimStart()
                code.startsWith("AppFloatingCard(") || code.startsWith("AppFloatingCard {")
            }
            assertEquals(
                "${file.name} 中每个自定义 Popup 都必须使用 AppFloatingCard",
                popupCount,
                cardCount,
            )
        }
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
    fun `悬浮卡片复用正确浮层的同步阴影入场实现`() {
        val source = File(
            mainSourceRoot(),
            "ui/components/popup/AppFloatingCard.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(source.contains("AppFloatingCardShape = RoundedCornerShape(28.dp)"))
        assertTrue(source.contains("AppFloatingCardElevation = 8.dp"))
        assertTrue("统一悬浮卡片必须从顶部同步展开", source.contains("TransformOrigin(0.5f, 0f)"))
        assertTrue("统一悬浮卡片必须复用 0.8 起始缩放", source.contains("val scale = remember { Animatable(0.8f) }"))
        assertTrue("统一悬浮卡片必须复用 30ms 透明度动画", source.contains("durationMillis = 30"))
        assertTrue("统一悬浮卡片必须复用 120ms 缩放动画", source.contains("durationMillis = 120"))

        val layerIndex = source.indexOf(".graphicsLayer {")
        val shadowIndex = source.indexOf(".shadow(AppFloatingCardElevation, AppFloatingCardShape)")
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
        assertTrue("统一悬浮卡片不得使用离屏合成策略", !source.contains("CompositingStrategy"))
    }

    @Test
    fun `AI 气泡悬浮卡片关闭时立即移除`() {
        val source = File(
            mainSourceRoot(),
            "ui/screens/MainScreen/chat/text/ui/AiContextUsagePopup.kt",
        ).readText(Charsets.UTF_8)
        val popupFunction = source.substringAfter("internal fun AiMessageFloatingPopupCard(")

        assertTrue("AI 气泡悬浮卡片必须由 expanded 直接控制", popupFunction.contains("if (!expanded) return"))
        assertTrue("AI 气泡悬浮卡片不得保留退出可见状态", !popupFunction.contains("var visible"))
        assertTrue("AI 气泡悬浮卡片不得延迟退出", !popupFunction.contains("delay("))
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
