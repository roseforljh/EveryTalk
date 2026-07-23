package com.android.everytalk.models

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectedMediaItemBitmapEncodingTest {
    @Test
    fun `bitmap attachment encodes successfully with bounded stream`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val item = SelectedMediaItem.ImageFromBitmap.fromBitmap(bitmap, "bitmap-1")

            assertFalse(item.bitmapData.isBlank())
            assertTrue(item.bitmapData.length < 16 * 1024 * 1024)
        } finally {
            bitmap.recycle()
        }
    }
}
