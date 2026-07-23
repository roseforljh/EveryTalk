package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProportionalAsyncImageRulesTest {

    @Test
    fun `data uri cache key does not retain image payload`() {
        val payload = "QUJDREVGRw=="
        val dataUri = "data:image/png;base64,$payload"

        val cacheKey = requireNotNull(proportionalImageCacheKey(dataUri))

        assertTrue(cacheKey.startsWith("opaque:"))
        assertFalse(cacheKey.contains(payload))
        assertEquals(cacheKey, proportionalImageCacheKey(dataUri))
    }

    @Test
    fun `image size cache evicts least recently used entry`() {
        val cache = ImageSizeLruCache(maxEntries = 2)
        cache.put("first", 100, 50)
        cache.put("second", 200, 100)
        assertEquals(100 to 50, cache.get("first"))

        cache.put("third", 300, 150)

        assertEquals(2, cache.size())
        assertNull(cache.get("second"))
        assertEquals(100 to 50, cache.get("first"))
        assertEquals(300 to 150, cache.get("third"))
    }

    @Test
    fun `composition source does not synchronously inspect image files or base64`() {
        val source = proportionalAsyncImageSource()

        assertFalse(source.contains("openInputStream"))
        assertFalse(source.contains("BitmapFactory.decodeFile"))
        assertFalse(source.contains("Base64.decode"))
        assertTrue(source.contains("remember(model, cacheKey, preserveAspectRatio)"))
    }

    private fun proportionalAsyncImageSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/components/image/ProportionalAsyncImage.kt"),
            File("app/src/main/java/com/android/everytalk/ui/components/image/ProportionalAsyncImage.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/components/image/ProportionalAsyncImage.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ProportionalAsyncImage.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
