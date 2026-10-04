package com.android.everytalk.data.computer

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import com.android.everytalk.util.storage.readAtMost

/** 将 App 私有 Workspace 映射为 just-bash 请求中的相对路径文件集合。 */
class LocalWorkspaceFileBridge(
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val maxTotalBytes: Long = 25L * 1024 * 1024,
    private val maxFileCount: Int = 500,
) {
    /** 工作区文件条目元信息：包含相对路径、字节数及可能存在的 UTF-8 文本内容（非 UTF-8 二进制文件为 null）。 */
    private data class WorkspaceEntry(
        val relativePath: String,
        val byteSize: Long,
        val textContent: String?,
    )
    /** 只检查路径，不创建目录。所有本地读写都应使用相同规则，拒绝符号链接和设备路径。 */
    fun target(root: File, relativePath: String): File {
        val parts = relativePath.split('/')
        require(relativePath.length in 1..512 && parts.size <= 20 && parts.all {
            it.isNotBlank() && it != "." && it != ".." && it.none { c -> c == '\\' || c == ':' || c.isISOControl() }
        }) { "Workspace 文件路径无效" }
        require(!isSensitive(relativePath)) { "禁止保存敏感文件" }
        val base = root.absoluteFile
        var current: File? = base
        while (current != null) {
            require(!java.nio.file.Files.isSymbolicLink(current.toPath())) { "Workspace 不允许符号链接" }
            current = current.parentFile
        }
        var target = base
        for (part in parts) {
            target = File(target, part)
            require(!java.nio.file.Files.isSymbolicLink(target.toPath())) { "Workspace 不允许符号链接" }
        }
        require(target.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) { "Workspace 文件路径越界" }
        require(!target.exists() || target.isFile) { "目标不是普通文件" }
        return target
    }

    /** 已批准写入的最后一道边界：先完整校验配额，再原子替换，失败时保留旧文件。 */
    fun saveText(root: File, relativePath: String, content: String): String {
        val target = target(root, relativePath)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes) { "Workspace 文件过大" }
        val entries = if (root.exists()) scan(root) else emptyList()
        val existing = entries.firstOrNull { it.relativePath == relativePath }
        // 关键防护：禁止用文本覆盖已存在的二进制文件，保留二进制文件完整性
        require(existing == null || existing.textContent != null) { "禁止用文本覆盖 Workspace 中的已有二进制文件" }
        val newFileCount = entries.size + (if (existing != null) 0 else 1)
        require(newFileCount <= maxFileCount) { "Workspace 文件数量超过限制" }
        val otherFilesBytes = entries.filter { it.relativePath != relativePath }.sumOf { it.byteSize }
        require(otherFilesBytes + bytes.size <= maxTotalBytes) { "Workspace 总大小超过限制" }
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Workspace 目录创建失败" }
        val temporary = File.createTempFile(".everytalk-save-", ".tmp", target.parentFile)
        try {
            java.io.FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return relativePath
    }
    /** 读取单个 Workspace 文件，统一执行路径、敏感文件和大小校验。 */
    fun read(root: File, relativePath: String): ByteArray {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") && !relativePath.contains('\\')) { "Workspace 文件路径无效" }
        require(!isSensitive(relativePath)) { "禁止上传敏感文件" }
        val target = target(root, relativePath)
        require(target.isFile) { "Workspace 文件不存在或路径越界" }
        require(target.length() <= maxFileBytes) { "Workspace 文件过大" }
        // length 只是预检；实际流读取也必须限额，防止读取期间文件增长。
        return target.readAtMost(maxFileBytes)
    }

    /** 扫描工作区所有物理文件，严格校验符号链接、敏感文件与配额，并辨析 UTF-8 文本与二进制文件。 */
    private fun scan(root: File): List<WorkspaceEntry> {
        // 先检查原始路径及父目录；canonicalFile 会抹掉根符号链接，不能先解析再校验。
        target(root, ".everytalk-path-check")
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory) { "Local Workspace 不存在" }
        val files = canonicalRoot.walkTopDown()
            .onEnter { directory ->
                require(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { "Local Workspace 不允许符号链接目录" }
                require(canonicalRoot.toPath().relativize(directory.toPath()).nameCount <= 20) { "Workspace 目录过深" }
                true
            }
            .filter { it.isFile }
        var total = 0L
        var count = 0
        return files.map { file ->
            require(++count <= maxFileCount) { "Local Workspace 文件数量超过限制" }
            val canonical = file.canonicalFile
            require(canonical.toPath().startsWith(canonicalRoot.toPath())) { "Local Workspace 路径越界" }
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Local Workspace 不允许符号链接文件" }
            val relative = canonicalRoot.toPath().relativize(canonical.toPath()).toString()
                .replace(File.separatorChar, '/')
            require(!isSensitive(relative)) {
                "Local Workspace 包含禁止装载的敏感文件"
            }
            require(file.length() <= maxFileBytes) { "Local Workspace 文件过大" }
            // 使用相对路径作为快照协议；统一限额读取
            val bytes = read(root, relative)
            total += bytes.size
            require(total <= maxTotalBytes) { "Local Workspace 总大小超过限制" }
            val textContent = decodeUtf8OrNull(bytes)
            WorkspaceEntry(relative, bytes.size.toLong(), textContent)
        }.toList()
    }

    /** 装载工作区文本快照供 just-bash 使用。只映射普通文本文件，二进制文件保留在磁盘且不引发报错。 */
    fun load(root: File): Map<String, String> {
        val entries = scan(root)
        return entries.mapNotNull { entry ->
            entry.textContent?.let { entry.relativePath to it }
        }.toMap()
    }

    /** 将虚拟文件系统中属于 Workspace 的文本快照原子写回，避免半写文件。 */
    fun writeBack(root: File, before: Map<String, String>, after: Map<String, String>) {
        target(root, ".everytalk-path-check")
        val canonicalRoot = root.canonicalFile
        require(!java.nio.file.Files.isSymbolicLink(canonicalRoot.toPath())) { "Workspace 不允许符号链接根目录" }
        val existingEntries = if (canonicalRoot.isDirectory) scan(canonicalRoot) else emptyList()
        val binaryEntries = existingEntries.filter { it.textContent == null }
        val binaryPaths = binaryEntries.map { it.relativePath }.toSet()
        // 磁盘上已有的二进制文件必须被保留，统计总数量和总大小
        require(after.size + binaryEntries.size <= maxFileCount) { "Workspace 文件数量超过限制" }
        val totalBytes = after.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } + binaryEntries.sumOf { it.byteSize }
        require(totalBytes <= maxTotalBytes) { "Workspace 总大小超过限制" }

        // 先完整验证整个结果，再进行任何写入；这样配额或路径错误不会留下半套文件。
        val validated = after.map { (virtualPath, content) ->
            val target = target(canonicalRoot, virtualPath)
            require(content.toByteArray(Charsets.UTF_8).size <= maxFileBytes) { "Local Workspace 文件过大" }
            // 关键防护：禁止写入覆盖已有二进制文件
            require(virtualPath !in binaryPaths) { "禁止用文本覆盖 Workspace 中的已有二进制文件" }
            virtualPath to target
        }.toMap()
        validated.forEach { (virtualPath, target) ->
            val content = after.getValue(virtualPath)
            if (before[virtualPath] == content && target.isFile) return@forEach
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Workspace 目录创建失败" }
            val temporary = File.createTempFile(".everytalk-write-", ".tmp", parent)
            try {
                java.io.FileOutputStream(temporary).use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                java.nio.file.Files.move(
                    temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } finally { temporary.delete() }
        }
        // 待删除路径必须排除磁盘现存的二进制文件，即便 before 是过期快照也不能误删已有二进制
        val toDelete = (before.keys - after.keys).filter { it !in binaryPaths }
        toDelete.forEach { virtualPath ->
            val target = target(canonicalRoot, virtualPath)
            if (target.exists()) check(target.delete()) { "Local Workspace 文件删除失败" }
        }
    }

    private fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun isSensitive(path: String): Boolean {
        val lower = path.replace(File.separatorChar, '/').lowercase()
        return lower == ".env" || lower.startsWith(".env/") || lower.startsWith(".env.") ||
            lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12") ||
            lower.contains("secret") || lower.contains("credential") || lower.startsWith(".git/")
    }
}
