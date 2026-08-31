package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ComputerToolCatalogTest {
    @Test
    fun `catalog exposes exactly eight unique tools without computer identity fields`() {
        val definitions = ComputerToolCatalog.definitions()
        val names = definitions.map { definition ->
            val function = definition["function"] as Map<*, *>
            function["name"] as String
        }

        assertEquals(8, definitions.size)
        assertEquals(ComputerToolNames.all, names.toSet())
        assertEquals(names.size, names.distinct().size)
        val schemaText = definitions.toString().lowercase()
        assertFalse("computer_id" in schemaText)
        assertFalse("workspace_id" in schemaText)
        assertFalse("host_key" in schemaText)
        assertFalse("secret_names" in schemaText)
    }

    @Test
    fun `edit使用pi的多段精准替换协议`() {
        val edit = ComputerToolCatalog.definitions().first { definition ->
            val function = definition["function"] as Map<*, *>
            function["name"] == ComputerToolNames.EDIT
        }
        val function = edit["function"] as Map<*, *>
        val parameters = function["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        val edits = properties["edits"] as Map<*, *>
        val item = edits["items"] as Map<*, *>

        assertEquals(listOf("path", "edits"), parameters["required"])
        assertEquals(listOf("oldText", "newText"), item["required"])
        assertEquals(false, item["additionalProperties"])
    }

    @Test
    fun `exec默认进入容器并允许模型明确选择主机`() {
        val exec = ComputerToolCatalog.definitions().first { definition ->
            val function = definition["function"] as Map<*, *>
            function["name"] == ComputerToolNames.EXEC
        }
        val function = exec["function"] as Map<*, *>
        val parameters = function["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        val target = properties["target"] as Map<*, *>

        assertEquals(listOf("container", "host"), target["enum"])
        assertEquals("container", target["default"])
    }

    @Test
    fun `openPort可以预览容器服务和VPS已有服务`() {
        val openPort = ComputerToolCatalog.definitions().first { definition ->
            val function = definition["function"] as Map<*, *>
            function["name"] == ComputerToolNames.OPEN_PORT
        }
        val function = openPort["function"] as Map<*, *>
        val parameters = function["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        val target = properties["target"] as Map<*, *>

        assertEquals(listOf("container", "host"), target["enum"])
        assertEquals("container", target["default"])
    }
}
