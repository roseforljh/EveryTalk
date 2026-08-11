package com.android.everytalk.data.computer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_IDLE_CONNECTION_MILLIS = 5 * 60 * 1000L

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
        } catch (error: ComputerSshChannelOpenException) {
            if (!shouldRetryComputerChannelOpen(startedBefore, lease.connection.startedChannelCount)) {
                throw error
            }
            lease.invalidate()
            lease.close()
            lease = acquire(computer)
            block(lease.connection)
        } finally {
            lease.close()
        }
    }

    suspend fun disconnect(computerId: String) {
        val entry = entries[computerId] ?: return
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
        entries.values.forEach { entry -> entry.connection?.close() }
        entries.clear()
    }
}

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
