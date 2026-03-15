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
    fun `predefined providers contain openclaw`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val providerManager = ProviderManager(scope)

            assertTrue(providerManager.allProviders.value.contains("OpenClaw"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `predefined providers contain openclaw remote`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val providerManager = ProviderManager(scope)

            assertTrue(providerManager.allProviders.value.contains("OpenClaw Remote"))
            assertEquals("OpenClaw", providerManager.allProviders.value.first())
        } finally {
            scope.cancel()
        }
    }
}
