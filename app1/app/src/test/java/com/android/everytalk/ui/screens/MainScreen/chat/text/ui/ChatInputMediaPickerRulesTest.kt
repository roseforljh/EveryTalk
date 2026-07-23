package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatInputMediaPickerRulesTest {

    @Test
    fun `photo picker reuses io file details helper`() {
        val source = chatInputSource().readText(Charsets.UTF_8)
        val photoPicker = source.substringAfter("val photoPickerLauncher")
            .substringBefore("val cameraLauncher")

        assertTrue(photoPicker.contains("getFileDetailsFromUri(context, uri)"))
        assertFalse(photoPicker.contains("contentResolver.getType"))
        assertFalse(photoPicker.contains("contentResolver.query"))
        assertTrue(photoPicker.contains("catch (e: CancellationException)"))
    }

    private fun chatInputSource(): File {
        val relativePath = "ui/screens/MainScreen/chat/text/ui/ChatInputArea.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 ChatInputArea.kt" }
    }
}
