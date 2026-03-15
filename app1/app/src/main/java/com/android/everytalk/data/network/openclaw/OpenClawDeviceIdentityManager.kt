package com.android.everytalk.data.network.openclaw

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

data class OpenClawDeviceIdentity(
    val deviceId: String,
    val publicKeyRaw: ByteArray,
    val privateKeyRaw: ByteArray
) {
    fun publicKeyBase64Url(): String = OpenClawDeviceAuthSigner.base64Url(publicKeyRaw)
}

class OpenClawDeviceIdentityManager(
    context: Context
) {
    private val prefs = context.getSharedPreferences("openclaw_device_identity", Context.MODE_PRIVATE)

    fun getOrCreate(): OpenClawDeviceIdentity {
        val publicKeyStored = prefs.getString("public_key", null)
        val privateKeyStored = prefs.getString("private_key", null)
        if (publicKeyStored != null && privateKeyStored != null) {
            val publicKeyRaw = Base64.decode(publicKeyStored, Base64.NO_WRAP)
            val privateKeyRaw = Base64.decode(privateKeyStored, Base64.NO_WRAP)
            return OpenClawDeviceIdentity(
                deviceId = OpenClawDeviceAuthSigner.sha256Hex(publicKeyRaw),
                publicKeyRaw = publicKeyRaw,
                privateKeyRaw = privateKeyRaw
            )
        }

        val generated = generateIdentity()
        prefs.edit()
            .putString("public_key", Base64.encodeToString(generated.publicKeyRaw, Base64.NO_WRAP))
            .putString("private_key", Base64.encodeToString(generated.privateKeyRaw, Base64.NO_WRAP))
            .apply()
        return generated
    }

    private fun generateIdentity(): OpenClawDeviceIdentity {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val privateKey = pair.private as Ed25519PrivateKeyParameters
        val publicKey = pair.public as Ed25519PublicKeyParameters
        val publicKeyRaw = publicKey.encoded
        val privateKeyRaw = privateKey.encoded
        return OpenClawDeviceIdentity(
            deviceId = OpenClawDeviceAuthSigner.sha256Hex(publicKeyRaw),
            publicKeyRaw = publicKeyRaw,
            privateKeyRaw = privateKeyRaw
        )
    }
}
