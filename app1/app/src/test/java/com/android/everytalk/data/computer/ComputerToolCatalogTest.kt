package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerToolCatalogTest {
    @Test
    fun `所有工具名满足OpenAI函数名约束`() {
        val pattern = Regex("[a-zA-Z0-9_-]+")
        val names = (ComputerToolCatalog.definitions() + ComputerToolCatalog.cloudflareDefinitions()).map { definition ->
            (definition["function"] as Map<*, *>)["name"] as String
        }

        names.forEach { assertTrue("工具名不合法: $it", pattern.matches(it)) }
    }

    @Test
    fun `SMART模式下CF写工具带ask_user_approval而只读工具不带`() {
        fun withParam(mode: ComputerPermissionMode): Set<String> =
            ComputerToolCatalog.cloudflareDefinitions(permissionMode = mode).mapNotNull { definition ->
                val function = definition["function"] as Map<*, *>
                val parameters = function["parameters"] as Map<*, *>
                val properties = parameters["properties"] as Map<*, *>
                (function["name"] as String).takeIf { "ask_user_approval" in properties }
            }.toSet()

        assertEquals(emptySet<String>(), withParam(ComputerPermissionMode.MANUAL))
        assertEquals(emptySet<String>(), withParam(ComputerPermissionMode.FULL))

        val smart = withParam(ComputerPermissionMode.SMART)
        listOf(
            ComputerToolNames.WORKER_CREATE, ComputerToolNames.WORKER_UPDATE, ComputerToolNames.WORKER_DEPLOY,
            ComputerToolNames.WORKER_DELETE, ComputerToolNames.KV_PUT, ComputerToolNames.KV_DELETE,
            ComputerToolNames.R2_UPLOAD, ComputerToolNames.R2_DELETE, ComputerToolNames.D1_MIGRATION,
            ComputerToolNames.D1_QUERY, ComputerToolNames.QUEUES_CREATE, ComputerToolNames.QUEUES_DELETE,
            ComputerToolNames.CRON_UPDATE,
        ).forEach { assertTrue("$it 在 SMART 下应带 ask_user_approval", it in smart) }
        listOf(
            ComputerToolNames.WORKER_LIST, "computer_worker_status", ComputerToolNames.WORKER_LOGS,
            ComputerToolNames.WORKER_HEALTH, ComputerToolNames.D1_LIST, ComputerToolNames.KV_GET,
            ComputerToolNames.R2_LIST_BUCKETS, ComputerToolNames.QUEUES_LIST, ComputerToolNames.CRON_LIST,
        ).forEach { assertFalse("$it 是只读工具，不应带 ask_user_approval", it in smart) }
    }

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
