package com.android.everytalk.data.computer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.android.everytalk.util.AppLogger
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_IDLE_CONNECTION_MILLIS = 5 * 60 * 1000L

/**
 * App 进程内唯一 SSH 连接池。ComputerManager 与前台 Service 共用它，
 * 同一 VPS 才不会因为两个 Repository 各建一条 Transport。
 */
internal object ComputerConnectionPoolRegistry {
    @Volatile private var sharedPool: ComputerConnectionPool? = null

    fun get(
        sshClient: ComputerSshClient,
        credentialStore: ComputerCredentialStore,
    ): ComputerConnectionPool = sharedPool ?: synchronized(this) {
        sharedPool ?: ComputerConnectionPool(sshClient, credentialStore).also { sharedPool = it }
    }

    fun closeAll(reason: String) {
        sharedPool?.closeWithReason(reason)
    }
}

/**
 * 按 Computer ID 复用已认证 SSH 连接。池内只保存进程内连接对象，凭据每次从本地加密文件短暂读取。
 */
class ComputerConnectionPool(
    private val sshClient: ComputerSshClient,
    private val credentialStore: ComputerCredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private class Entry(now: Long) {
        val mutex = Mutex()
        val activeLeases = AtomicInteger(0)
        val lastUsedAt = AtomicLong(now)

        @Volatile
        var connection: ComputerSshConnection? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun acquire(computer: Computer): ComputerConnectionLease {
        val entry = entries.computeIfAbsent(computer.id) { Entry(clock()) }
        val connection = entry.mutex.withLock {
            entry.connection?.takeIf(ComputerSshConnection::isUsable) ?: run {
                entry.connection?.close()
                val credential = credentialStore.loadComputerCredential(computer.id)
                sshClient.connect(computer, credential).also { entry.connection = it }
            }
        }
        entry.activeLeases.incrementAndGet()
        entry.lastUsedAt.set(clock())
        return ComputerConnectionLease(connection) {
            entry.activeLeases.decrementAndGet()
            entry.lastUsedAt.set(clock())
        }
    }

    suspend fun <T> withConnection(
        computer: Computer,
        block: suspend (ComputerSshConnection) -> T,
    ): T {
        var lease = acquire(computer)
        val startedBefore = lease.connection.startedChannelCount
        return try {
            block(lease.connection)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!isComputerConnectionFailure(error)) throw error
            if (shouldRetryComputerChannelOpen(startedBefore, lease.connection.startedChannelCount)) {
                lease.invalidate()
                lease.close()
                lease = acquire(computer)
                try {
                    block(lease.connection)
                } catch (retryError: Throwable) {
                    // 第二次连接也失败时，无论底层具体是 SSHJ Transport 还是 Channel 包装异常，
                    // 都必须销毁它。否则下一次命令会继续拿到这条已经失效的连接。
                    if (isComputerConnectionFailure(retryError)) lease.invalidate()
                    throw retryError
                }
            } else {
                // Channel 已经启动后不能安全重放命令，但必须销毁坏 Transport，
                // 否则连接池会在下一条命令中继续复用这条已断开的连接。
                AppLogger.warn(
                    "ComputerSsh",
                    "SSH Transport 异常 computer=${computer.id} type=${error::class.java.simpleName} message=${error.message}",
                )
                lease.invalidate()
                throw error
            }
        } finally {
            lease.close()
        }
    }

    /**
     * 为 PTY、SFTP 以外的长生命周期 Channel 建立连接租约。
     * 只有首次尝试尚未启动 Channel 时才换 Transport 重试一次，返回后由调用方持有并关闭 lease。
     */
    suspend fun <T> acquireWithChannel(
        computer: Computer,
        open: suspend (ComputerSshConnection) -> T,
    ): Pair<ComputerConnectionLease, T> {
        var lease = acquire(computer)
        val startedBefore = lease.connection.startedChannelCount
        try {
            return lease to open(lease.connection)
        } catch (error: ComputerSshChannelOpenException) {
            if (!shouldRetryComputerChannelOpen(startedBefore, lease.connection.startedChannelCount)) {
                lease.close()
                throw error
            }
            lease.invalidate()
            lease.close()
            lease = acquire(computer)
            return try {
                lease to open(lease.connection)
            } catch (retryError: Throwable) {
                if (isComputerConnectionFailure(retryError)) lease.invalidate()
                lease.close()
                throw retryError
            }
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    suspend fun disconnect(computerId: String, reason: String = "explicit") {
        val entry = entries[computerId] ?: return
        AppLogger.warn(
            "ComputerSsh",
            "关闭 SSH Transport computer=$computerId reason=$reason activeLeases=${entry.activeLeases.get()}",
        )
        entry.mutex.withLock {
            entry.connection?.close()
            entry.connection = null
        }
    }

    suspend fun closeIdle(maxIdleMillis: Long = DEFAULT_IDLE_CONNECTION_MILLIS) {
        require(maxIdleMillis >= 0) { "空闲时间不能小于 0" }
        val now = clock()
        entries.forEach { (_, entry) ->
            if (entry.activeLeases.get() == 0 && now - entry.lastUsedAt.get() >= maxIdleMillis) {
                entry.mutex.withLock {
                    if (entry.activeLeases.get() == 0 && now - entry.lastUsedAt.get() >= maxIdleMillis) {
                        entry.connection?.close()
                        entry.connection = null
                    }
                }
            }
        }
    }

    override fun close() {
        closeWithReason("owner_closed")
    }

    fun closeWithReason(reason: String) {
        AppLogger.warn("ComputerSsh", "关闭全部 SSH Transport reason=$reason entries=${entries.size}")
        entries.values.forEach { entry -> entry.connection?.close() }
        entries.clear()
    }
}

/** 判断异常是否代表当前 SSH Transport 或 Channel 已经不能继续复用。 */
internal fun isComputerConnectionFailure(error: Throwable): Boolean =
    error is IOException ||
        error is net.schmizz.sshj.transport.TransportException ||
        error is net.schmizz.sshj.connection.ConnectionException

/** 只有本次 block 尚未成功启动任何 Channel 时，重连重试才不会重放远端副作用。 */
internal fun shouldRetryComputerChannelOpen(startedBefore: Long, startedAfter: Long): Boolean =
    startedBefore == startedAfter

class ComputerConnectionLease internal constructor(
    val connection: ComputerSshConnection,
    private val onClosed: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** Channel 发现连接已失效时立即丢弃 transport，下一次获取会重新认证。 */
    fun invalidate() {
        connection.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onClosed()
    }
}
