package com.android.everytalk.statecontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelHistoryLoadTest {

    @Test
    fun `skips reloading currently loaded history item with messages`() {
        val result = shouldSkipReloadingLoadedHistory(
            requestedIndex = 3,
            loadedIndex = 3,
            hasLoadedMessages = true,
        )

        assertTrue(result)
    }

    @Test
    fun `does not skip current history item when messages are empty`() {
        val result = shouldSkipReloadingLoadedHistory(
            requestedIndex = 3,
            loadedIndex = 3,
            hasLoadedMessages = false,
        )

        assertFalse(result)
    }

    @Test
    fun `does not skip different history item`() {
        val result = shouldSkipReloadingLoadedHistory(
            requestedIndex = 4,
            loadedIndex = 3,
            hasLoadedMessages = true,
        )

        assertFalse(result)
    }
}
