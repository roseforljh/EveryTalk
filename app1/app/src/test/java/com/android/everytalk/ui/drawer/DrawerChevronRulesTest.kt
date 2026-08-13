package com.android.everytalk.ui.drawer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerChevronRulesTest {
    @Test
    fun `侧边栏分组使用GPT尖括号并围绕中心旋转`() {
        val source = sourceFile().readText(Charsets.UTF_8)
            .substringAfter("fun CollapsibleGroupHeader(")

        assertTrue(source.contains("R.drawable.ic_gpt_chevron_right"))
        assertTrue(source.contains("targetValue = if (isExpanded) 90f else 0f"))
        assertTrue(source.contains("transformOrigin = TransformOrigin.Center"))
        assertTrue(source.contains("easing = FastOutSlowInEasing"))
        assertFalse(source.contains("R.drawable.ic_arrow_end"))
    }

    private fun sourceFile(): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/MainScreen/drawer/AppDrawerGroupComponents.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/MainScreen/drawer/AppDrawerGroupComponents.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/drawer/AppDrawerGroupComponents.kt"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到侧边栏分组源码" }
    }
}
