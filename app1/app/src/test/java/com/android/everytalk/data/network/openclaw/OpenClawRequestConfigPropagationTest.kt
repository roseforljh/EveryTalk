package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.openclaw.OpenClawDeviceIdentityManager
import com.android.everytalk.provider.OpenClawProvider
import io.mockk.every
import io.ktor.client.HttpClient
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawRequestConfigPropagationTest {

    @Test
    fun `request keeps explicit openclaw session id separate from local conversation id`() {
        val request = ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "OpenClaw",
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "token",
            model = "main",
            conversationId = "local-conv-1",
            openClawSessionId = "remote-session-1"
        )

        assertEquals("local-conv-1", request.conversationId)
        assertEquals("remote-session-1", request.openClawSessionId)
        val manager = mockk<OpenClawDeviceIdentityManager>()
        every { manager.getOrCreate() } returns OpenClawDeviceIdentity(
            deviceId = "device-id",
            publicKeyRaw = byteArrayOf(1, 2, 3),
            privateKeyRaw = byteArrayOf(4, 5, 6)
        )
        assertEquals(true, OpenClawProvider(mockk<HttpClient>(relaxed = true), manager).canHandle(request))
    }
}
