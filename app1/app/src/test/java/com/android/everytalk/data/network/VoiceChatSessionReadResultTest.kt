package com.android.everytalk.data.network

import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceChatSessionReadResultTest {

    @Test
    fun `negative audio record results fail fast`() {
        listOf(-1, -2, -3, -6).forEach { errorCode ->
            assertThrows(IllegalStateException::class.java) {
                validateAudioRecordReadResult(errorCode)
            }
        }
    }

    @Test
    fun `zero and positive audio record results are accepted`() {
        validateAudioRecordReadResult(0)
        validateAudioRecordReadResult(3200)
    }
}
