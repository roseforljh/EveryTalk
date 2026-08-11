package com.android.everytalk.data.computer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private const val PROBE_TIMEOUT_MILLIS = 30_000L
private const val PROBE_OUTPUT_BYTES = 256 * 1024
private const val FORWARD_PROBE_TIMEOUT_MILLIS = 3_000

/**
 * 通过只读命令探测 VPS。探测结果只用于能力展示和运行模式选择，不依据资源大小限制用户。
 */
class ComputerProbe {
    suspend fun probe(connection: ComputerSshConnection, sshPort: Int): ComputerCapabilities {
        val commandResult = connection.execute(
            command = PROBE_COMMAND,
            timeoutMillis = PROBE_TIMEOUT_MILLIS,
            maxOutputBytes = PROBE_OUTPUT_BYTES,
        )
        if (commandResult.timedOut) {
            throw ComputerException(ComputerErrorCodes.SSH_TIMEOUT, "服务器能力探测超时", retryable = true)
        }
        if (commandResult.exitCode != 0 || commandResult.stdoutTruncated) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器能力探测失败", retryable = true)
        }

        val base = ComputerProbeParser.parse(commandResult.stdout)
        val sftpAvailable = runCatching {
            connection.withSftp { client -> client.version() }
        }.isSuccess
        val ptyAvailable = runCatching {
            connection.openPty(columns = 80, rows = 24).close()
        }.isSuccess
        val portForwardAvailable = probePortForward(connection, sshPort)
        return base.copy(
            sftpAvailable = sftpAvailable,
            ptyAvailable = ptyAvailable,
            portForwardAvailable = portForwardAvailable,
            containerSandboxAvailable = base.dockerAvailable,
        )
    }

    private suspend fun probePortForward(connection: ComputerSshConnection, sshPort: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                connection.openLocalPortForward(remotePort = sshPort).use { forward ->
                    Socket().use { socket ->
                        socket.soTimeout = FORWARD_PROBE_TIMEOUT_MILLIS
                        socket.connect(
                            InetSocketAddress(InetAddress.getLoopbackAddress(), forward.localPort),
                            FORWARD_PROBE_TIMEOUT_MILLIS,
                        )
                        val banner = ByteArray(4)
                        var offset = 0
                        while (offset < banner.size) {
                            val count = socket.getInputStream().read(banner, offset, banner.size - offset)
                            if (count < 0) break
                            offset += count
                        }
                        check(offset == banner.size && banner.contentEquals("SSH-".toByteArray()))
                    }
                }
            }.isSuccess
        }
}

internal object ComputerProbeParser {
    fun parse(output: String): ComputerCapabilities {
        val values = output.lineSequence()
            .mapNotNull { line ->
                if (!line.startsWith(PROBE_PREFIX)) return@mapNotNull null
                val separator = line.indexOf('=', startIndex = PROBE_PREFIX.length)
                if (separator < 0) return@mapNotNull null
                line.substring(PROBE_PREFIX.length, separator) to line.substring(separator + 1).trim()
            }
            .toMap()

        val osRelease = values["OS_RELEASE"].orEmpty()
            .split(RECORD_SEPARATOR)
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null else entry.substring(0, separator) to unquote(entry.substring(separator + 1))
            }
            .toMap()

        val memoryBytes = values["MEMORY_KIB"]?.toLongOrNull()?.let { kib ->
            if (kib > Long.MAX_VALUE / 1024L) Long.MAX_VALUE else kib * 1024L
        }
        val diskParts = values["DISK"]?.splitToSequence(' ', '\t')
            ?.filter(String::isNotBlank)
            ?.toList()
            .orEmpty()

        return ComputerCapabilities(
            osId = osRelease["ID"].orEmpty().lowercase(),
            osVersion = osRelease["VERSION_ID"].orEmpty(),
            kernel = values["KERNEL"].orEmpty(),
            architecture = values["ARCH"].orEmpty(),
            remoteUser = values["USER"].orEmpty(),
            shell = values["SHELL"].orEmpty(),
            cpuCount = values["CPU_COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
            memoryBytes = memoryBytes,
            diskAvailableBytes = diskParts.getOrNull(3)?.toLongOrNull()?.let { kib ->
                if (kib > Long.MAX_VALUE / 1024L) Long.MAX_VALUE else kib * 1024L
            },
            loadAverage = values["LOAD"].orEmpty().ifBlank { null },
            dockerAvailable = values["DOCKER"] == "1",
            sudoAvailable = values["SUDO"] == "1",
        )
    }

    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val quote = value.first()
        return if ((quote == '\'' || quote == '"') && value.last() == quote) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}

private const val PROBE_PREFIX = "__ET_PROBE_"
private const val RECORD_SEPARATOR = "|"

/** 固定命令不拼接任何用户输入，输出使用单行标记，避免系统本地化文本影响解析。 */
private val PROBE_COMMAND = """
    export LC_ALL=C
    printf '__ET_PROBE_OS_RELEASE='
    if [ -r /etc/os-release ]; then tr '\n' '|' < /etc/os-release; fi
    printf '\n__ET_PROBE_KERNEL='; uname -sr 2>/dev/null
    printf '__ET_PROBE_ARCH='; uname -m 2>/dev/null
    printf '__ET_PROBE_USER='; id -un 2>/dev/null
    printf '__ET_PROBE_SHELL='
    if command -v getent >/dev/null 2>&1; then getent passwd "${'$'}(id -u)" | cut -d: -f7; else printf '%s\n' "${'$'}SHELL"; fi
    printf '__ET_PROBE_CPU_COUNT='
    if command -v getconf >/dev/null 2>&1; then getconf _NPROCESSORS_ONLN 2>/dev/null; elif command -v nproc >/dev/null 2>&1; then nproc; fi
    printf '__ET_PROBE_MEMORY_KIB='; while read -r key value unit; do if [ "${'$'}key" = 'MemTotal:' ]; then printf '%s\n' "${'$'}value"; break; fi; done < /proc/meminfo
    printf '__ET_PROBE_DISK='; df -Pk "${'$'}HOME" 2>/dev/null | tail -n 1
    printf '__ET_PROBE_LOAD='; if [ -r /proc/loadavg ]; then cat /proc/loadavg; fi
    printf '__ET_PROBE_DOCKER='; if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then printf '1\n'; else printf '0\n'; fi
    printf '__ET_PROBE_SUDO='; if command -v sudo >/dev/null 2>&1; then printf '1\n'; else printf '0\n'; fi
""".trimIndent()
