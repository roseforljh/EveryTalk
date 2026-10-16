package com.android.everytalk.data.sync

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.*

/** 合同 openapi.yaml 中的跨实现 Argon2id、AAD 和 AES-GCM 固定向量。 */
class SyncCryptoTest {
    @Test fun `实际字段信封保留默认协议字段并通过名称扫描`() {
        val dek = ByteArray(32) { it.toByte() }
        val aad = SyncCrypto.aad("account", "api_configs", "config", "/apiKey", 1, 0)
        val value = JsonPrimitive("test-only")
        val envelope = SyncCrypto.encryptJson(dek, value, aad, 1)
        assertEquals(setOf("\$enc", "alg", "nonce", "ct", "keyVersion"), envelope.keys)
        SyncContractSanitizer.validate(buildJsonObject { put("apiKey", envelope) })
        assertEquals(value, SyncCrypto.decryptJson(dek, syncJson.decodeFromJsonElement(SyncEncryptedEnvelope.serializer(), envelope), aad))
    }
    @Test
    fun `合同 Argon2id 向量`() {
        val result = SyncCrypto.deriveKek(
            "correct horse battery staple".toCharArray(),
            hex("00112233445566778899aabbccddeeff"),
            SyncKdfParams(19_456, 2, 1, 32),
        )
        assertEquals(
            "ccd958f3ead5af13ebc914232f7d0627baf8b4e8192ec2ba815443316cbf703d",
            result.toHex(),
        )
    }

    @Test
    fun `合同 AES GCM 普通信封和 AAD 向量`() {
        val dek = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val nonce = hex("202122232425262728292a2b")
        val aad = SyncCrypto.aad(
            "00000000-0000-4000-8000-000000000001",
            "api_configs",
            "80000000-0000-4000-8000-000000000099",
            "/apiKey",
            1,
            0,
        )
        assertEquals(
            "5b22657665727974616c6b2d73796e63222c22302e322e30222c2230303030303030302d303030302d343030302d383030302d303030303030303030303031222c226170695f636f6e66696773222c2238303030303030302d303030302d343030302d383030302d303030303030303030303939222c222f6170694b6579222c312c305d",
            aad.toHex(),
        )
        val envelope = SyncCrypto.encrypt(dek, "sk-example-secret".toByteArray(), aad, 1, nonce)
        assertEquals("oVGLFRT5d352GW+9pHuGnKTs/PPb565wb2+d0Qq1O8wC", envelope.ct)
        assertArrayEquals("sk-example-secret".toByteArray(), SyncCrypto.decrypt(dek, envelope, aad))
    }

    @Test
    fun `合同 wrapped DEK 向量和篡改保护`() {
        val kek = hex("ccd958f3ead5af13ebc914232f7d0627baf8b4e8192ec2ba815443316cbf703d")
        val dek = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val envelope = SyncCrypto.wrapDek(kek, dek, 1, hex("303132333435363738393a3b"))
        assertEquals("VazexjuXi6lYoTwIRJlwZdJ2H4DU5anGqlnD2sp2jL3DCSpcc604JMPy1dmdia+n", envelope.ct)
        assertArrayEquals(dek, SyncCrypto.unwrapDek(kek, envelope))
        assertThrows(Exception::class.java) {
            SyncCrypto.unwrapDek(kek, envelope.copy(ct = envelope.ct.dropLast(2) + "aa"))
        }
    }

    @Test
    fun `AAD 修改后不能解密`() {
        val dek = ByteArray(32) { it.toByte() }
        val envelope = SyncCrypto.encrypt(dek, "value".toByteArray(), "a".toByteArray(), 1, ByteArray(12))
        assertThrows(Exception::class.java) {
            SyncCrypto.decrypt(dek, envelope, "b".toByteArray())
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
