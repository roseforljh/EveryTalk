package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.MessageToolIds
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderComputerToolTest {
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
}
