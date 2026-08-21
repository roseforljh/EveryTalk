package com.android.everytalk.data.computer

import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.FileAttributes
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
private val PRIVATE_FILE_ATTRIBUTES = FileAttributes.Builder().withPermissions(0b110000000).build()
private val PRIVATE_EXECUTABLE_ATTRIBUTES = FileAttributes.Builder().withPermissions(0b111000000).build()
private val PRIVATE_DIRECTORY_ATTRIBUTES = FileAttributes.Builder().withPermissions(0b111000000).build()

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

/** SFTP 文件读写只允许当前 Workspace，逐级拒绝符号链接。 */
class ComputerFileTransfer {
    private val writeLocks = ComputerKeyedMutexPool()

    suspend fun read(
        connection: ComputerSshConnection,
        workspace: ComputerWorkspace,
        path: String,
        offset: Long = 0,
        limit: Int = 256 * 1024,
        encoding: ComputerFileEncoding = ComputerFileEncoding.UTF8,
    ): ComputerFilePage {
        require(offset >= 0) { "读取偏移不能小于 0" }
        require(limit in 1..MAX_READ_BYTES) { "单次读取大小无效" }
        val relative = ComputerWorkspacePath.normalize(path)
        return connection.withSftp { sftp ->
            val target = resolveExistingFile(sftp, workspace, relative)
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
                    path = ComputerWorkspacePath.display(relative),
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
        workspace: ComputerWorkspace,
        toolCallId: String,
        path: String,
        content: String,
        encoding: ComputerFileEncoding = ComputerFileEncoding.UTF8,
        mode: ComputerFileWriteMode = ComputerFileWriteMode.OVERWRITE,
        createParents: Boolean = false,
    ): ComputerFileWriteResult {
        ComputerIdentifier.requireValid(toolCallId, "Tool Call ID")
        val relative = ComputerWorkspacePath.normalize(path)
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

        val lock = writeLocks.forKey("${workspace.id}\u0000$relative")
        return try {
            lock.withLock {
                connection.withSftp { sftp ->
                    val root = resolveWorkspaceRoot(sftp, workspace)
                    val target = resolveWritablePath(sftp, root, relative, createParents)
                    when (mode) {
                        ComputerFileWriteMode.OVERWRITE -> overwriteAtomically(
                            sftp,
                            target,
                            toolCallId,
                            bytes,
                        )
                        ComputerFileWriteMode.APPEND -> append(sftp, target, bytes)
                    }
                    ComputerFileWriteResult(
                        path = ComputerWorkspacePath.display(relative),
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
        workspace: ComputerWorkspace,
        toolCallId: String,
        path: String,
        edits: List<ComputerTextEdit>,
    ): ComputerFileEditResult {
        ComputerIdentifier.requireValid(toolCallId, "Tool Call ID")
        val relative = ComputerWorkspacePath.normalize(path)
        val lock = writeLocks.forKey("${workspace.id}\u0000$relative")
        return lock.withLock {
            connection.withSftp { sftp ->
                val target = resolveExistingFile(sftp, workspace, relative)
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
                            path = ComputerWorkspacePath.display(relative),
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
        workspace: ComputerWorkspace,
        toolCallId: String,
        destinationPath: String,
        source: InputStream,
        expectedSize: Long?,
        overwrite: Boolean,
    ): ComputerStreamTransferResult {
        ComputerIdentifier.requireValid(toolCallId, "Tool Call ID")
        val relative = ComputerWorkspacePath.normalize(destinationPath)
        val lock = writeLocks.forKey("${workspace.id}\u0000$relative")
        return lock.withLock {
            connection.withSftp { sftp ->
                val root = resolveWorkspaceRoot(sftp, workspace)
                val target = resolveWritablePath(sftp, root, relative, createParents = true)
                if (!overwrite && lstatOrNull(sftp, target) != null) {
                    throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "目标文件已存在")
                }
                val temporary = "${target}.everytalk-$toolCallId.upload"
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
                    sftp.chmod(temporary, 0b110000000)
                    if ((expectedSize != null && total != expectedSize) || sftp.size(temporary) != total) {
                        throw ComputerException(ComputerErrorCodes.UPLOAD_INTERRUPTED, "上传文件大小校验失败", retryable = true)
                    }
                    val flags = if (overwrite) {
                        EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC)
                    } else {
                        EnumSet.of(RenameFlags.ATOMIC)
                    }
                    sftp.rename(temporary, target, flags)
                    ComputerStreamTransferResult(
                        path = ComputerWorkspacePath.display(relative),
                        bytes = total,
                        sha256 = digest.digest().toHex(),
                    )
                } finally {
                    runCatching { if (lstatOrNull(sftp, temporary) != null) sftp.rm(temporary) }
                }
            }
        }
    }

    suspend fun download(
        connection: ComputerSshConnection,
        workspace: ComputerWorkspace,
        sourcePath: String,
        destination: OutputStream,
    ): ComputerStreamTransferResult {
        val relative = ComputerWorkspacePath.normalize(sourcePath)
        return connection.withSftp { sftp ->
            val target = resolveExistingFile(sftp, workspace, relative)
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
                path = ComputerWorkspacePath.display(relative),
                bytes = total,
                sha256 = digest.digest().toHex(),
            )
        }
    }

    internal fun writePrivateFile(
        sftp: SFTPClient,
        remotePath: String,
        bytes: ByteArray,
        executable: Boolean = false,
    ) {
        sftp.open(
            remotePath,
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
            if (executable) PRIVATE_EXECUTABLE_ATTRIBUTES else PRIVATE_FILE_ATTRIBUTES,
        ).use { file -> writeAll(file::write, bytes) }
    }

    /** 创建时直接设置 0700，避免高延迟 SSH 上再发一次 chmod 请求。 */
    internal fun createPrivateDirectory(sftp: SFTPClient, remotePath: String) {
        sftp.sftpEngine.makeDir(remotePath, PRIVATE_DIRECTORY_ATTRIBUTES)
    }

    internal fun resolveWorkspaceRoot(sftp: SFTPClient, workspace: ComputerWorkspace): String {
        ComputerIdentifier.requireValid(workspace.id, "Workspace ID")
        val rawAttributes = sftp.lstat(workspace.hostPath)
        if (rawAttributes.type != FileMode.Type.DIRECTORY) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 根目录已变化")
        }
        val root = sftp.canonicalize(workspace.hostPath).trimEnd('/')
        val home = sftp.canonicalize(".").trimEnd('/')
        val expectedPrefix = "$home/.everytalk/workspaces/"
        if (root != "$expectedPrefix${workspace.id}" || !root.startsWith(expectedPrefix)) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 根目录越界")
        }
        return root
    }

    private fun resolveExistingFile(sftp: SFTPClient, workspace: ComputerWorkspace, relative: String): String {
        val root = resolveWorkspaceRoot(sftp, workspace)
        val target = walkWithoutSymlinks(sftp, root, relative, allowMissingLeaf = false)
        val canonical = sftp.canonicalize(target)
        requireWithinRoot(root, canonical)
        if (sftp.lstat(target).type != FileMode.Type.REGULAR) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "目标不是普通文件")
        }
        return canonical
    }

    private fun resolveWritablePath(
        sftp: SFTPClient,
        root: String,
        relative: String,
        createParents: Boolean,
    ): String {
        val parentRelative = relative.substringBeforeLast('/', "")
        val filename = relative.substringAfterLast('/')
        val parent = if (parentRelative.isEmpty()) {
            root
        } else if (createParents) {
            createDirectoriesWithoutSymlinks(sftp, root, parentRelative)
        } else {
            walkWithoutSymlinks(sftp, root, parentRelative, allowMissingLeaf = false)
        }
        val canonicalParent = sftp.canonicalize(parent)
        requireWithinRoot(root, canonicalParent)
        val target = "$canonicalParent/$filename"
        val existing = lstatOrNull(sftp, target)
        if (existing?.type == FileMode.Type.SYMLINK) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "禁止写入符号链接")
        }
        return target
    }

    private fun createDirectoriesWithoutSymlinks(sftp: SFTPClient, root: String, relative: String): String {
        var current = root
        relative.split('/').forEach { component ->
            current = "$current/$component"
            val existing = lstatOrNull(sftp, current)
            if (existing == null) {
                createPrivateDirectory(sftp, current)
            }
            val attributes = sftp.lstat(current)
            if (attributes.type != FileMode.Type.DIRECTORY) {
                throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径包含非目录节点")
            }
        }
        return current
    }

    private fun walkWithoutSymlinks(
        sftp: SFTPClient,
        root: String,
        relative: String,
        allowMissingLeaf: Boolean,
    ): String {
        var current = root
        val components = relative.split('/')
        components.forEachIndexed { index, component ->
            current = "$current/$component"
            val attributes = lstatOrNull(sftp, current)
            if (attributes == null && allowMissingLeaf && index == components.lastIndex) return@forEachIndexed
            if (attributes == null) throw ComputerException(
                ComputerErrorCodes.WORKSPACE_PATH_INVALID,
                "Workspace 路径不存在",
            )
            if (attributes.type == FileMode.Type.SYMLINK) {
                throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径禁止符号链接")
            }
            if (index < components.lastIndex && attributes.type != FileMode.Type.DIRECTORY) {
                throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径包含非目录节点")
            }
        }
        return current
    }

    private fun requireWithinRoot(root: String, candidate: String) {
        if (candidate != root && !candidate.startsWith("$root/")) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "Workspace 路径越界")
        }
    }

    private fun lstatOrNull(sftp: SFTPClient, path: String) = try {
        sftp.lstat(path)
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

    private fun overwriteAtomically(
        sftp: SFTPClient,
        target: String,
        toolCallId: String,
        bytes: ByteArray,
    ) {
        val temporary = "${target}.everytalk-$toolCallId.tmp"
        try {
            sftp.open(
                temporary,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC, OpenMode.EXCL),
            ).use { file -> writeAll(file::write, bytes) }
            sftp.chmod(temporary, 0b110000000)
            check(sftp.size(temporary) == bytes.size.toLong()) { "远端临时文件大小不一致" }
            sftp.rename(temporary, target, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
        } finally {
            runCatching { if (sftp.statExistence(temporary) != null) sftp.rm(temporary) }
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
        sftp.chmod(target, 0b110000000)
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
