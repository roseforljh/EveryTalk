package com.android.everytalk.provider

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.openclaw.OpenClawDeviceIdentity
import com.android.everytalk.data.network.openclaw.OpenClawDeviceIdentityManager
import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawProviderSelectionTest {

    private lateinit var openClawProvider: OpenClawProvider
    private lateinit var openAIProvider: OpenAICompatibleProvider
    private val mockHttpClient = mockk<HttpClient>(relaxed = true)
    private val identityManager = mockk<OpenClawDeviceIdentityManager>()

    @Before
    fun setup() {
        every { identityManager.getOrCreate() } returns OpenClawDeviceIdentity(
            deviceId = "device-id",
            publicKeyRaw = byteArrayOf(1, 2, 3),
            privateKeyRaw = byteArrayOf(4, 5, 6)
        )
        openClawProvider = OpenClawProvider(mockHttpClient, identityManager)
        openAIProvider = OpenAICompatibleProvider(mockHttpClient)
    }

    @Test
    fun `openclaw provider handles openclaw channel`() {
        val request = createRequest(channel = "openclaw", provider = "default")

        assertTrue(openClawProvider.canHandle(request))
        assertFalse(openAIProvider.canHandle(request))
    }

    @Test
    fun `openclaw provider handles openclaw provider name`() {
        val request = createRequest(channel = "custom", provider = "openclaw")

        assertTrue(openClawProvider.canHandle(request))
        assertFalse(openAIProvider.canHandle(request))
    }

    @Test
    fun `openclaw provider handles remote provider name`() {
        val request = createRequest(channel = "custom", provider = "OpenClaw Remote", model = "")

        assertTrue(openClawProvider.canHandle(request))
        assertFalse(openAIProvider.canHandle(request))
    }

    @Test
    fun `provider registry selects openclaw provider for remote provider name`() {
        val request = createRequest(channel = "OpenClaw", provider = "OpenClaw Remote", model = "")

        assertTrue(GeminiProvider(mockHttpClient).canHandle(request).not())
        assertTrue(OpenClawProvider(mockHttpClient, identityManager).canHandle(request))
        assertTrue(OpenAICompatibleProvider(mockHttpClient).canHandle(request).not())
        assertEquals("OpenClaw", openClawProvider.providerName)
    }

    private fun createRequest(
        channel: String,
        provider: String,
        model: String = "openclaw-default"
    ): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = provider,
            channel = channel,
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "test-token",
            model = model,
            deviceId = "test-device"
        )
    }
}
