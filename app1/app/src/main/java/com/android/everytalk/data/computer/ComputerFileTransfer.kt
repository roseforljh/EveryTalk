package com.android.everytalk.data.computer

import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumSet

private const val MAX_READ_BYTES = 1024 * 1024
private const val MAX_WRITE_BYTES = 8 * 1024 * 1024
/** 新建文件沿用常见默认权限；覆盖已有文件时保留原权限，避免把服务配置改成只有自己可读。 */
private const val DEFAULT_FILE_PERMISSIONS = 0b110100100

enum class ComputerFileEncoding { UTF8, BASE64 }
enum class ComputerFileWriteMode { OVERWRITE, APPEND }

data class ComputerFilePage(
    val path: String,
    val content: String,
    val encoding: ComputerFileEncoding,
    val offset: Long,
    val nextOffset: Long?,
    val size: Long,
    val truncated: Boolean,
)

data class ComputerFileWriteResult(
    val path: String,
    val bytesWritten: Long,
    val size: Long,
)

data class ComputerFileEditResult(
    val path: String,
    val replacements: Int,
    val size: Long,
)

data class ComputerStreamTransferResult(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * 服务器就是 AI 的工具：文件工具与 exec 拥有同样的范围。
 * 路径可以是绝对路径，也可以相对 SSH 用户 Home（支持 `~/` 前缀）；返回值统一给出真实绝对路径。
 */
class ComputerFileTransfer {
    private val writeLocks = ComputerKeyedMutexPool()

    suspend fun read(
        connection: ComputerSshConnection,
        path: String,
        offset: Long = 0,
        limit: Int = 256 * 1024,
        encoding: ComputerFileEncoding = ComputerFileEncoding.UTF8,
    ): ComputerFilePage {
        require(offset >= 0) { "读取偏移不能小于 0" }
        require(limit in 1..MAX_READ_BYTES) { "单次读取大小无效" }
        return connection.withSftp { sftp ->
            val target = resolveExistingFile(sftp, path)
            sftp.open(target, EnumSet.of(OpenMode.READ)).use { file ->
                val size = file.length()
                val bytesToRead = minOf(limit.toLong(), (size - offset).coerceAtLeast(0)).toInt()
                val bytes = ByteArray(bytesToRead)
                var total = 0
                while (total < bytes.size) {
                    val count = file.read(offset + total, bytes, total, bytes.size - total)
                    if (count <= 0) break
                    total += count
                }
                val actual = if (total == bytes.size) bytes else bytes.copyOf(total)
                val next = (offset + total).takeIf { it < size }
                ComputerFilePage(
                    path = target,
                    content = when (encoding) {
                        ComputerFileEncoding.UTF8 -> actual.toString(Charsets.UTF_8)
                        ComputerFileEncoding.BASE64 -> Base64.getEncoder().encodeToString(actual)
                    },
                    encoding = encoding,
                    offset = offset,
                    nextOffset = next,
                    size = size,
                    truncated = next != null,
                )
            }
        }
    }

    suspend fun write(
        connection: ComputerSshConnection,
        toolCallId: String,
        path: String,
        content: String,
        encoding: ComputerFileEncoding = ComputerFileEncoding.UTF8,
        mode: ComputerFileWriteMode = ComputerFileWriteMode.OVERWRITE,
        createParents: Boolean = false,
    ): ComputerFileWriteResult {
        ComputerIdentifier.requireValid(toolCallId, "Tool Call ID")
        requireValidPath(path)
        val bytes = when (encoding) {
            ComputerFileEncoding.UTF8 -> content.toByteArray(Charsets.UTF_8)
            ComputerFileEncoding.BASE64 -> try {
                Base64.getDecoder().decode(content)
            } catch (error: IllegalArgumentException) {
                throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Base64 文件内容无效", cause = error)
            }
        }
        if (bytes.size > MAX_WRITE_BYTES) {
            bytes.fill(0)
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "单次写入不能超过 8 MiB")
        }
        return try {
            writeLocks.forKey(path).withLock {
                connection.withSftp { sftp ->
                    val target = resolveWritablePath(sftp, path, createParents)
                    when (mode) {
                        ComputerFileWriteMode.OVERWRITE -> overwriteAtomically(sftp, target, toolCallId, bytes)
                        ComputerFileWriteMode.APPEND -> append(sftp, target, bytes)
                    }
                    ComputerFileWriteResult(
                        path = target,
                        bytesWritten = bytes.size.toLong(),
                        size = sftp.size(target),
                    )
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 在同一个文件锁和 SFTP 会话内完成读取、匹配、原子写回。
     * 这样能避免两个并发 edit 都基于旧内容成功，随后互相覆盖。
     */
    internal suspend fun edit(
        connection: ComputerSshConnection,
        toolCallId: String,
        path: String,
        edits: List<ComputerTextEdit>,
    ): ComputerFileEditResult {
        ComputerIdentifier.requireValid(toolCallId, "Tool Call ID")
        requireValidPath(path)
        return writeLocks.forKey(path).withLock {
            connection.withSftp { sftp ->
                val target = resolveExistingFile(sftp, path)
                val size = sftp.size(target)
                if (size > MAX_WRITE_BYTES) {
                    throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "edit 文件不能超过 8 MiB")
                }
                val bytes = ByteArray(size.toInt())
                try {
                    sftp.open(target, EnumSet.of(OpenMode.READ)).use { file ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = file.read(offset.toLong(), bytes, offset, bytes.size - offset)
                            if (count <= 0) break
                            offset += count
                        }
                        if (offset != bytes.size) {
                            throw ComputerException(
                                ComputerErrorCodes.DOWNLOAD_INTERRUPTED,
                                "edit 读取文件不完整",
                                retryable = true,
                            )
                        }
                    }
                    val result = ComputerTextEditor.apply(bytes.toString(Charsets.UTF_8), edits, path)
                    val editedBytes = result.content.toByteArray(Charsets.UTF_8)
                    try {
                        if (editedBytes.size > MAX_WRITE_BYTES) {
                            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "edit 后的文件不能超过 8 MiB")
                        }
                        overwriteAtomically(sftp, target, toolCallId, editedBytes)
                        ComputerFileEditResult(
                            path = target,
                            replacements = result.replacements,
                            size = editedBytes.size.toLong(),
                        )
                    } finally {
                        editedBytes.fill(0)
                    }
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }

    suspend fun upload(
        connection: ComputerSshConnection,
        toolCallId: String,
        destinationPath: String,
        source: InputStream,
        expectedSize: Long?,
        overwrite: Boolean,
    ): ComputerStreamTransferResult {
        ComputerIdentifier.requireValid(toolCallId, "Tool Call ID")
        requireValidPath(destinationPath)
        return writeLocks.forKey(destinationPath).withLock {
            connection.withSftp { sftp ->
                val target = resolveWritablePath(sftp, destinationPath, createParents = true)
                val existing = statOrNull(sftp, target)
                if (!overwrite && existing != null) {
                    throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "目标文件已存在")
                }
                val temporary = "$target.everytalk-$toolCallId.upload"
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    sftp.open(
                        temporary,
                        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC, OpenMode.EXCL),
                    ).use { file ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            file.write(total, buffer, 0, count)
                            digest.update(buffer, 0, count)
                            total += count
                        }
                        buffer.fill(0)
                    }
                    sftp.chmod(temporary, existing?.permissionsOrDefault() ?: DEFAULT_FILE_PERMISSIONS)
                    if ((expectedSize != null && total != expectedSize) || sftp.size(temporary) != total) {
                        throw ComputerException(ComputerErrorCodes.UPLOAD_INTERRUPTED, "上传文件大小校验失败", retryable = true)
                    }
                    val flags = if (overwrite) {
                        EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC)
                    } else {
                        // SFTP v3 的标准 rename 自带“不覆盖”语义。单独传 ATOMIC 会被 SSHJ 拒绝，
                        // 即使服务端支持 OpenSSH 原子重命名扩展，也不能用会覆盖目标的扩展冒充此操作。
                        EnumSet.noneOf(RenameFlags::class.java)
                    }
                    sftp.rename(temporary, target, flags)
                    ComputerStreamTransferResult(
                        path = target,
                        bytes = total,
                        sha256 = digest.digest().toHex(),
                    )
                } finally {
                    runCatching { if (statOrNull(sftp, temporary) != null) sftp.rm(temporary) }
                }
            }
        }
    }

    suspend fun download(
        connection: ComputerSshConnection,
        sourcePath: String,
        destination: OutputStream,
    ): ComputerStreamTransferResult = connection.withSftp { sftp ->
        val target = resolveExistingFile(sftp, sourcePath)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        sftp.open(target, EnumSet.of(OpenMode.READ)).use { file ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = file.read(total, buffer, 0, buffer.size)
                if (count <= 0) break
                destination.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                total += count
            }
            buffer.fill(0)
        }
        destination.flush()
        if (total != sftp.size(target)) {
            throw ComputerException(ComputerErrorCodes.DOWNLOAD_INTERRUPTED, "下载文件大小校验失败", retryable = true)
        }
        ComputerStreamTransferResult(
            path = target,
            bytes = total,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun requireValidPath(path: String) {
        if (path.isBlank() || path.length > 4096 || path.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "文件路径无效")
        }
    }

    /** `~`、`~/x` 和相对路径都以 SSH 用户 Home 为基准。 */
    private fun absolutePath(sftp: SFTPClient, path: String): String {
        requireValidPath(path)
        if (path.startsWith('/')) return path
        val home = sftp.canonicalize(".").trimEnd('/')
        val relative = when {
            path == "~" -> ""
            path.startsWith("~/") -> path.substring(2)
            else -> path
        }
        return if (relative.isEmpty()) home else "$home/$relative"
    }

    /** 跟随符号链接得到真实文件，必须是普通文件。 */
    private fun resolveExistingFile(sftp: SFTPClient, path: String): String {
        val absolute = absolutePath(sftp, path)
        val attributes = statOrNull(sftp, absolute)
            ?: throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "文件不存在：$absolute")
        if (attributes.type != FileMode.Type.REGULAR) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "目标不是普通文件：$absolute")
        }
        return sftp.canonicalize(absolute)
    }

    /** 已存在的符号链接写到它指向的真实文件，不把链接替换成普通文件。 */
    private fun resolveWritablePath(sftp: SFTPClient, path: String, createParents: Boolean): String {
        val absolute = absolutePath(sftp, path)
        statOrNull(sftp, absolute)?.let { existing ->
            if (existing.type == FileMode.Type.DIRECTORY) {
                throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "目标是目录：$absolute")
            }
            return sftp.canonicalize(absolute)
        }
        val parent = absolute.substringBeforeLast('/').ifEmpty { "/" }
        if (statOrNull(sftp, parent) == null) {
            if (!createParents) {
                throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "父目录不存在：$parent")
            }
            sftp.mkdirs(parent)
        }
        return "${sftp.canonicalize(parent).trimEnd('/')}/${absolute.substringAfterLast('/')}"
    }

    private fun statOrNull(sftp: SFTPClient, path: String): FileAttributes? = try {
        sftp.stat(path)
    } catch (error: SFTPException) {
        if (
            error.statusCode == Response.StatusCode.NO_SUCH_FILE ||
            error.statusCode == Response.StatusCode.NO_SUCH_PATH
        ) {
            null
        } else {
            throw error
        }
    }

    private fun FileAttributes.permissionsOrDefault(): Int =
        mode?.permissionsMask?.takeIf { it != 0 } ?: DEFAULT_FILE_PERMISSIONS

    private fun overwriteAtomically(
        sftp: SFTPClient,
        target: String,
        toolCallId: String,
        bytes: ByteArray,
    ) {
        val permissions = statOrNull(sftp, target)?.permissionsOrDefault() ?: DEFAULT_FILE_PERMISSIONS
        val temporary = "$target.everytalk-$toolCallId.tmp"
        try {
            sftp.open(
                temporary,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC, OpenMode.EXCL),
            ).use { file -> writeAll(file::write, bytes) }
            sftp.chmod(temporary, permissions)
            check(sftp.size(temporary) == bytes.size.toLong()) { "远端临时文件大小不一致" }
            sftp.rename(temporary, target, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
        } finally {
            runCatching { if (statOrNull(sftp, temporary) != null) sftp.rm(temporary) }
        }
    }

    private fun append(sftp: SFTPClient, target: String, bytes: ByteArray) {
        sftp.open(
            target,
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND),
        ).use { file ->
            val offset = file.length()
            writeAll({ _, data, dataOffset, length -> file.write(offset + dataOffset, data, dataOffset, length) }, bytes)
        }
    }

    private fun writeAll(
        writer: (Long, ByteArray, Int, Int) -> Unit,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(32 * 1024, bytes.size - offset)
            writer(offset.toLong(), bytes, offset, count)
            offset += count
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
