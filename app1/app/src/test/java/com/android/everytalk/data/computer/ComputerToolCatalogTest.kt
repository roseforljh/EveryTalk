package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ComputerToolCatalogTest {
    @Test
    fun `catalog exposes exactly seven unique tools without computer identity fields`() {
        val definitions = ComputerToolCatalog.definitions()
        val names = definitions.map { definition ->
            val function = definition["function"] as Map<*, *>
            function["name"] as String
        }

        assertEquals(7, definitions.size)
        assertEquals(ComputerToolNames.all, names.toSet())
        assertEquals(names.size, names.distinct().size)
        val schemaText = definitions.toString().lowercase()
        assertFalse("computer_id" in schemaText)
        assertFalse("workspace_id" in schemaText)
        assertFalse("host_key" in schemaText)
    }
}
