package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.MessageToolIds
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerToolNames
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderComputerToolTest {
    @Test
    fun `手机文件能力保留但所有入口只在用户明确要求时使用`() {
        val policy = com.android.everytalk.data.computer.ComputerToolCatalog.PHONE_FILE_POLICY
        val local = com.android.everytalk.statecontroller.viewmodel.prepareLocalComputerRequest("chat")
        assertTrue(local.environmentPrompt.contains(policy))
        val tools = appendLocalBashTool(emptyList(), enabled = true)
        assertEquals(setOf("local_bash", ComputerToolNames.LOCAL_FILE_SAVE), tools.mapNotNull(::extractToolName).toSet())
        tools.forEach { tool ->
            assertTrue((tool["function"] as Map<*, *>)["description"].toString().contains(policy))
        }
        val download = com.android.everytalk.data.computer.ComputerToolCatalog.definitions()
            .first { extractToolName(it) == ComputerToolNames.DOWNLOAD }
        assertTrue((download["function"] as Map<*, *>)["description"].toString().contains(policy))
    }

    @Test
    fun `agent toggle adds all seven tools and message snapshot marker`() {
        val tools = appendComputerTools(emptyList(), enabled = true)
        val names = tools.mapNotNull(::extractToolName).toSet()

        assertEquals(ComputerToolNames.all, names)
        assertEquals(
            listOf(MessageToolIds.AGENT),
            enabledMessageToolIdsForRequest(
                isImageGeneration = false,
                webSearchEnabled = false,
                mcpEnabled = false,
                agentEnabled = true,
            ),
        )
    }

    @Test
    fun `本地 Agent 仍提供服务器申请而远端请求收起申请`() {
        val local = com.android.everytalk.statecontroller.viewmodel.prepareLocalComputerRequest("chat")
        for ((request, expected) in listOf(null to true, local to true, local.copy(localOnly = false) to false)) {
            val names = appendAgentRequestTool(emptyList(), request).mapNotNull(::extractToolName)
            assertEquals(expected, "request_agent" in names)
            assertTrue("request_capability" in names)
        }
    }

    @Test
    fun `agent rejects a custom tool that shadows a computer tool`() {
        val conflicting = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf("name" to ComputerToolNames.EXEC),
            ),
        )

        val error = runCatching { appendComputerTools(conflicting, enabled = true) }.exceptionOrNull()

        assertTrue(error is ComputerException)
    }

    @Test
    fun `服务器准备失败不会取消发送协程`() = runTest {
        val preparation = async {
            captureComputerPreparation {
                throw ComputerException("TEST_FAILURE", "服务器准备失败")
            }
        }

        val result = preparation.await()

        assertTrue(result.exceptionOrNull() is ComputerException)
        assertTrue("发送协程应能继续执行错误收尾", isActive)
    }
}
