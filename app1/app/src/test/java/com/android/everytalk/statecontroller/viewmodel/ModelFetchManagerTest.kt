package com.android.everytalk.statecontroller.viewmodel

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
}
