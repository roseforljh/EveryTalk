package com.android.everytalk.data.computer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TERMINAL_BUFFER_CHARS = 128 * 1024
private const val TERMINAL_WRITE_CHARS = 64 * 1024
private const val CONTAINER_TERMINAL_HELPER = "/usr/local/libexec/everytalk-containerctl"

data class ComputerTerminalReadResult(
    val terminalId: String,
    val output: String,
    val cursor: Long,
    val droppedBeforeCursor: Boolean,
    val open: Boolean,
)

/** PTY 与 Ring Buffer 仅存在于当前 Android 进程，进程重启后旧 Terminal ID 统一失效。 */
class ComputerTerminalManager(private val repository: ComputerRepository) : Closeable {
    enum class TerminalWriter { AGENT, HUMAN }

    private data class TerminalSession(
        val context: ComputerRequestContext,
        val lease: ComputerConnectionLease,
        val foregroundActivity: Closeable,
        val pty: ComputerPtyHandle,
        val buffer: TerminalRingBuffer,
        val readerJobs: List<Job>,
        val lost: AtomicBoolean = AtomicBoolean(false),
        val writer: AtomicReference<TerminalWriter> = AtomicReference(TerminalWriter.AGENT),
        val sensitiveWindow: SensitiveTerminalView = SensitiveTerminalView(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminals = ConcurrentHashMap<String, TerminalSession>()

    suspend fun open(
        requestContext: ComputerRequestContext,
        workspace: ComputerWorkspace,
        columns: Int = 120,
        rows: Int = 40,
    ): ComputerTerminalReadResult {
        val foregroundActivity = repository.acquireForegroundActivity()
        val (lease, computer, pty) = try {
            repository.acquireConnectionAndOpen(requestContext.computerId) { connection ->
                connection.openPty(columns, rows)
            }
        } catch (error: Throwable) {
            foregroundActivity.close()
            throw error
        }
        try {
            val terminalId = "terminal_${UUID.randomUUID().toString().replace("-", "")}"
            val ringBuffer = TerminalRingBuffer(TERMINAL_BUFFER_CHARS)
            val lost = AtomicBoolean(false)
            val jobs = listOf(pty.input, pty.error).map { input ->
                scope.launch {
                    try {
                        InputStreamReader(input, Charsets.UTF_8).use { reader ->
                            val chars = CharArray(4096)
                            while (true) {
                                val count = reader.read(chars)
                                if (count < 0) break
                                ringBuffer.append(chars, count)
                            }
                        }
                    } catch (_: Throwable) {
                        lost.set(true)
                    }
                }
            }
            val session = TerminalSession(requestContext, lease, foregroundActivity, pty, ringBuffer, jobs, lost)
            terminals[terminalId] = session

            val initialization = when (computer.runMode) {
                ComputerRunMode.DIRECT ->
                    "cd \"${'$'}HOME/.everytalk/workspaces/${workspace.id}\" && exec \"${'$'}{SHELL:-/bin/sh}\" -l"
                ComputerRunMode.CONTAINER -> {
                    val helper = if (computer.username == "root") {
                        CONTAINER_TERMINAL_HELPER
                    } else {
                        "sudo -n -- $CONTAINER_TERMINAL_HELPER"
                    }
                    "exec $helper terminal ${workspace.id}"
                }
            }
            pty.output.write("$initialization\n".toByteArray(Charsets.UTF_8))
            pty.output.flush()
            return ComputerTerminalReadResult(terminalId, "", 0, false, true)
        } catch (error: Throwable) {
            lease.close()
            foregroundActivity.close()
            throw error
        }
    }

    fun read(
        requestContext: ComputerRequestContext,
        terminalId: String,
        cursor: Long = 0,
    ): ComputerTerminalReadResult {
        val session = requireSession(requestContext, terminalId)
        val page = session.buffer.read(cursor)
        return ComputerTerminalReadResult(
            terminalId = terminalId,
            output = session.sensitiveWindow.sanitize(page.text, page.nextCursor - page.text.length, page.nextCursor),
            cursor = page.nextCursor,
            droppedBeforeCursor = page.dropped,
            open = session.pty.isOpen && !session.lost.get(),
        )
    }

    fun write(requestContext: ComputerRequestContext, terminalId: String, input: String) {
        if (input.length > TERMINAL_WRITE_CHARS || '\u0000' in input) {
            throw ComputerException(ComputerErrorCodes.TERMINAL_LOST, "Terminal 输入无效")
        }
        val session = requireSession(requestContext, terminalId)
        if (!session.pty.isOpen || session.lost.get()) throw terminalLost()
        if (session.writer.get() != TerminalWriter.AGENT) {
            throw ComputerException(ComputerErrorCodes.TERMINAL_LOST, "Terminal 正由用户接管，Agent 暂时不能写入")
        }
        session.pty.output.write(input.toByteArray(Charsets.UTF_8))
        session.pty.output.flush()
    }

    /** 开始用户接管。同一 PTY 任一时刻只能有一个写入方。 */
    fun beginHumanTakeover(requestContext: ComputerRequestContext, terminalId: String): Boolean {
        val session = requireSession(requestContext, terminalId)
        if (!session.writer.compareAndSet(TerminalWriter.AGENT, TerminalWriter.HUMAN)) return false
        session.sensitiveWindow.begin(session.buffer.endCursor())
        return true
    }

    /** 用户敏感输入直接写 PTY，不转换为普通 String；调用结束后覆盖 buffer。 */
    fun writeHuman(requestContext: ComputerRequestContext, terminalId: String, input: CharArray) {
        val session = requireSession(requestContext, terminalId)
        require(session.writer.get() == TerminalWriter.HUMAN) { "Terminal 尚未由用户接管" }
        require(input.size <= TERMINAL_WRITE_CHARS && input.none { it == '\u0000' }) { "Terminal 输入无效" }
        val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(input))
        val payload = ByteArray(bytes.remaining()).also(bytes::get)
        try {
            session.pty.output.write(payload)
            session.pty.output.flush()
        } finally {
            payload.fill(0)
            input.fill('\u0000')
        }
    }

    /** 可信同步点缺失时保留开放脱敏窗口，后续 Agent View 继续丢弃该段输出。 */
    fun finishHumanTakeover(
        requestContext: ComputerRequestContext,
        terminalId: String,
        trustedSyncPoint: Boolean,
    ): Boolean {
        val session = requireSession(requestContext, terminalId)
        if (!session.writer.compareAndSet(TerminalWriter.HUMAN, TerminalWriter.AGENT)) return false
        session.sensitiveWindow.finish(session.buffer.endCursor(), trustedSyncPoint)
        return true
    }

    /** Human View 可读取 Raw PTY；该结果禁止进入 Tool Result 或模型上下文。 */
    fun readForHuman(requestContext: ComputerRequestContext, terminalId: String, cursor: Long = 0): ComputerTerminalReadResult {
        val session = requireSession(requestContext, terminalId)
        val page = session.buffer.read(cursor)
        return ComputerTerminalReadResult(terminalId, page.text, page.nextCursor, page.dropped, session.pty.isOpen && !session.lost.get())
    }

    suspend fun resize(
        requestContext: ComputerRequestContext,
        terminalId: String,
        columns: Int,
        rows: Int,
    ) {
        val session = requireSession(requestContext, terminalId)
        if (!session.pty.isOpen || session.lost.get()) throw terminalLost()
        session.pty.resize(columns, rows)
    }

    fun close(requestContext: ComputerRequestContext, terminalId: String) {
        val session = requireSession(requestContext, terminalId)
        terminals.remove(terminalId, session)
        closeSession(session)
    }

    /** Workspace 被清理时关闭属于它的所有本地 PTY。 */
    fun closeWorkspace(workspaceId: String) {
        terminals.entries
            .filter { (_, session) -> session.context.workspaceId == workspaceId }
            .forEach { (terminalId, session) ->
                if (terminals.remove(terminalId, session)) closeSession(session)
            }
    }

    /** 前台通知的停止动作关闭全部当前 PTY，但保留管理器供后续新 Terminal 使用。 */
    fun closeActiveSessions() {
        terminals.values.forEach(::closeSession)
        terminals.clear()
    }

    private fun requireSession(context: ComputerRequestContext, terminalId: String): TerminalSession {
        val session = terminals[terminalId] ?: throw terminalLost()
        if (session.context != context) throw terminalLost()
        return session
    }

    private fun closeSession(session: TerminalSession) {
        session.readerJobs.forEach(Job::cancel)
        session.pty.close()
        session.lease.close()
        session.foregroundActivity.close()
    }

    private fun terminalLost() = ComputerException(
        ComputerErrorCodes.TERMINAL_LOST,
        "Terminal 已断开，请重新打开",
        retryable = true,
    )

    override fun close() {
        closeActiveSessions()
        scope.cancel()
    }
}

internal data class TerminalBufferPage(val text: String, val nextCursor: Long, val dropped: Boolean)

internal class TerminalRingBuffer(private val maxCharacters: Int) {
    private val text = StringBuilder()
    private var startCursor = 0L

    init {
        require(maxCharacters > 0)
    }

    @Synchronized
    fun append(characters: CharArray, count: Int) {
        require(count in 0..characters.size)
        text.append(characters, 0, count)
        val overflow = text.length - maxCharacters
        if (overflow > 0) {
            text.delete(0, overflow)
            startCursor += overflow
        }
    }

    @Synchronized
    fun read(cursor: Long): TerminalBufferPage {
        require(cursor >= 0)
        val dropped = cursor < startCursor
        val effectiveCursor = cursor.coerceIn(startCursor, startCursor + text.length)
        val startIndex = (effectiveCursor - startCursor).toInt()
        return TerminalBufferPage(
            text = text.substring(startIndex),
            nextCursor = startCursor + text.length,
            dropped = dropped,
        )
    }

    @Synchronized
    fun endCursor(): Long = startCursor + text.length
}

/** 将用户接管期间的 Raw PTY 窗口从 Agent View 中完整移除。 */
internal class SensitiveTerminalView {
    private data class Window(val start: Long, val endExclusive: Long)
    private val openStart = AtomicLong(-1)
    private val windows = mutableListOf<Window>()

    fun begin(cursor: Long) {
        check(openStart.compareAndSet(-1, cursor)) { "敏感终端窗口已开启" }
    }

    @Synchronized
    fun finish(cursor: Long, trustedSyncPoint: Boolean) {
        val start = openStart.getAndSet(-1)
        if (start < 0) return
        windows += Window(start, if (trustedSyncPoint) cursor else Long.MAX_VALUE)
    }

    @Synchronized
    fun sanitize(text: String, startCursor: Long, endCursor: Long): String {
        val activeStart = openStart.get()
        val all = if (activeStart >= 0) windows + Window(activeStart, Long.MAX_VALUE) else windows
        if (all.isEmpty() || text.isEmpty()) return text
        val keep = BooleanArray(text.length) { true }
        all.forEach { window ->
            val from = maxOf(startCursor, window.start)
            val until = minOf(endCursor, window.endExclusive)
            if (from < until) {
                for (index in (from - startCursor).toInt() until (until - startCursor).toInt()) keep[index] = false
            }
        }
        return buildString(text.length) { text.forEachIndexed { index, char -> if (keep[index]) append(char) } }
    }
}
