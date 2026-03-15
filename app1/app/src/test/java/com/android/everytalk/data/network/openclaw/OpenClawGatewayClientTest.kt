package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawGatewayClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `build session key keeps conversation scoped prefix`() {
        val sessionKey = OpenClawGatewayClient.buildSessionKey("conversation-123")

        assertEquals("et:conversation-123", sessionKey)
    }

    @Test
    fun `build session key preserves main session`() {
        val sessionKey = OpenClawGatewayClient.buildSessionKey("main")

        assertEquals("main", sessionKey)
    }

    @Test
    fun `build connect request uses operator role and signed device auth`() {
        val identity = createIdentity()
        val request = OpenClawGatewayClient.buildConnectRequest(
            request = createRequest(),
            challengeNonce = "challenge-nonce",
            identity = identity
        )
        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"method\":\"connect\""))
        assertTrue(encoded.contains("\"role\":\"operator\""))
        assertTrue(encoded.contains("\"token\":\"test-token\""))
        assertTrue(encoded.contains("\"client\":{\"id\":\"openclaw-android\""))
        assertTrue(encoded.contains("\"mode\":\"ui\""))
        assertTrue(encoded.contains("\"scopes\":[\"operator.read\",\"operator.write\",\"operator.admin\"]"))
        assertTrue(encoded.contains("\"id\":\"${identity.deviceId}\""))
        assertTrue(encoded.contains("\"nonce\":\"challenge-nonce\""))
        assertTrue(encoded.contains("\"platform\":\"android\""))
        assertTrue(encoded.contains("\"publicKey\":"))
        assertTrue(encoded.contains("\"signature\":"))
        assertTrue(encoded.contains("\"signedAt\":"))
        assertFalse(encoded.contains("\"name\":"))
    }

    @Test
    fun `device auth payload v3 matches official field order`() {
        val payload = OpenClawDeviceAuthSigner.buildDeviceAuthPayloadV3(
            deviceId = "device-id",
            clientId = "openclaw-android",
            clientMode = "ui",
            role = "operator",
            scopes = listOf("operator.read", "operator.write", "operator.admin"),
            signedAtMs = 123456789L,
            token = "token-abc",
            nonce = "nonce-xyz",
            platform = "android",
            deviceFamily = ""
        )

        assertEquals(
            "v3|device-id|openclaw-android|ui|operator|operator.read,operator.write,operator.admin|123456789|token-abc|nonce-xyz|android|",
            payload
        )
    }

    @Test
    fun `queue frames waits until hello then flushes in order`() {
        val queue = OpenClawGatewayClient.PendingFrameQueue()

        queue.enqueue("history")
        queue.enqueue("send")

        assertEquals(listOf("history", "send"), queue.drain())
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `build chat send request contains method and session key`() {
        val request = OpenClawGatewayClient.buildChatSendRequest(
            request = createRequest(),
            messageText = "hello openclaw",
            requestId = "req-1",
            sessionKey = "et:conversation-123",
            idempotencyKey = "msg-1"
        )

        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"type\":\"req\""))
        assertTrue(encoded.contains("\"method\":\"chat.send\""))
        assertTrue(encoded.contains("\"sessionKey\":\"et:conversation-123\""))
        assertTrue(encoded.contains("\"idempotencyKey\":\"msg-1\""))
        assertTrue(encoded.contains("\"message\":\"hello openclaw\""))
        assertTrue(encoded.contains("\"deliver\":false"))
        assertFalse(encoded.contains("\"text\":"))
        assertFalse(encoded.contains("\"agentId\":"))
        assertFalse(encoded.contains("\"role\":"))
        assertFalse(encoded.contains("\"content\":"))
    }

    @Test
    fun `build history request targets session key`() {
        val request = OpenClawGatewayClient.buildHistoryRequest("main")
        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"type\":\"req\""))
        assertTrue(encoded.contains("\"method\":\"chat.history\""))
        assertTrue(encoded.contains("\"sessionKey\":\"main\""))
    }

    @Test
    fun `build abort request wraps params with req envelope`() {
        val request = OpenClawGatewayClient.buildAbortRequest("main", "run-1")
        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"type\":\"req\""))
        assertTrue(encoded.contains("\"method\":\"chat.abort\""))
        assertTrue(encoded.contains("\"sessionKey\":\"main\""))
        assertTrue(encoded.contains("\"runId\":\"run-1\""))
    }

    @Test
    fun `remote provider connect request declares operator role with protocol metadata`() {
        val identity = createIdentity()
        val request = OpenClawGatewayClient.buildConnectRequest(
            request = createRemoteRequest(),
            challengeNonce = "challenge-nonce",
            identity = identity
        )
        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"method\":\"connect\""))
        assertTrue(encoded.contains("\"type\":\"req\""))
        assertTrue(encoded.contains("\"role\":\"operator\""))
        assertTrue(encoded.contains("\"minProtocol\":3"))
        assertTrue(encoded.contains("\"maxProtocol\":3"))
        assertTrue(encoded.contains("\"mode\":\"ui\""))
        assertTrue(encoded.contains("\"scopes\":[\"operator.read\",\"operator.write\",\"operator.admin\"]"))
        assertTrue(encoded.contains("\"nonce\":\"challenge-nonce\""))
        assertTrue(encoded.contains("\"token\":\"remote-token\""))
        assertTrue(encoded.contains("\"client\":{\"id\":\"openclaw-android\""))
        assertTrue(encoded.contains("\"platform\":\"android\""))
        assertTrue(encoded.contains("\"publicKey\":"))
        assertTrue(encoded.contains("\"signature\":"))
        assertTrue(encoded.contains("\"signedAt\":"))
        assertFalse(encoded.contains("\"name\":"))
    }

    @Test
    fun `signature changes when nonce changes`() {
        val identity = createIdentity()
        val signatureA = OpenClawDeviceAuthSigner.signDevicePayload(
            privateKeyRaw = identity.privateKeyRaw,
            payload = "v3|a|openclaw-android|ui|operator|operator.read,operator.write,operator.admin|1|token|nonce-a|android|"
        )
        val signatureB = OpenClawDeviceAuthSigner.signDevicePayload(
            privateKeyRaw = identity.privateKeyRaw,
            payload = "v3|a|openclaw-android|ui|operator|operator.read,operator.write,operator.admin|1|token|nonce-b|android|"
        )

        assertNotEquals(signatureA, signatureB)
    }

    private fun createIdentity(): OpenClawGatewayClient.DeviceIdentity {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val privateKey = pair.private as Ed25519PrivateKeyParameters
        val publicKey = pair.public as Ed25519PublicKeyParameters
        return OpenClawGatewayClient.DeviceIdentity(
            deviceId = OpenClawDeviceAuthSigner.sha256Hex(publicKey.encoded),
            publicKeyRaw = publicKey.encoded,
            privateKeyRaw = privateKey.encoded
        )
    }

    private fun createRequest(): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "openclaw",
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "test-token",
            model = "main",
            deviceId = "device-1"
        )
    }

    private fun createRemoteRequest(): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = "OpenClaw Remote",
            channel = "OpenClaw",
            apiAddress = "wss://gateway.example.com",
            apiKey = "remote-token",
            model = "",
            deviceId = "device-1"
        )
    }
}
