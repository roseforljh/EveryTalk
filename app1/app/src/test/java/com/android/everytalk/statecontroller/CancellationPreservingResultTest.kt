package com.android.everytalk.statecontroller

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationPreservingResultTest {

    @Test
    fun `cancellation is rethrown`() {
        assertThrows(CancellationException::class.java) {
            runCatchingPreservingCancellation<String> {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `business failures remain result failures`() {
        val result = runCatchingPreservingCancellation<String> {
            error("gateway timeout")
        }

        assertTrue(result.isFailure)
        assertEquals("gateway timeout", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation helper only rethrows cancellation`() {
        IllegalStateException("business error").rethrowIfCancellation()

        assertThrows(CancellationException::class.java) {
            CancellationException("cancelled").rethrowIfCancellation()
        }
    }
}
