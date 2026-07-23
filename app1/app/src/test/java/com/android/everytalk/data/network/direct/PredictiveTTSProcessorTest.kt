package com.android.everytalk.data.network.direct

import android.app.Application
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PredictiveTTSProcessorTest {

    @Test
    fun `空文本任务不会阻塞后续音频输出`() = runTest {
        val expected = "后续音频".encodeToByteArray()
        val processor = PredictiveTTSProcessor(
            ttsExecutor = { text -> flowOf(text.encodeToByteArray()) },
            taskTimeout = 1_000,
            firstTaskTimeout = 1_000,
        )

        try {
            processor.submitTask(0, "   ")
            processor.submitTask(1, "后续音频")
            processor.markInputComplete()

            val chunks = withTimeout(2_000) { processor.yieldAudioInOrder().toList() }

            assertEquals(1, chunks.size)
            assertArrayEquals(expected, chunks.single())
        } finally {
            processor.cleanup()
        }
    }
}
