package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class ComputerSshFoundationTest {
    @Test
    fun `endpoint validator accepts domain ipv4 and ipv6 without url syntax`() {
        assertEquals(
            "vps.example.com",
            ComputerEndpointValidator.validate(" VPS.Example.COM. ", 22, "root").host,
        )
        assertEquals("192.0.2.8", ComputerEndpointValidator.validate("192.0.2.8", 2222).host)
        assertEquals("2001:db8::8", ComputerEndpointValidator.validate("[2001:db8::8]", 22).host)
    }

    @Test
    fun `endpoint validator rejects url userinfo path embedded port and malformed ipv4`() {
        val invalidHosts = listOf(
            "https://vps.example.com",
            "root@vps.example.com",
            "vps.example.com/home",
            "vps.example.com:22",
            "192.0.2.999",
            "vps.example.com\nsecond-host",
        )

        invalidHosts.forEach { host ->
            val failure = runCatching { ComputerEndpointValidator.validate(host, 22) }.exceptionOrNull()
            assertTrue("Host 应被拒绝：$host", failure is ComputerException)
            assertEquals(ComputerErrorCodes.HOST_INVALID, (failure as ComputerException).code)
        }
    }

    @Test
    fun `host key codec emits openssh blob and sha256 fingerprint`() {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

        val encoded = ComputerHostKeyCodec.encode(publicKey)

        assertEquals("ssh-rsa", encoded.algorithm)
        assertTrue(encoded.blob.isNotEmpty())
        assertEquals(encoded.blob.toList(), Base64.getDecoder().decode(encoded.blobBase64).toList())
        assertTrue(encoded.fingerprint.startsWith("SHA256:"))
        assertTrue('=' !in encoded.fingerprint)
    }
}
