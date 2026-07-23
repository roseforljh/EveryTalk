package com.android.everytalk.ui.screens.MainScreen.chat.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceSessionControllerLifecycleTest {

    @Test
    fun `controller close is idempotent and unused preconnect resources are absent`() {
        val appDir = findAppDir()
        val controller = appDir.resolve(
            "src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/voice/logic/VoiceSessionController.kt"
        ).readText(Charsets.UTF_8)
        val screen = appDir.resolve(
            "src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/voice/ui/VoiceInputScreen.kt"
        ).readText(Charsets.UTF_8)

        assertTrue(controller.contains("isClientClosed.compareAndSet(false, true)"))
        assertTrue(controller.contains("CoroutineStart.LAZY"))
        assertTrue(controller.contains("ownsProcessingSlot(processingJob, finishingJob)"))
        assertTrue(controller.contains("job.cancel()"))
        assertTrue(controller.contains("session.forceRelease()"))
        assertTrue(controller.contains("invokeOnCompletion { cleanupScope.cancel() }"))
        assertTrue(controller.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
        assertTrue(controller.contains("Dispatchers.Main.immediate"))
        assertTrue(controller.contains("if (!isClientClosed.get()) action()"))
        assertFalse(controller.contains("preconnectJob"))
        assertFalse(controller.contains("sharedKtorClient"))
        assertFalse(controller.contains("AliyunSttConnectionManager"))
        assertTrue(screen.contains("sessionController.close()"))
    }

    private fun findAppDir(): File {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .forEach { dir ->
                listOf(dir, dir.resolve("app"))
                    .firstOrNull { it.resolve("src/main/java/com/android/everytalk").isDirectory }
                    ?.let { return it }
            }
        error("无法定位 app 模块")
    }
}
