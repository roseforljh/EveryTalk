package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerToolCatalogTest {
    @Test
    fun `连续刷新请求仍保留两台服务器的身份和跨机工具`() {
        val targets = listOf(
            ComputerTargetBinding("a", "wa", "24", ComputerPermissionMode.FULL),
            ComputerTargetBinding("b", "wb", "34", ComputerPermissionMode.FULL),
        )
        var request = com.android.everytalk.data.DataClass.ChatRequest(
            messages = emptyList(), provider = "OpenAI", channel = "OpenAI",
            apiAddress = "https://example.com", apiKey = "", model = "test",
            tools = ComputerToolCatalog.definitions(),
            localComputerRequestContext = ComputerRequestContext("session", "a", "wa", selectedTargets = targets),
        )
        repeat(3) { round ->
            val prompt = buildMultiComputerSessionPrompt(targets.map { target ->
                target to "environment ${target.workspaceId}\n<computer-session-state>round $round</computer-session-state>"
            })
            request = refreshComputerExecutionPolicy(request, prompt)
            val policy = request.messages.filter { it.id == "computer-execution-policy" }.single()
                as com.android.everytalk.data.DataClass.SimpleTextApiMessage
            assertTrue(policy.content.contains("名称=\"24\""))
            assertTrue(policy.content.contains("名称=\"34\""))
            assertTrue(policy.content.contains("environment wa"))
            assertTrue(policy.content.contains("environment wb"))
            assertFalse(policy.content.contains("round"))
            listOf(ComputerToolNames.EXEC, ComputerToolNames.DOWNLOAD, ComputerToolNames.UPLOAD).forEach { name ->
                val function = request.tools!!.map { it["function"] as Map<*, *> }.single { it["name"] == name }
                val properties = (function["parameters"] as Map<*, *>)["properties"] as Map<*, *>
                assertEquals(listOf("a", "b"), (properties["computer_id"] as Map<*, *>)["enum"])
            }
        }
    }

    @Test
    fun `恢复旧请求时刷新默认工具和system规则但保留用户历史与其他工具`() {
        val user = com.android.everytalk.data.DataClass.SimpleTextApiMessage(role = "user", content = "之前沙箱里有文件，请继续处理")
        val externalTool = mapOf<String, Any>("type" to "function", "function" to mapOf("name" to "external_tool"))
        val legacyExec = mapOf<String, Any>("type" to "function", "function" to mapOf(
            "name" to "exec", "description" to "Defaults to the isolated Container"))
        val legacyEnvironment = "Agent server tools are enabled for this request. The Android app connects to the VPS directly over SSH. " +
            "For exec, use target=container by default. Architecture: x86_64. Available tools: ${ComputerToolNames.all.sorted().joinToString(", ")}."
        val request = com.android.everytalk.data.DataClass.ChatRequest(
            messages = listOf(com.android.everytalk.data.DataClass.SimpleTextApiMessage(
                role = "system", content = "保留用户自定义规则\n\n$legacyEnvironment"), user),
            provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.com", apiKey = "", model = "test",
            tools = listOf(legacyExec, externalTool),
            localComputerRequestContext = ComputerRequestContext("session", "server", "ws_legacy"),
        )
        val environment = "Use exec target=host for normal work. Earlier container defaults do not describe the current environment."
        val refreshed = refreshComputerExecutionPolicy(request, "$environment\n<computer-session-state>任务状态</computer-session-state>")
        assertTrue(refreshed.messages.any { it === user })
        assertEquals(environment, (refreshed.messages.last() as com.android.everytalk.data.DataClass.SimpleTextApiMessage).content)
        assertEquals("system", refreshed.messages.last().role)
        assertTrue(refreshed.tools!!.contains(externalTool))
        val exec = refreshed.tools!!.first { (it["function"] as? Map<*, *>)?.get("name") == "exec" }
        val parameters = (exec["function"] as Map<*, *>)["parameters"] as Map<*, *>
        val target = (parameters["properties"] as Map<*, *>)["target"] as Map<*, *>
        assertEquals("host", target["default"])
        val sent = com.android.everytalk.data.network.SystemPromptInjector.injectSystemPrompt(refreshed.messages)
        val system = (sent.first() as com.android.everytalk.data.DataClass.SimpleTextApiMessage).content
        assertTrue(system.contains(environment))
        assertTrue(system.contains("保留用户自定义规则"))
        assertFalse(system.contains("For exec, use target=container by default"))
        assertEquals(1, refreshComputerExecutionPolicy(refreshed, "$environment\n<computer-session-state></computer-session-state>").messages
            .count { it.id == "computer-execution-policy" })
    }

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
    fun `exec默认使用SSH并允许按次选择容器`() {
        val exec = ComputerToolCatalog.definitions().first { definition ->
            val function = definition["function"] as Map<*, *>
            function["name"] == ComputerToolNames.EXEC
        }
        val function = exec["function"] as Map<*, *>
        val parameters = function["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        val target = properties["target"] as Map<*, *>

        assertEquals(listOf("container", "host"), target["enum"])
        assertEquals("host", target["default"])
        assertTrue((function["description"] as String).contains("specific operation needs isolation"))
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
        assertEquals("host", target["default"])
    }
}
