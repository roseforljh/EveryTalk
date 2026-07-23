package com.android.everytalk.ui.screens.MainScreen.chat.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceAudioReleaseRulesTest {

    @Test
    fun `controller only signals playback stop on caller thread`() {
        val source = sourceFile(
            "ui/screens/MainScreen/chat/voice/logic/VoiceSessionController.kt"
        ).readText(Charsets.UTF_8)
        val stopPlayback = source.substringAfter("fun stopPlayback()")
            .substringBefore("fun cancel()")

        assertTrue(stopPlayback.contains("session?.requestStopPlayback()"))
        assertTrue(stopPlayback.contains("cleanupInBackground"))
        assertTrue(source.contains("withContext(NonCancellable + Dispatchers.IO)"))
    }

    @Test
    fun `stream audio close always releases track in finally`() {
        val source = sourceFile("data/network/VoiceChatSession.kt").readText(Charsets.UTF_8)
        val close = source.substringAfter("fun close()")
            .substringBefore("private fun resample")
        val finallyBlock = close.substringAfter("finally")

        assertTrue(finallyBlock.contains("track.release()"))
        assertTrue(source.contains("withContext(NonCancellable + Dispatchers.IO)"))
    }

    private fun sourceFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 $relativePath" }
    }
}
