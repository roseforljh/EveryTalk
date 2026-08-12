package com.android.everytalk.data.computer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
private const val DEFAULT_KEEPALIVE_SECONDS = 20
private const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 120_000L
private const val DEFAULT_COMMAND_OUTPUT_BYTES = 4 * 1024 * 1024
private val sshSecurityProviderLock = Any()

/**
 * 创建 SSHJ 客户端前确保进程内使用 App 打包的完整版 BouncyCastle。
 *
 * Android 自带一个同名为 BC 的精简 Provider。SSHJ 发现同名 Provider 后会继续按名称取用它，
 * 导致 CHACHA 等算法在真机上无法加载。这里保留原 Provider 的顺序，只替换同名实现。
 */
private fun createDefaultSshClient(): SSHClient {
    synchronized(sshSecurityProviderLock) {
        val providerName = BouncyCastleProvider.PROVIDER_NAME
        val currentProvider = Security.getProvider(providerName)
        if (currentProvider !is BouncyCastleProvider) {
            val originalPosition = Security.getProviders().indexOfFirst { it.name == providerName } + 1
            if (currentProvider != null) Security.removeProvider(providerName)

            val bundledProvider = BouncyCastleProvider()
            val installedPosition = if (originalPosition > 0) {
                Security.insertProviderAt(bundledProvider, originalPosition)
            } else {
                Security.addProvider(bundledProvider)
            }
            if (installedPosition <= 0 || Security.getProvider(providerName) !is BouncyCastleProvider) {
                // 替换失败时恢复原 Provider，避免影响进程内其他加密功能。
                Security.removeProvider(providerName)
                if (currentProvider != null) {
                    Security.insertProviderAt(currentProvider, originalPosition)
                }
                throw IllegalStateException("无法初始化 SSH 加密组件")
            }
        }
    }
    return SSHClient()
}

internal data class ValidatedComputerEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
)

/**
 * 校验用户输入的 SSH 地址。Host 与端口分开保存，拒绝 URL、userinfo、路径和控制字符。
 * 返回值中的域名已经通过 IDN 规则转成 ASCII，IPv6 地址会移除最外层方括号。
 */
internal object ComputerEndpointValidator {
    fun validate(hostInput: String, port: Int, usernameInput: String? = null): ValidatedComputerEndpoint {
        val host = normalizeHost(hostInput)
        if (port !in 1..65_535) {
            throw ComputerException(ComputerErrorCodes.HOST_INVALID, "SSH 端口必须在 1 到 65535 之间")
        }
        val username = usernameInput?.trim()?.also { value ->
            if (value.isEmpty() || value.length > 255 || value.any { it.isISOControl() }) {
                throw ComputerException(ComputerErrorCodes.HOST_INVALID, "SSH 用户名无效")
            }
        }
        return ValidatedComputerEndpoint(host, port, username)
    }

    private fun normalizeHost(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            throw ComputerException(ComputerErrorCodes.HOST_INVALID, "服务器 Host 无效")
        }

        val host = when {
            trimmed.startsWith('[') && trimmed.endsWith(']') -> trimmed.substring(1, trimmed.length - 1)
            trimmed.startsWith('[') || trimmed.endsWith(']') -> throw ComputerException(
                ComputerErrorCodes.HOST_INVALID,
                "IPv6 方括号不完整",
            )
            else -> trimmed
        }
        if (host.isEmpty()) {
            throw ComputerException(ComputerErrorCodes.HOST_INVALID, "服务器 Host 无效")
        }

        if (':' in host) {
            val parsed = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (parsed !is Inet6Address) {
                throw ComputerException(ComputerErrorCodes.HOST_INVALID, "IPv6 地址无效")
            }
            return host
        }

        if (host.all { it.isDigit() || it == '.' } && '.' in host) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { part ->
                    part.isEmpty() || part.length > 3 || (part.length > 1 && part.startsWith('0')) || part.toIntOrNull() !in 0..255
                }
            ) {
                throw ComputerException(ComputerErrorCodes.HOST_INVALID, "IPv4 地址无效")
            }
            return host
        }

        val ascii = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (error: IllegalArgumentException) {
            throw ComputerException(ComputerErrorCodes.HOST_INVALID, "服务器域名或主机名无效", cause = error)
        }
        val withoutRootDot = ascii.removeSuffix(".")
        if (withoutRootDot.isEmpty() || withoutRootDot.length > 253) {
            throw ComputerException(ComputerErrorCodes.HOST_INVALID, "服务器域名或主机名无效")
        }
        val labels = withoutRootDot.split('.')
        if (labels.any { label -> label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-') }) {
            throw ComputerException(ComputerErrorCodes.HOST_INVALID, "服务器域名或主机名无效")
        }
        return withoutRootDot
    }
}

internal data class EncodedHostKey(
    val algorithm: String,
    val blob: ByteArray,
    val blobBase64: String,
    val fingerprint: String,
)

/** 生成与 OpenSSH `SHA256:` 显示一致的完整公钥 Blob 和指纹。 */
internal object ComputerHostKeyCodec {
    fun encode(publicKey: PublicKey): EncodedHostKey {
        val keyType = KeyType.fromKey(publicKey)
        if (keyType == KeyType.UNKNOWN) {
            throw ComputerException(ComputerErrorCodes.HOST_KEY_CHANGED, "SSH Host Key 算法不受支持")
        }
        val buffer = Buffer.PlainBuffer()
        keyType.putPubKeyIntoBuffer(publicKey, buffer)
        val blob = buffer.compactData
        val base64 = Base64.getEncoder().encodeToString(blob)
        val fingerprint = Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        return EncodedHostKey(
            algorithm = keyType.toString(),
            blob = blob,
            blobBase64 = base64,
            fingerprint = "SHA256:$fingerprint",
        )
    }
}

private class CapturingHostKeyVerifier : HostKeyVerifier {
    private val captured = AtomicReference<EncodedHostKey?>()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val encoded = ComputerHostKeyCodec.encode(key)
        val existing = captured.get()
        return if (existing == null) {
            captured.compareAndSet(null, encoded)
            true
        } else {
            MessageDigest.isEqual(existing.blob, encoded.blob)
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    fun requireCaptured(): EncodedHostKey = captured.get()
        ?: throw ComputerException(ComputerErrorCodes.SSH_TIMEOUT, "SSH 握手没有返回 Host Key", retryable = true)
}

private class PinnedHostKeyVerifier(
    private val expectedAlgorithm: String,
    expectedBlobBase64: String,
) : HostKeyVerifier {
    private val expectedBlob = try {
        Base64.getDecoder().decode(expectedBlobBase64)
    } catch (error: IllegalArgumentException) {
        throw ComputerException(ComputerErrorCodes.HOST_KEY_CHANGED, "本地 Host Key 数据无效", cause = error)
    }
    private val mismatchDetected = AtomicBoolean(false)

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val actual = ComputerHostKeyCodec.encode(key)
        val matches = actual.algorithm == expectedAlgorithm && MessageDigest.isEqual(expectedBlob, actual.blob)
        mismatchDetected.set(!matches)
        return matches
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = listOf(expectedAlgorithm)

    fun didDetectMismatch(): Boolean = mismatchDetected.get()
}

data class ComputerSshCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

internal data class BoundedStreamResult(val bytes: ByteArray, val truncated: Boolean)

internal object ComputerBoundedOutputReader {
    fun read(input: InputStream, maxBytes: Int): BoundedStreamResult {
        require(maxBytes > 0)
        val headLimit = (maxBytes + 1) / 2
        val tailLimit = maxBytes - headLimit
        val head = ByteArrayOutputStream(minOf(headLimit, 64 * 1024))
        val tail = ByteArray(tailLimit)
        var tailWriteIndex = 0
        var tailCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            var offset = 0
            val headRemaining = headLimit - head.size()
            if (headRemaining > 0) {
                val headCount = minOf(count, headRemaining)
                head.write(buffer, 0, headCount)
                offset += headCount
            }
            if (offset < count && tailLimit > 0) {
                val remaining = count - offset
                if (remaining >= tailLimit) {
                    buffer.copyInto(tail, 0, count - tailLimit, count)
                    tailWriteIndex = 0
                    tailCount = tailLimit
                } else {
                    val first = minOf(remaining, tailLimit - tailWriteIndex)
                    buffer.copyInto(tail, tailWriteIndex, offset, offset + first)
                    val second = remaining - first
                    if (second > 0) buffer.copyInto(tail, 0, offset + first, count)
                    tailWriteIndex = (tailWriteIndex + remaining) % tailLimit
                    tailCount = minOf(tailLimit, tailCount + remaining)
                }
            }
        }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024)).apply {
            write(head.toByteArray())
            if (tailCount in 1 until tailLimit) {
                write(tail, 0, tailCount)
            } else if (tailCount == tailLimit && tailLimit > 0) {
                write(tail, tailWriteIndex, tailLimit - tailWriteIndex)
                if (tailWriteIndex > 0) write(tail, 0, tailWriteIndex)
            }
        }
        buffer.fill(0)
        tail.fill(0)
        return BoundedStreamResult(output.toByteArray(), totalBytes > maxBytes)
    }
}

/**
 * Android 本地 SSH 入口。首次探测只进行 Key Exchange；正式连接要求完整 Host Key 匹配后才认证。
 */
class ComputerSshClient internal constructor(
    private val clientFactory: () -> SSHClient = ::createDefaultSshClient,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    suspend fun probeHostKey(host: String, port: Int): HostKeyProbeResult = withContext(Dispatchers.IO) {
        val endpoint = ComputerEndpointValidator.validate(host, port)
        val verifier = CapturingHostKeyVerifier()
        val client = createClient(verifier)
        try {
            client.connect(endpoint.host, endpoint.port)
            val hostKey = verifier.requireCaptured()
            HostKeyProbeResult(
                host = endpoint.host,
                resolvedAddress = client.remoteAddress?.hostAddress
                    ?: throw ComputerException(
                        ComputerErrorCodes.HOST_RESOLUTION_FAILED,
                        "无法确认服务器解析地址",
                        retryable = true,
                    ),
                port = endpoint.port,
                algorithm = hostKey.algorithm,
                keyBlob = hostKey.blob,
                fingerprint = hostKey.fingerprint,
            )
        } catch (error: Throwable) {
            throw mapConnectionFailure(error)
        } finally {
            closeClient(client)
        }
    }

    suspend fun connect(computer: Computer, credential: ComputerCredential): ComputerSshConnection =
        withContext(Dispatchers.IO) {
            val endpoint = ComputerEndpointValidator.validate(computer.host, computer.port, computer.username)
            val algorithm = computer.hostKeyAlgorithm
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器尚未确认 Host Key")
            val blob = computer.hostKeyBlobBase64
                ?: throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "服务器尚未确认 Host Key")
            val verifier = PinnedHostKeyVerifier(algorithm, blob)
            val client = createClient(verifier)
            try {
                client.connect(endpoint.host, endpoint.port)
                authenticate(client, endpoint.username.orEmpty(), credential)
                client.connection.keepAlive.keepAliveInterval = DEFAULT_KEEPALIVE_SECONDS
                ComputerSshConnection(client)
            } catch (error: Throwable) {
                closeClient(client)
                if (verifier.didDetectMismatch()) {
                    throw ComputerException(
                        code = ComputerErrorCodes.HOST_KEY_CHANGED,
                        message = "服务器身份已变化，请先核对新指纹",
                        action = "CONFIRM_HOST_KEY",
                        cause = error,
                    )
                }
                throw mapConnectionFailure(error, credential)
            } finally {
                credential.clear()
            }
        }

    private fun createClient(verifier: HostKeyVerifier): SSHClient = clientFactory().apply {
        connectTimeout = connectTimeoutMillis
        timeout = readTimeoutMillis
        addHostKeyVerifier(verifier)
    }

    private fun authenticate(client: SSHClient, username: String, credential: ComputerCredential) {
        when (credential) {
            is ComputerCredential.Password -> client.authPassword(username, credential.password)
            is ComputerCredential.PrivateKey -> authenticatePrivateKey(client, username, credential)
        }
        if (!client.isAuthenticated) {
            throw ComputerException(ComputerErrorCodes.AUTH_FAILED, "SSH 认证失败", action = "UPDATE_CREDENTIAL")
        }
    }

    private fun authenticatePrivateKey(
        client: SSHClient,
        username: String,
        credential: ComputerCredential.PrivateKey,
    ) {
        val privateKeyText = String(credential.privateKey)
        val passwordFinder: PasswordFinder? = credential.passphrase?.let(PasswordUtils::createOneOff)
        val keyProvider = try {
            client.loadKeys(privateKeyText, null, passwordFinder).also { provider ->
                provider.public
                provider.private
            }
        } catch (error: IOException) {
            throw ComputerException(
                code = ComputerErrorCodes.PRIVATE_KEY_INVALID,
                message = "SSH 私钥格式或口令无效",
                action = "UPDATE_CREDENTIAL",
                cause = error,
            )
        }
        client.authPublickey(username, keyProvider)
    }

    private fun mapConnectionFailure(error: Throwable, credential: ComputerCredential? = null): ComputerException {
        if (error is ComputerException) return error
        return when (error) {
            is UnknownHostException -> ComputerException(
                ComputerErrorCodes.HOST_RESOLUTION_FAILED,
                "服务器地址解析失败",
                retryable = true,
                cause = error,
            )
            is SocketTimeoutException -> ComputerException(
                ComputerErrorCodes.SSH_TIMEOUT,
                "SSH 连接超时",
                retryable = true,
                cause = error,
            )
            is UserAuthException -> ComputerException(
                ComputerErrorCodes.AUTH_FAILED,
                "SSH 认证失败",
                action = "UPDATE_CREDENTIAL",
                cause = error,
            )
            is IOException -> ComputerException(
                ComputerErrorCodes.SSH_TIMEOUT,
                "无法建立 SSH 连接",
                retryable = true,
                cause = error,
            )
            else -> {
                val code = if (credential is ComputerCredential.PrivateKey) {
                    ComputerErrorCodes.PRIVATE_KEY_INVALID
                } else {
                    ComputerErrorCodes.SSH_TIMEOUT
                }
                ComputerException(code, "SSH 连接失败", retryable = true, cause = error)
            }
        }
    }

    private fun closeClient(client: SSHClient) {
        runCatching { client.close() }
    }
}

/** 已认证连接可并发创建独立 exec、SFTP、PTY 和转发 Channel。 */
class ComputerSshConnection internal constructor(private val client: SSHClient) : Closeable {
    private val resources = CopyOnWriteArraySet<Closeable>()
    private val closed = AtomicBoolean(false)
    private val startedChannels = AtomicLong(0)
    private val runtimeWrapperCache = ComputerRuntimeWrapperCache()

    val isUsable: Boolean
        get() = !closed.get() && client.isConnected && client.isAuthenticated

    /** 连接池用它判断失败前是否已经启动过本次 Channel，防止自动重放有副作用操作。 */
    internal val startedChannelCount: Long
        get() = startedChannels.get()

    suspend fun execute(
        command: String,
        stdin: ByteArray? = null,
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        maxOutputBytes: Int = DEFAULT_COMMAND_OUTPUT_BYTES,
    ): ComputerSshCommandResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "SSH 命令不能为空" }
        require(timeoutMillis > 0) { "命令超时必须大于 0" }
        require(maxOutputBytes > 0) { "输出上限必须大于 0" }
        openChannel { client.startSession() }.use { session ->
            val remoteCommand = openChannel { session.exec(command) }
            startedChannels.incrementAndGet()
            try {
                coroutineScope {
                    val stdout = async(Dispatchers.IO) {
                        ComputerBoundedOutputReader.read(remoteCommand.inputStream, maxOutputBytes)
                    }
                    val stderr = async(Dispatchers.IO) {
                        ComputerBoundedOutputReader.read(remoteCommand.errorStream, maxOutputBytes)
                    }
                    val inputWriter = async(Dispatchers.IO) {
                        remoteCommand.outputStream.use { output ->
                            if (stdin != null) {
                                output.write(stdin)
                                output.flush()
                            }
                        }
                    }

                    inputWriter.await()
                    remoteCommand.join(timeoutMillis, TimeUnit.MILLISECONDS)
                    val timedOut = remoteCommand.isOpen
                    if (timedOut) runCatching { remoteCommand.close() }

                    val stdoutResult = stdout.await()
                    val stderrResult = stderr.await()
                    ComputerSshCommandResult(
                        stdout = stdoutResult.bytes.toString(Charsets.UTF_8),
                        stderr = stderrResult.bytes.toString(Charsets.UTF_8),
                        exitCode = remoteCommand.exitStatus,
                        timedOut = timedOut,
                        stdoutTruncated = stdoutResult.truncated,
                        stderrTruncated = stderrResult.truncated,
                    )
                }
            } finally {
                runCatching { remoteCommand.close() }
            }
        }
    }

    suspend fun <T> withSftp(block: suspend (SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        val sftp = openChannel { client.newSFTPClient() }
        startedChannels.incrementAndGet()
        sftp.use { block(it) }
    }

    /**
     * 每条 SSH Transport 只安装一次相同版本的 Runtime Wrapper。
     * 新连接拥有独立缓存，安装失败不会留下成功状态。
     */
    internal suspend fun ensureRuntimeWrapper(
        expectedHash: ByteArray,
        installer: suspend () -> Unit,
    ): Boolean = runtimeWrapperCache.ensureInstalled(expectedHash, installer)

    suspend fun openPty(columns: Int = 120, rows: Int = 40): ComputerPtyHandle = withContext(Dispatchers.IO) {
        require(columns > 0 && rows > 0) { "PTY 尺寸必须大于 0" }
        val session = openChannel { client.startSession() }
        try {
            openChannel {
                session.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap<PTYMode, Int>())
            }
            val shell = openChannel { session.startShell() }
            startedChannels.incrementAndGet()
            ComputerPtyHandle(session, shell) { handle -> resources.remove(handle) }
                .also(resources::add)
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw error
        }
    }

    suspend fun openLocalPortForward(
        remotePort: Int,
        remoteHost: String = "127.0.0.1",
        requestedLocalPort: Int = 0,
    ): ComputerPortForward = withContext(Dispatchers.IO) {
        require(remotePort in 1..65_535) { "远端端口无效" }
        require(requestedLocalPort in 0..65_535) { "本地端口无效" }
        ensureUsable()

        val serverSocket = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedLocalPort))
        }
        try {
            val localAddress = serverSocket.inetAddress.hostAddress
            val parameters = Parameters(localAddress, serverSocket.localPort, remoteHost, remotePort)
            val forwarder = openChannel { client.newLocalPortForwarder(parameters, serverSocket) }
            ComputerPortForward(serverSocket, forwarder) { handle -> resources.remove(handle) }
                .also(resources::add)
                .also(ComputerPortForward::start)
                .also { startedChannels.incrementAndGet() }
        } catch (error: Throwable) {
            runCatching { serverSocket.close() }
            throw error
        }
    }

    private fun ensureUsable() {
        if (!isUsable) throw IOException("SSH connection is closed")
    }

    /** 只包装远端命令尚未开始前的 Channel 建立失败，供连接池安全重连。 */
    private inline fun <T> openChannel(block: () -> T): T {
        try {
            ensureUsable()
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ComputerSshChannelOpenException(error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.toList().forEach { resource -> runCatching { resource.close() } }
        resources.clear()
        runCatching { client.close() }
    }

}

/**
 * 保存单条 SSH 连接已经安装的 Runtime Wrapper 版本。
 * 首次并发安装通过 Mutex 合并，成功后同版本调用走无锁快路径。
 */
internal class ComputerRuntimeWrapperCache {
    private val installationMutex = Mutex()

    @Volatile
    private var installedHash: ByteArray? = null

    /** 返回 true 表示本次调用实际完成了安装。 */
    suspend fun ensureInstalled(expectedHash: ByteArray, installer: suspend () -> Unit): Boolean {
        if (installedHash?.contentEquals(expectedHash) == true) return false
        return installationMutex.withLock {
            if (installedHash?.contentEquals(expectedHash) == true) return@withLock false
            installer()
            installedHash = expectedHash.copyOf()
            true
        }
    }
}

internal class ComputerSshChannelOpenException(cause: Throwable) : IOException(
    "SSH Channel 建立失败",
    cause,
)

class ComputerPtyHandle internal constructor(
    private val session: Session,
    private val shell: Session.Shell,
    private val onClosed: (ComputerPtyHandle) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val input: InputStream get() = shell.inputStream
    val error: InputStream get() = shell.errorStream
    val output: OutputStream get() = shell.outputStream
    val isOpen: Boolean get() = !closed.get() && shell.isOpen

    suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        require(columns > 0 && rows > 0) { "PTY 尺寸必须大于 0" }
        shell.changeWindowDimensions(columns, rows, 0, 0)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { shell.close() }
        runCatching { session.close() }
        onClosed(this)
    }
}

class ComputerPortForward internal constructor(
    private val serverSocket: ServerSocket,
    private val forwarder: LocalPortForwarder,
    private val onClosed: (ComputerPortForward) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>()
    private lateinit var listenerThread: Thread

    val localPort: Int get() = serverSocket.localPort
    val isActive: Boolean
        get() = !closed.get() && failure.get() == null && listenerThread.isAlive
    val failureCause: Throwable? get() = failure.get()

    internal fun start() {
        listenerThread = Thread({
            try {
                forwarder.listen()
            } catch (error: Throwable) {
                if (!closed.get()) failure.compareAndSet(null, error)
            }
        }, "EveryTalk-SSH-PortForward").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { forwarder.close() }
        runCatching { serverSocket.close() }
        if (::listenerThread.isInitialized) listenerThread.interrupt()
        onClosed(this)
    }
}
