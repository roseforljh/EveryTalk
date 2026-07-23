package com.android.everytalk.ui.screens.ImageGeneration

import com.android.everytalk.ui.screens.BubbleMain.Main.imageContextMenuItemCount
import com.android.everytalk.ui.screens.BubbleMain.Main.imageContextMenuEditLabel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationUiRulesTest {
    @Test
    fun `function panel follows plus button anchor while ime is visible`() {
        val anchoredY = resolveImageFunctionPanelPopupY(
            windowHeightPx = 1200,
            anchorTopPx = 1120,
            inputContentHeightPx = 360,
            popupHeightPx = 300,
            marginPx = 8,
        )
        val oldInputHeightBasedY = resolveImageFunctionPanelPopupY(
            windowHeightPx = 1200,
            anchorTopPx = 0,
            inputContentHeightPx = 360,
            popupHeightPx = 300,
            marginPx = 8,
        )

        assertTrue(anchoredY > oldInputHeightBasedY)
        assertEquals(812, anchoredY)
        assertEquals(300, resolveImageFunctionPanelMaxHeightDp(imeVisible = true))
        assertEquals(370, resolveImageFunctionPanelMaxHeightDp(imeVisible = false))
    }

    @Test
    fun `image context menu includes edit image action`() {
        assertEquals(3, imageContextMenuItemCount(showEditAction = true))
        assertEquals("编辑图像", imageContextMenuEditLabel())
    }

    @Test
    fun `image context edit does not open preview first`() {
        assertEquals(false, imageContextEditUsesPreview())
    }

    @Test
    fun `base64 decoded size gate handles padding whitespace and limit`() {
        assertEquals(1L, estimateImageBase64DecodedBytes("TQ=="))
        assertEquals(2L, estimateImageBase64DecodedBytes("V E\nE="))
        assertTrue(isImageBase64WithinDecodedLimit("QUJD", maxDecodedBytes = 3L))
        assertEquals(false, isImageBase64WithinDecodedLimit("QUJDRA==", maxDecodedBytes = 3L))
    }

    @Test
    fun `base64 data uri streams valid payload and rejects malformed input`() {
        assertArrayEquals(
            "Hello".toByteArray(),
            requireNotNull(decodeImageBase64DataUri("data:image/png;base64,SGV s\nbG8=")),
        )
        assertNull(decodeImageBase64DataUri("data:image/png;base64,%%%"))
        assertNull(decodeImageBase64DataUri("data:image/png,SGVsbG8="))
    }
}
