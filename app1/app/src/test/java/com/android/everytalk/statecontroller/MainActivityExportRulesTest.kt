package com.android.everytalk.statecontroller

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityExportRulesTest {

    @Test
    fun `export callback consumes pending content and writes on io dispatcher`() {
        val source = mainActivitySource().readText(Charsets.UTF_8)
        val callback = source.substringAfter("private val createDocument")
            .substringBefore("override fun onCreate")
        assertTrue(callback.contains("val content = fileContentToSave.also { fileContentToSave = null }"))
        assertTrue(callback.contains("lifecycleScope.launch(Dispatchers.IO)"))
        assertTrue(callback.contains("content.toByteArray(Charsets.UTF_8)"))
    }

    private fun mainActivitySource(): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/statecontroller/MainActivity.kt"),
            File("app/src/main/java/com/android/everytalk/statecontroller/MainActivity.kt"),
            File("app1/app/src/main/java/com/android/everytalk/statecontroller/MainActivity.kt"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 MainActivity.kt" }
    }
}
