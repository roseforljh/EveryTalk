package com.android.everytalk.data.computer

import com.android.everytalk.data.DataClass.ChatRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Test

class ComputerRequestPrivacyTest {
    @Test
    fun `local computer context never enters serialized provider request`() {
        val request = ChatRequest(
            messages = emptyList(),
            provider = "provider",
            channel = "OpenAI兼容",
            apiAddress = "https://example.com",
            apiKey = "key",
            model = "model",
            localComputerRequestContext = ComputerRequestContext(
                conversationId = "private-conversation",
                computerId = "private-computer",
                workspaceId = "private-workspace",
            ),
        )

        val encoded = Json.encodeToString(request)

        assertFalse(encoded.contains("localComputerRequestContext"))
        assertFalse(encoded.contains("private-conversation"))
        assertFalse(encoded.contains("private-computer"))
        assertFalse(encoded.contains("private-workspace"))
    }
}
