package com.android.everytalk.util.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImageEncodingTest {

    private fun png(width: Int, height: Int, alpha: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (alpha) Color.TRANSPARENT else Color.BLUE)
        bitmap.setHasAlpha(alpha)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun bounds(base64: String) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it)
    }

    @Test
    fun `小图原样发送并识别真实MIME`() {
        val bytes = png(64, 64, alpha = false)
        val encoded = encodeImageBytesForModel(bytes, declaredMimeType = "image/jpeg")
        assertEquals("image/png", encoded.mimeType)
        assertEquals(Base64.encodeToString(bytes, Base64.NO_WRAP), encoded.base64Data)
    }

    @Test
    fun `超长图缩到2048以内且不透明图转JPEG`() {
        val encoded = encodeImageBytesForModel(png(1272, 4800, alpha = false), "image/png")
        val size = bounds(encoded.base64Data)
        assertEquals("image/jpeg", encoded.mimeType)
        assertTrue(maxOf(size.outWidth, size.outHeight) <= 2048)
    }

    @Test
    fun `透明图缩放后保留PNG`() {
        val encoded = encodeImageBytesForModel(png(3000, 3000, alpha = true), "image/png")
        assertEquals("image/png", encoded.mimeType)
        assertTrue(maxOf(bounds(encoded.base64Data).outWidth, bounds(encoded.base64Data).outHeight) <= 2048)
    }
}
