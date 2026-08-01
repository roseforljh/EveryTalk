package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderManagerTest {

    @Test
    fun `predefined providers contain anthropic`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val providerManager = ProviderManager(scope)

            assertTrue(providerManager.allProviders.value.contains("Anthropic"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `anthropic is first predefined provider`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val providerManager = ProviderManager(scope)

            assertEquals("Anthropic", providerManager.allProviders.value.first())
        } finally {
            scope.cancel()
        }
    }
}
