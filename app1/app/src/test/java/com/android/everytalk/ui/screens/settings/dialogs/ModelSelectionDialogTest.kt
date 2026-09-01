package com.android.everytalk.ui.screens.settings.dialogs

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionDialogTest {
    @Test
    fun `remote and local models are split into new added and removed tabs`() {
        val groups = classifyModelCatalog(
            remoteModels = listOf("model-new", "model-kept", "MODEL-KEPT"),
            existingModels = listOf("model-kept", "model-removed"),
        )

        assertEquals(listOf("model-new"), groups.newModels)
        assertEquals(listOf("model-kept"), groups.addedModels)
        assertEquals(listOf("model-removed"), groups.removedModels)
    }

    @Test
    fun `switching tabs keeps new and removed selections independently`() {
        var selections = emptyMap<ModelCatalogTab, Set<String>>()

        selections = updateModelCatalogTabSelection(
            selections,
            ModelCatalogTab.NEW,
            setOf("model-new"),
        )
        selections = updateModelCatalogTabSelection(
            selections,
            ModelCatalogTab.REMOVED,
            setOf("model-removed"),
        )

        assertEquals(setOf("model-new"), selections[ModelCatalogTab.NEW])
        assertEquals(setOf("model-removed"), selections[ModelCatalogTab.REMOVED])
    }
}
