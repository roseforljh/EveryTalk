package com.android.everytalk.data.computer

import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom
import java.util.Base64

private const val AUTHORIZED_KEYS_TIMEOUT_MILLIS = 30_000L

internal data class ComputerDedicatedKey(
    val credential: ComputerCredential.PrivateKey,
    val authorizedKeyLine: String,
)

/** 每台 Computer 独立生成 Ed25519 Key，不复用用户已有私钥。 */
internal object ComputerDedicatedKeyGenerator {
    fun generate(computerId: String): ComputerDedicatedKey {
        ComputerIdentifier.requireValid(computerId, "Computer ID")
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val privateSeed = privateKey.encoded
        val publicKey = privateKey.generatePublicKey().encoded
        val publicBuffer = Buffer.PlainBuffer()
            .putString(KEY_TYPE)
            .putString(publicKey)
        val publicBlob = Base64.getEncoder().encodeToString(publicBuffer.compactData)
        return try {
            val privatePem = encodeOpenSshPrivateKey(privateSeed, publicKey, "everytalk:$computerId")
            ComputerDedicatedKey(
                credential = ComputerCredential.PrivateKey(privatePem.toCharArray()),
                authorizedKeyLine = "$KEY_TYPE $publicBlob everytalk:$computerId",
            )
        } finally {
            privateSeed.fill(0)
            publicKey.fill(0)
        }
    }

    private fun encodeOpenSshPrivateKey(privateSeed: ByteArray, publicKey: ByteArray, comment: String): String {
        val check = SecureRandom().nextInt().toLong() and 0xffff_ffffL
        val privateAndPublic = privateSeed + publicKey
        val privateBlock = Buffer.PlainBuffer()
            .putUInt32(check)
            .putUInt32(check)
            .putString(KEY_TYPE)
            .putString(publicKey)
            .putString(privateAndPublic)
            .putString(comment)
        var padding = 1
        while (privateBlock.wpos() % OPENSSH_BLOCK_SIZE != 0) {
            privateBlock.putByte(padding.toByte())
            padding += 1
        }

        val publicBuffer = Buffer.PlainBuffer()
            .putString(KEY_TYPE)
            .putString(publicKey)
        val encoded = Buffer.PlainBuffer()
            .putRawBytes(OPENSSH_AUTH_MAGIC)
            .putString("none")
            .putString("none")
            .putString(ByteArray(0))
            .putUInt32(1)
            .putString(publicBuffer.compactData)
            .putString(privateBlock.compactData)
            .compactData
        privateAndPublic.fill(0)
        privateBlock.array().fill(0)

        return try {
            val body = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(encoded)
            "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
        } finally {
            encoded.fill(0)
        }
    }

    private const val KEY_TYPE = "ssh-ed25519"
    private const val OPENSSH_BLOCK_SIZE = 8
    private val OPENSSH_AUTH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
}

/** 管理远端 authorized_keys 更新和第二连接验证。 */
internal class ComputerDedicatedKeyManager(private val sshClient: ComputerSshClient) {
    suspend fun installAndVerify(
        computer: Computer,
        authenticatedConnection: ComputerSshConnection,
    ): ComputerDedicatedKey {
        val dedicatedKey = ComputerDedicatedKeyGenerator.generate(computer.id)
        appendAuthorizedKey(authenticatedConnection, dedicatedKey.authorizedKeyLine)
        try {
            val verificationCredential = ComputerCredential.PrivateKey(
                dedicatedKey.credential.privateKey.copyOf(),
                dedicatedKey.credential.passphrase?.copyOf(),
            )
            sshClient.connect(computer, verificationCredential).use { verified ->
                check(verified.isUsable) { "专用 SSH Key 验证连接不可用" }
            }
            return dedicatedKey
        } catch (error: Throwable) {
            runCatching { removeAuthorizedKey(authenticatedConnection, dedicatedKey.authorizedKeyLine) }
            dedicatedKey.credential.clear()
            if (error is CancellationException) throw error
            throw ComputerException(
                ComputerErrorCodes.AUTH_FAILED,
                "服务器专用 SSH Key 验证失败",
                retryable = true,
                cause = error,
            )
        }
    }

    suspend fun rollback(connection: ComputerSshConnection, authorizedKeyLine: String) {
        removeAuthorizedKey(connection, authorizedKeyLine)
    }

    private suspend fun appendAuthorizedKey(connection: ComputerSshConnection, keyLine: String) {
        val input = "$keyLine\n".toByteArray()
        try {
            val result = connection.execute(
                command = APPEND_AUTHORIZED_KEY_COMMAND,
                stdin = input,
                timeoutMillis = AUTHORIZED_KEYS_TIMEOUT_MILLIS,
                maxOutputBytes = 64 * 1024,
            )
            if (result.timedOut || result.exitCode != 0) {
                throw ComputerException(
                    ComputerErrorCodes.AUTH_FAILED,
                    "无法安装服务器专用 SSH Key",
                    retryable = true,
                )
            }
        } finally {
            input.fill(0)
        }
    }

    private suspend fun removeAuthorizedKey(connection: ComputerSshConnection, keyLine: String) {
        val input = "$keyLine\n".toByteArray()
        try {
            connection.execute(
                command = REMOVE_AUTHORIZED_KEY_COMMAND,
                stdin = input,
                timeoutMillis = AUTHORIZED_KEYS_TIMEOUT_MILLIS,
                maxOutputBytes = 64 * 1024,
            )
        } finally {
            input.fill(0)
        }
    }
}

private val APPEND_AUTHORIZED_KEY_COMMAND = """
    set -eu
    umask 077
    ssh_dir="${'$'}HOME/.ssh"
    key_file="${'$'}ssh_dir/authorized_keys"
    mkdir -p "${'$'}ssh_dir"
    chmod 700 "${'$'}ssh_dir"
    touch "${'$'}key_file"
    chmod 600 "${'$'}key_file"
    IFS= read -r everytalk_key
    temporary="${'$'}key_file.everytalk.${'$'}${'$'}"
    status=0
    grep -Fvx -- "${'$'}everytalk_key" "${'$'}key_file" > "${'$'}temporary" || status=${'$'}?
    if [ "${'$'}status" -gt 1 ]; then rm -f "${'$'}temporary"; exit "${'$'}status"; fi
    printf '%s\n' "${'$'}everytalk_key" >> "${'$'}temporary"
    chmod 600 "${'$'}temporary"
    mv -f "${'$'}temporary" "${'$'}key_file"
""".trimIndent()

private val REMOVE_AUTHORIZED_KEY_COMMAND = """
    set -eu
    umask 077
    key_file="${'$'}HOME/.ssh/authorized_keys"
    [ -f "${'$'}key_file" ] || exit 0
    IFS= read -r everytalk_key
    temporary="${'$'}key_file.everytalk.${'$'}${'$'}"
    status=0
    grep -Fvx -- "${'$'}everytalk_key" "${'$'}key_file" > "${'$'}temporary" || status=${'$'}?
    if [ "${'$'}status" -gt 1 ]; then rm -f "${'$'}temporary"; exit "${'$'}status"; fi
    chmod 600 "${'$'}temporary"
    mv -f "${'$'}temporary" "${'$'}key_file"
""".trimIndent()
