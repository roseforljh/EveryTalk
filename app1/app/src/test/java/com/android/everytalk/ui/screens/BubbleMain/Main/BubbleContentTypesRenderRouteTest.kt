package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BubbleContentTypesRenderRouteTest {

    @Test
    fun `bubble content should not call enhanced markdown text directly`() {
        val source = bubbleContentTypesSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
    }

    @Test
    fun `multiple image attachments use visible bounds instead of fixed square whitespace`() {
        val source = bubbleContentTypesSource()

        assertEquals(
            AttachmentThumbnailSize(widthDp = 45f, heightDp = 100f),
            fitAttachmentThumbnail(sourceWidth = 1080f, sourceHeight = 2400f),
        )
        assertEquals(
            AttachmentThumbnailSize(widthDp = 100f, heightDp = 100f),
            fitAttachmentThumbnail(sourceWidth = 1000f, sourceHeight = 1000f),
        )
        assertEquals(
            AttachmentThumbnailSize(widthDp = 160f, heightDp = 80f),
            fitAttachmentThumbnail(sourceWidth = 2000f, sourceHeight = 1000f),
        )
        assertEquals(
            AttachmentThumbnailSize(widthDp = 100f, heightDp = 100f),
            fitAttachmentThumbnail(sourceWidth = Float.NaN, sourceHeight = 1000f),
        )
        assertEquals(Color.White.copy(alpha = 0.45f), attachmentImageBorderColor(Color.White))
        assertEquals(Color.Black.copy(alpha = 0.45f), attachmentImageBorderColor(Color.Black))
        assertNotEquals(Color.Black, attachmentImageBorderColor(Color.White))
        assertNotEquals(Color.White, attachmentImageBorderColor(Color.Black))
        assertTrue(
            contrastRatio(
                attachmentImageBorderColor(Color.White).compositeOver(Color.Black),
                Color.Black,
            ) >= 3f
        )
        assertTrue(
            contrastRatio(
                attachmentImageBorderColor(Color.Black).compositeOver(Color.White),
                Color.White,
            ) >= 3f
        )
        assertTrue(source.contains("Arrangement.spacedBy(2.dp"))
        assertTrue(source.contains("attachmentImageBorderColor(MaterialTheme.colorScheme.onSurface)"))
        assertTrue(source.contains(".border(1.dp, imageBorderColor, imageShape)"))
        assertTrue(source.contains(".clip(imageShape)"))
        assertTrue(source.contains(".width(thumbnailSize.widthDp.dp)"))
        assertTrue(source.contains(".height(thumbnailSize.heightDp.dp)"))
        assertFalse(source.contains(".size(imageStripHeight)"))
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun bubbleContentTypesSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 BubbleContentTypes.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
