package com.android.everytalk.data.network.openclaw

import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object OpenClawDeviceAuthSigner {

    fun buildDeviceAuthPayloadV3(
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String,
        nonce: String,
        platform: String,
        deviceFamily: String
    ): String {
        val scopesCsv = scopes.joinToString(",")
        return listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopesCsv,
            signedAtMs.toString(),
            token,
            nonce,
            platform,
            deviceFamily
        ).joinToString("|")
    }

    fun signDevicePayload(
        privateKeyRaw: ByteArray,
        payload: String
    ): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        val bytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(bytes, 0, bytes.size)
        return base64Url(signer.generateSignature())
    }

    fun sha256Hex(input: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    fun base64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
