package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EveryTalkLoadingIndicatorTest {
    @Test
    fun `loading elapsed text is clamped and formatted in seconds`() {
        assertEquals("0s", everyTalkLoadingElapsedText(-1L))
        assertEquals("1s", everyTalkLoadingElapsedText(1_999L))
        assertEquals("6s", everyTalkLoadingElapsedText(6_000L))
    }

    @Test
    fun `loading indicator stays frame clock rendered`() {
        val source = findProjectFile(
            "src/main/java/com/android/everytalk/ui/components/loading/EveryTalkLoadingIndicator.kt",
        ).readText(Charsets.UTF_8)

        assertTrue("加载动画必须由 Compose 实时绘制", source.contains("CircularProgressIndicator("))
        assertFalse("加载动画不能使用固定帧图片", source.contains("AsyncImage("))
        assertFalse(
            "加载动画不能保留固定帧 GIF",
            findProjectFile("src/main/res").resolve("drawable-nodpi/everytalk_loading_spinner_light.gif").exists(),
        )
        assertFalse(
            "加载动画不能保留固定帧 GIF",
            findProjectFile("src/main/res").resolve("drawable-nodpi/everytalk_loading_spinner_dark.gif").exists(),
        )
    }

    private fun findProjectFile(relativePath: String): File = listOf(
        File(relativePath),
        File("app/$relativePath"),
        File("app1/app/$relativePath"),
    ).firstOrNull(File::exists) ?: error("找不到项目文件：$relativePath")
}
