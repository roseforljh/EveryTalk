package com.android.everytalk.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecordingDurationLimitTest {

    @Test
    fun `recording duration limit triggers exactly at boundary`() {
        assertFalse(
            hasReachedRecordingDurationLimit(
                startedAtMs = 1_000,
                nowMs = 300_999,
                maxDurationMs = 300_000,
            )
        )
        assertTrue(
            hasReachedRecordingDurationLimit(
                startedAtMs = 1_000,
                nowMs = 301_000,
                maxDurationMs = 300_000,
            )
        )
    }
}
