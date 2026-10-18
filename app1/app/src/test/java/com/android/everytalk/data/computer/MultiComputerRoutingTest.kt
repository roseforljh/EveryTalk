package com.android.everytalk.data.computer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** 冻结目标只能由可信快照提供，模型不得省略目标或自行指定 Workspace。 */
class MultiComputerRoutingTest {
    private val context = ComputerRequestContext("session", "a", "wa", runId = "run",
        selectedTargets = listOf(
            ComputerTargetBinding("a", "wa", "A"),
            ComputerTargetBinding("b", "wb", "B", ComputerPermissionMode.SMART),
        ), approvedToolCallId = "approved")

    @Test fun `多机必须选择范围内的目标且保留本次审批`() {
        for (arguments in listOf(buildJsonObject {}, buildJsonObject { put("computer_id", "outside") },
            buildJsonObject { put("computer_id", 3) })) {
            assertThrows(IllegalArgumentException::class.java) {
                resolveComputerToolContext(ComputerToolNames.EXEC, arguments, context)
            }
        }
        val target = requireNotNull(resolveComputerToolContext(ComputerToolNames.EXEC, buildJsonObject {
            put("computer_id", "b"); put("workspace_id", "forged")
        }, context))
        assertEquals("wb", target.workspaceId)
        assertEquals("run", target.runId)
        assertEquals("approved", target.approvedToolCallId)
        assertEquals(ComputerPermissionMode.SMART, target.permissionMode)
        assertTrue(target.selectedTargets.isEmpty())
    }

    @Test fun `混合提供商只公开各自能执行的目标`() {
        val mixed = context.copy(selectedTargets = context.selectedTargets.take(1) +
            ComputerTargetBinding("cf", "wc", "Cloudflare", provider = ComputerProvider.CLOUDFLARE))
        val definitions = multiComputerToolDefinitions(mixed, workerWriteEnabled = false)
        fun targetIds(name: String): List<*> {
            val function = definitions.map { it["function"] as Map<*, *> }.single { it["name"] == name }
            val parameters = function["parameters"] as Map<*, *>
            assertTrue("computer_id" in parameters["required"] as List<*>)
            return ((parameters["properties"] as Map<*, *>)["computer_id"] as Map<*, *>)["enum"] as List<*>
        }
        assertEquals(listOf("a"), targetIds(ComputerToolNames.EXEC))
        assertEquals(listOf("cf"), targetIds(ComputerToolNames.WORKER_LIST))
        assertFalse(definitions.any { (it["function"] as Map<*, *>)["name"] == ComputerToolNames.WORKER_CREATE })
    }

    @Test fun `旧单机快照无需新字段也可恢复`() {
        val legacy = Json.decodeFromString<ComputerRequestContext>(
            """{"conversationId":"s","computerId":"a","workspaceId":"wa"}""")
        assertEquals(legacy, resolveComputerToolContext(ComputerToolNames.EXEC, buildJsonObject {}, legacy))
    }
}
