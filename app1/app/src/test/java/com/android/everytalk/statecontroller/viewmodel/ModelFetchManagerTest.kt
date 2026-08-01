package com.android.everytalk.statecontroller.viewmodel

import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFetchManagerTest {

    @Test
    fun `refresh state keeps only the latest request`() {
        val manager = ModelFetchManager()

        manager.setRefreshingModel("first")
        assertEquals(setOf("first"), manager.isRefreshingModels.value)

        manager.setRefreshingModel("second")
        assertEquals(setOf("second"), manager.isRefreshingModels.value)

        manager.setRefreshingModel(null)
        assertTrue(manager.isRefreshingModels.value.isEmpty())
    }

    @Test
    fun `模型目录同时发布名称并保留对应能力`() {
        val manager = ModelFetchManager()
        val candidate = ModelCapabilityCandidate(
            modelId = "model-a",
            protocol = ModelParameterProtocol.GEMINI,
            contextWindowTokens = 1_000_000,
            maxOutputTokens = 64_000,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
        )

        manager.setFetchedCatalog(listOf(candidate))

        assertEquals(listOf("model-a"), manager.fetchedModels.value)
        assertEquals(candidate, manager.capabilityFor("MODEL-A"))
    }
}
