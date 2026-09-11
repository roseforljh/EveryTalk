package com.android.everytalk.data.computer

import java.io.File
import com.android.everytalk.util.storage.readAtMost

/** 将 App 私有 Workspace 映射为 just-bash 请求中的相对路径文件集合。 */
class LocalWorkspaceFileBridge(
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val maxTotalBytes: Long = 25L * 1024 * 1024,
    private val maxFileCount: Int = 500,
) {
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
        val snapshot = if (root.exists()) load(root) else emptyMap()
        require(snapshot.size + (if (relativePath in snapshot) 0 else 1) <= maxFileCount) { "Workspace 文件数量超过限制" }
        require(snapshot.filterKeys { it != relativePath }.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } + bytes.size <= maxTotalBytes) { "Workspace 总大小超过限制" }
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
    fun load(root: File): Map<String, String> {
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
        return files.associate { file ->
            require(++count <= maxFileCount) { "Local Workspace 文件数量超过限制" }
            val canonical = file.canonicalFile
            require(canonical.toPath().startsWith(canonicalRoot.toPath())) { "Local Workspace 路径越界" }
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Local Workspace 不允许符号链接文件" }
            require(!isSensitive(canonicalRoot.toPath().relativize(canonical.toPath()).toString())) {
                "Local Workspace 包含禁止装载的敏感文件"
            }
            require(file.length() <= maxFileBytes) { "Local Workspace 文件过大" }
            val relative = canonicalRoot.toPath().relativize(canonical.toPath()).toString()
                .replace(File.separatorChar, '/')
            // 使用相对路径作为快照协议；just-bash 会以 cwd 解析它，避免把宿主路径语义泄露给运行时。
            val bytes = read(root, relative)
            total += bytes.size
            require(total <= maxTotalBytes) { "Local Workspace 总大小超过限制" }
            val content = bytes.toString(Charsets.UTF_8)
            require(content.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Workspace 包含非 UTF-8 文件" }
            relative to content
        }
    }

    /** 将虚拟文件系统中属于 Workspace 的文本快照原子写回，避免半写文件。 */
    fun writeBack(root: File, before: Map<String, String>, after: Map<String, String>) {
        target(root, ".everytalk-path-check")
        val canonicalRoot = root.canonicalFile
        require(!java.nio.file.Files.isSymbolicLink(canonicalRoot.toPath())) { "Workspace 不允许符号链接根目录" }
        require(after.size <= maxFileCount) { "Workspace 文件数量超过限制" }
        val totalBytes = after.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        require(totalBytes <= maxTotalBytes) { "Workspace 总大小超过限制" }

        // 先完整验证整个结果，再进行任何写入；这样配额或路径错误不会留下半套文件。
        val validated = after.map { (virtualPath, content) ->
            val target = target(canonicalRoot, virtualPath)
            require(content.toByteArray(Charsets.UTF_8).size <= maxFileBytes) { "Local Workspace 文件过大" }
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
        (before.keys - after.keys).forEach { virtualPath ->
            val target = target(canonicalRoot, virtualPath)
            if (target.exists()) check(target.delete()) { "Local Workspace 文件删除失败" }
        }
    }

    private fun isSensitive(path: String): Boolean {
        val lower = path.replace(File.separatorChar, '/').lowercase()
        return lower == ".env" || lower.startsWith(".env/") || lower.startsWith(".env.") ||
            lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12") ||
            lower.contains("secret") || lower.contains("credential") || lower.startsWith(".git/")
    }
}
