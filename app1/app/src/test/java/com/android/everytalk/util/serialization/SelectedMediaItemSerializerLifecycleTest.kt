package com.android.everytalk.util.serialization

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedMediaItemSerializerLifecycleTest {
    @Test
    fun `bitmap serializer releases temporary decoded bitmap`() {
        val source = listOf(
            File("src/main/java/com/android/everytalk/util/serialization/SelectedMediaItemSerializer.kt"),
            File("app/src/main/java/com/android/everytalk/util/serialization/SelectedMediaItemSerializer.kt"),
            File("app1/app/src/main/java/com/android/everytalk/util/serialization/SelectedMediaItemSerializer.kt"),
        ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("找不到 SelectedMediaItemSerializer.kt")

        assertTrue(source.contains("finally"))
        assertTrue(source.contains("isRecycled"))
        assertTrue(source.contains("recycle()"))
    }
}
