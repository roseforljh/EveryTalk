package com.android.everytalk.data.network.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceChatDirectSessionMemoryContractTest {

    @Test
    fun `streamed audio is not retained for the whole session`() {
        val source = sourceFile(
            "data/network/direct/VoiceChatDirectSession.kt"
        ).readText(Charsets.UTF_8)

        assertFalse(source.contains("mutableListOf<ByteArray>()"))
        assertFalse(source.contains("audioChunks.add"))
        assertFalse(source.contains("mergeAudioChunks"))
        assertEquals(2, Regex("hasAudio = true").findAll(source).count())
        assertTrue(source.contains("val hasAudio: Boolean"))
    }

    @Test
    fun `voice result only carries the audio production flag`() {
        val source = sourceFile("data/network/direct/VoiceChatSession.kt").readText(Charsets.UTF_8)
        val result = source.substringAfter("data class VoiceChatResult(")

        assertTrue(result.contains("val hasAudio: Boolean"))
        assertFalse(result.contains("val audioData: ByteArray"))
        assertFalse(source.contains("private suspend fun playAudio"))
        assertFalse(source.contains("currentAudioTrack"))
        assertFalse(source.contains("ByteArrayOutputStream(44 + pcmData.size)"))
        assertTrue(source.contains("val wavData = ByteArray(44 + pcmData.size)"))
    }

    @Test
    fun `voice path does not use automatic prompt routing`() {
        val source = sourceFile("data/network/direct/VoiceChatDirectSession.kt").readText(Charsets.UTF_8)

        assertFalse(source.contains("PromptDirectiveBuilder"))
        assertFalse(source.contains("SystemPromptRouter"))
        assertFalse(source.contains("[ETD v="))
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
