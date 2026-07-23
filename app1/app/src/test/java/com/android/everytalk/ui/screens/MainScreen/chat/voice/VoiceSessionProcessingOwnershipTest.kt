package com.android.everytalk.ui.screens.MainScreen.chat.voice

import com.android.everytalk.ui.screens.MainScreen.chat.voice.logic.ownsProcessingSlot
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionProcessingOwnershipTest {

    @Test
    fun `旧任务不能清空新任务占用的处理槽位`() {
        val activeJob = Job()
        val staleJob = Job()

        try {
            assertTrue(ownsProcessingSlot(activeJob, activeJob))
            assertFalse(ownsProcessingSlot(activeJob, staleJob))
            assertFalse(ownsProcessingSlot(null, staleJob))
        } finally {
            activeJob.cancel()
            staleJob.cancel()
        }
    }
}
