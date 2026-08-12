package com.android.everytalk.data.computer

import android.content.Context
import net.schmizz.sshj.sftp.OpenMode
import java.nio.CharBuffer
import java.security.MessageDigest
import java.util.EnumSet

internal const val COMPUTER_BOOTSTRAP_VERSION = "5"
private const val SANDBOX_IMAGE = "everytalk-sandbox:1"
private const val BOOTSTRAP_COMMAND_TIMEOUT_MILLIS = 20 * 60 * 1000L
private const val BOOTSTRAP_OUTPUT_BYTES = 2 * 1024 * 1024

data class ComputerProvisionResult(
    val bootstrapVersion: String,
    val sandboxImage: String,
)

/** 上传 Android 包内固定资产，校验 SHA-256 后在用户 VPS 上配置 Docker Sandbox。 */
class ComputerProvisioner(private val context: Context) {
    private data class Asset(val assetName: String, val remoteName: String, val executable: Boolean)

    private val assets = listOf(
        Asset("computer/install-docker.sh", "install-docker.sh", true),
        Asset("computer/everytalk-containerctl.sh", "everytalk-containerctl.sh", true),
        Asset("computer/runtime-wrapper.sh", "runtime-wrapper.sh", true),
        Asset("computer/Dockerfile", "Dockerfile", false),
    )

    suspend fun provision(
        connection: ComputerSshConnection,
        computer: Computer,
        sudoPassword: CharArray?,
        onProgress: suspend (ComputerSetupStage) -> Unit = {},
    ): ComputerProvisionResult {
        require(computer.runMode == ComputerRunMode.CONTAINER) { "只有 Container 模式需要配置" }
        ComputerIdentifier.requireValid(computer.id, "Computer ID")
        val remoteDirectory = "/tmp/everytalk-bootstrap-${computer.id}"
        try {
            onProgress(ComputerSetupStage.PREPARING_CONTAINER)
            prepareRemoteDirectory(connection, remoteDirectory)
            val hashes = uploadAssets(connection, remoteDirectory)
            verifyAssets(connection, remoteDirectory, hashes)

            // 即使 Docker 已存在，也先展示同一个稳定阶段，界面步骤不会因服务器环境而跳号。
            onProgress(ComputerSetupStage.PREPARING_DOCKER)
            if (computer.capabilities?.dockerAvailable != true) {
                runRootCommand(
                    connection = connection,
                    computer = computer,
                    command = "$remoteDirectory/install-docker.sh",
                    sudoPassword = sudoPassword,
                    errorCode = ComputerErrorCodes.DOCKER_INSTALL_FAILED,
                    errorMessage = "Docker 安装失败",
                )
            }

            onProgress(ComputerSetupStage.INSTALLING_HELPER)
            runRootCommand(
                connection = connection,
                computer = computer,
                command = "sh $remoteDirectory/everytalk-containerctl.sh install $remoteDirectory/runtime-wrapper.sh $remoteDirectory/Dockerfile",
                sudoPassword = sudoPassword,
                errorCode = ComputerErrorCodes.HELPER_INTEGRITY_FAILED,
                errorMessage = "Container Helper 安装失败",
            )
            onProgress(ComputerSetupStage.BUILDING_IMAGE)
            runInstalledHelper(connection, computer, "build-image", sudoPassword)
            onProgress(ComputerSetupStage.CONFIGURING_NETWORK)
            runInstalledHelper(
                connection,
                computer,
                if (computer.allowPrivateNetwork) "set-network private" else "set-network restricted",
                sudoPassword,
            )
            return ComputerProvisionResult(COMPUTER_BOOTSTRAP_VERSION, SANDBOX_IMAGE)
        } finally {
            sudoPassword?.fill('\u0000')
            runCatching {
                connection.execute(
                    command = "rm -rf -- $remoteDirectory",
                    timeoutMillis = 30_000,
                    maxOutputBytes = 64 * 1024,
                )
            }
        }
    }

    private suspend fun prepareRemoteDirectory(connection: ComputerSshConnection, remoteDirectory: String) {
        val cleanup = connection.execute(
            command = "umask 077; rm -rf -- $remoteDirectory; mkdir -m 700 -- $remoteDirectory",
            timeoutMillis = 30_000,
            maxOutputBytes = 64 * 1024,
        )
        if (cleanup.timedOut || cleanup.exitCode != 0) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "无法创建远端配置临时目录")
        }
    }

    private suspend fun uploadAssets(
        connection: ComputerSshConnection,
        remoteDirectory: String,
    ): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        connection.withSftp { sftp ->
            assets.forEach { asset ->
                val bytes = context.assets.open(asset.assetName).use { it.readBytes() }
                try {
                    val remotePath = "$remoteDirectory/${asset.remoteName}"
                    sftp.open(
                        remotePath,
                        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
                    ).use { file ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = minOf(32 * 1024, bytes.size - offset)
                            file.write(offset.toLong(), bytes, offset, count)
                            offset += count
                        }
                    }
                    sftp.chmod(remotePath, if (asset.executable) 0b111000000 else 0b110000000)
                    hashes[asset.remoteName] = sha256(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        }
        return hashes
    }

    private suspend fun verifyAssets(
        connection: ComputerSshConnection,
        remoteDirectory: String,
        expectedHashes: Map<String, String>,
    ) {
        expectedHashes.forEach { (name, expectedHash) ->
            val result = connection.execute(
                command = "sha256sum $remoteDirectory/$name",
                timeoutMillis = 30_000,
                maxOutputBytes = 64 * 1024,
            )
            val actualHash = result.stdout.substringBefore(' ').trim()
            if (result.timedOut || result.exitCode != 0 || actualHash != expectedHash) {
                throw ComputerException(
                    ComputerErrorCodes.HELPER_INTEGRITY_FAILED,
                    "远端配置资产校验失败",
                )
            }
        }
    }

    private suspend fun runInstalledHelper(
        connection: ComputerSshConnection,
        computer: Computer,
        arguments: String,
        sudoPassword: CharArray?,
    ) {
        runRootCommand(
            connection = connection,
            computer = computer,
            command = "/usr/local/libexec/everytalk-containerctl $arguments",
            sudoPassword = sudoPassword,
            errorCode = ComputerErrorCodes.DOCKER_INSTALL_FAILED,
            errorMessage = "Container 环境配置失败",
        )
    }

    private suspend fun runRootCommand(
        connection: ComputerSshConnection,
        computer: Computer,
        command: String,
        sudoPassword: CharArray?,
        errorCode: String,
        errorMessage: String,
    ) {
        val input = if (computer.username == "root" || sudoPassword == null) {
            null
        } else {
            encodePasswordLine(sudoPassword)
        }
        val rootCommand = when {
            computer.username == "root" -> command
            sudoPassword == null -> "sudo -n -- $command"
            else -> "sudo -S -p '' -- $command"
        }
        try {
            val result = connection.execute(
                command = rootCommand,
                stdin = input,
                timeoutMillis = BOOTSTRAP_COMMAND_TIMEOUT_MILLIS,
                maxOutputBytes = BOOTSTRAP_OUTPUT_BYTES,
            )
            if (result.timedOut || result.exitCode != 0) {
                val code = if (
                    computer.username != "root" &&
                    (result.stderr.contains("password", ignoreCase = true) || result.stderr.contains("sudo", ignoreCase = true))
                ) {
                    ComputerErrorCodes.SUDO_REQUIRED
                } else {
                    errorCode
                }
                throw ComputerException(code, errorMessage, retryable = true, action = "RETRY_PROVISION")
            }
        } finally {
            input?.fill(0)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    /** 避免把一次性 sudo 密码转换为无法清除的 String。 */
    private fun encodePasswordLine(password: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        return try {
            ByteArray(encoded.remaining() + 1).also { output ->
                encoded.get(output, 0, output.lastIndex)
                output[output.lastIndex] = '\n'.code.toByte()
            }
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }
}
