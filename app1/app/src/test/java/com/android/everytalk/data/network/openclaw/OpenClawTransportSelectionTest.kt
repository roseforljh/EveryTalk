package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.openclaw.OpenClawDeviceIdentityManager
import com.android.everytalk.provider.OpenClawProvider
import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawTransportSelectionTest {

    private lateinit var provider: TestableOpenClawProvider
    private val httpClient = mockk<HttpClient>(relaxed = true)
    private val identityManager = mockk<OpenClawDeviceIdentityManager>()

    @Before
    fun setup() {
        every { identityManager.getOrCreate() } returns OpenClawDeviceIdentity(
            deviceId = "device-id",
            publicKeyRaw = byteArrayOf(1, 2, 3),
            privateKeyRaw = byteArrayOf(4, 5, 6)
        )
        provider = TestableOpenClawProvider(httpClient, identityManager)
    }

    @Test
    fun `openclaw always selects gateway transport`() {
        val transport = provider.resolveTransportForTest(
            ChatRequest(
                messages = emptyList(),
                provider = "openclaw",
                channel = "openclaw",
                apiAddress = "ws://gateway.local:18789",
                apiKey = "token",
                model = "main",
                deviceId = "device-1"
            )
        )

        assertTrue(transport is OpenClawGatewayClient)
    }

    private class TestableOpenClawProvider(
        httpClient: HttpClient,
        deviceIdentityManager: OpenClawDeviceIdentityManager
    ) : OpenClawProvider(httpClient, deviceIdentityManager) {
        fun resolveTransportForTest(request: ChatRequest): OpenClawChatTransport = resolveTransport(request)
    }
}
