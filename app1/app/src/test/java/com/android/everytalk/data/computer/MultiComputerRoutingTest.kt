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

    @Test fun `多机全权限目录保留直接执行说明和服务器名称映射`() {
        val full = context.copy(selectedTargets = context.selectedTargets.map { it.copy(permissionMode = ComputerPermissionMode.FULL) })
        val exec = multiComputerToolDefinitions(full).map { it["function"] as Map<*, *> }
            .single { it["name"] == ComputerToolNames.EXEC }
        assertTrue((exec["description"] as String).contains("without an approval prompt"))
        val properties = (exec["parameters"] as Map<*, *>)["properties"] as Map<*, *>
        val target = properties["computer_id"] as Map<*, *>
        assertEquals(listOf("a", "b"), target["enum"])
        assertTrue((target["description"] as String).contains("a=\"A\""))
        assertTrue((target["description"] as String).contains("b=\"B\""))
        assertFalse("ask_user_approval" in properties)
    }

    @Test fun `首次发送和每轮刷新均在状态块之前提供全部环境`() {
        val prompts = context.selectedTargets.map { target -> target to
            "${target.workspaceId} environment\n<computer-session-state>${target.computerId} running</computer-session-state>" }
        val prompt = buildMultiComputerSessionPrompt(prompts)
        val environment = prompt.substringBefore("<computer-session-state>")
        val states = prompt.substringAfter("<computer-session-state>")
        assertTrue(environment.contains("wa environment"))
        assertTrue(environment.contains("wb environment"))
        assertTrue(environment.contains("无需切换会话"))
        assertTrue(environment.contains("attachment_id"))
        assertTrue(states.contains("a running"))
        assertTrue(states.contains("b running"))
        assertEquals(1, Regex("<computer-session-state>").findAll(prompt).count())
        val initial = buildMultiComputerSessionPrompt(prompts.map { (target, text) ->
            target to text.substringBefore("<computer-session-state>").trim()
        })
        assertEquals(initial.trim(), environment.trim())
    }
}
