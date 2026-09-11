package com.android.everytalk.data.computer

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 部署包中的单个文件摘要，不包含绝对路径。 */
data class WorkerPackageFile(
    val relativePath: String,
    val bytes: ByteArray,
    val sha256: String,
)

data class WorkerPackage(
    val files: List<WorkerPackageFile>,
    val requestHash: String,
    val totalBytes: Long,
    val entryPoint: String = "worker.js",
    val bindings: List<WorkerBinding> = emptyList(),
)

/** Worker 的非敏感资源绑定；Secret 不得进入 Workspace 部署包。 */
data class WorkerBinding(
    val name: String,
    val type: String,
    val resourceId: String,
)

@Serializable
private data class WorkerProjectConfig(
    val entry: String? = null,
    val bindings: List<WorkerProjectBinding> = emptyList(),
)

@Serializable
private data class WorkerProjectBinding(
    val name: String,
    val type: String,
    val id: String,
)

private const val WORKER_PROJECT_CONFIG = ".everytalk/worker.json"

/**
 * 从当前 Workspace 构建 Worker 部署包。
 * 所有路径必须位于 root，敏感文件直接拒绝，避免把本地密钥上传到 Cloudflare。
 */
class WorkerPackageBuilder(
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val maxTotalBytes: Long = 25L * 1024 * 1024,
    private val maxFileCount: Int = 500,
) {
    fun build(root: File): WorkerPackage {
        require(root.isDirectory) { "Worker Workspace 不是目录" }
        val canonicalRoot = root.canonicalFile
        require(!java.nio.file.Files.isSymbolicLink(root.toPath())) { "Worker Workspace 不允许符号链接根目录" }
        val projectConfig = readProjectConfig(canonicalRoot)
        val entryPoint = resolveEntryPoint(canonicalRoot, projectConfig?.entry)
        val bindings = validateBindings(projectConfig?.bindings.orEmpty())
        val candidates = canonicalRoot.walkTopDown()
            .onEnter { directory ->
                require(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { "Worker Workspace 不允许符号链接目录" }
                val depth = canonicalRoot.toPath().relativize(directory.canonicalFile.toPath()).nameCount
                require(depth <= 20) { "Worker 目录深度超过限制" }
                val relative = canonicalRoot.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/').lowercase()
                // 构建缓存和依赖目录不属于部署源文件；直接跳过，避免把无关大目录
                // 当成 Secret 失败，也避免扫描它们带来的不必要 IO。
                relative.isBlank() || relative.split('/').none { it in setOf(".git", "node_modules", "build", ".cache", ".gradle") }
            }
            .filter { it.isFile && it.relativeTo(canonicalRoot).invariantSeparatorsPath != WORKER_PROJECT_CONFIG }
            .take(maxFileCount + 1).toList()
        require(candidates.size <= maxFileCount) { "Worker 文件数量超过限制" }
        var totalBytes = 0L
        val files = candidates.map { file ->
            val canonical = file.canonicalFile
            require(canonical.toPath().startsWith(canonicalRoot.toPath())) { "Worker 文件路径越界" }
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Worker 文件不允许符号链接" }
            val relative = canonicalRoot.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/')
            require(!isSensitive(relative)) { "禁止上传敏感文件：$relative" }
            require(file.length() <= maxFileBytes) { "Worker 文件过大：$relative" }
            val remaining = minOf(maxFileBytes, maxTotalBytes - totalBytes)
            require(remaining >= 0 && file.length() <= remaining) { "Worker 部署包过大" }
            val bytes = readBounded(file, remaining)
            require(!containsSensitiveContent(bytes, relative)) { "文件内容疑似包含 Secret：$relative" }
            totalBytes += bytes.size
            if (relative == entryPoint) {
                val source = bytes.toString(Charsets.UTF_8)
                require(source.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Worker 入口必须是有效 UTF-8" }
                require(source.contains("export")) { "Worker 入口必须使用模块格式" }
            }
            WorkerPackageFile(relative, bytes, sha256(bytes))
        }.sortedBy { it.relativePath }
        val total = files.sumOf { it.bytes.size.toLong() }
        require(total <= maxTotalBytes) { "Worker 部署包过大" }
        val manifest = buildString {
            append("entry=").append(entryPoint).append('\n')
            bindings.forEach { append("binding=").append(it.name).append(':').append(it.type).append(':').append(it.resourceId).append('\n') }
            files.forEach { append(it.relativePath).append(':').append(it.sha256).append('\n') }
        }
        return WorkerPackage(files, sha256(manifest.toByteArray()), total, entryPoint, bindings)
    }

    /**
     * 入口优先使用 App 自己的结构化配置；没有配置时只接受明确的常见模块入口。
     * TypeScript 需要构建链生成 JavaScript，当前 App 没有这条链，因此明确拒绝而不上传源码。
     */
    private fun resolveEntryPoint(root: File, configured: String?): String {
        require(configured == null || configured.isNotBlank()) { "Worker 配置不能使用空入口" }
        if (configured?.endsWith(".ts", ignoreCase = true) == true) {
            throw IllegalArgumentException("Worker TypeScript 入口需要先构建为 JavaScript")
        }
        val candidates = if (configured != null) listOf(configured) else listOf(
            "worker.js", "src/index.js", "src/index.mjs", "index.js",
        )
        val existing = candidates.filter { candidate ->
            val normalized = normalizeRelativePath(candidate)
            val file = File(root, normalized)
            file.isFile && file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
        }
        require(existing.size == 1) { "Worker 入口缺失或不唯一，请在 .everytalk/worker.json 的 entry 中明确指定" }
        val entry = normalizeRelativePath(existing.single())
        require(entry.endsWith(".js") || entry.endsWith(".mjs")) { "Worker 入口必须是 JavaScript 模块" }
        return entry
    }

    private fun readProjectConfig(root: File): WorkerProjectConfig? {
        val file = File(root, WORKER_PROJECT_CONFIG)
        val configParent = file.parentFile ?: throw IllegalArgumentException("Worker 配置目录无效")
        require(!java.nio.file.Files.isSymbolicLink(configParent.toPath())) { "Worker 配置目录不允许符号链接" }
        if (!file.exists()) return null
        require(file.canonicalFile.toPath().startsWith(root.toPath())) { "Worker 配置路径越界" }
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Worker 配置文件无效" }
        require(file.length() <= 64 * 1024) { "Worker 配置文件过大" }
        val raw = readBounded(file, 64L * 1024)
        require(raw.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(raw)) { "Worker 配置必须是 UTF-8" }
        return runCatching { Json.decodeFromString<WorkerProjectConfig>(raw.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Worker 配置格式无效") }
    }

    private fun validateBindings(values: List<WorkerProjectBinding>): List<WorkerBinding> {
        require(values.size <= 100) { "Worker binding 数量超过限制" }
        val result = values.map { binding ->
            require(binding.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) { "Worker binding 名称无效" }
            require(binding.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Worker binding 资源引用无效" }
            val type = when (binding.type) {
                "d1", "kv_namespace", "r2_bucket", "durable_object_namespace", "queue" -> binding.type
                else -> throw IllegalArgumentException("Worker binding 类型不受支持：" + binding.type)
            }
            WorkerBinding(binding.name, type, binding.id)
        }
        require(result.map { it.name }.distinct().size == result.size) { "Worker binding 名称重复" }
        return result.sortedWith(compareBy<WorkerBinding> { it.name }.thenBy { it.type }.thenBy { it.resourceId })
    }

    private fun normalizeRelativePath(value: String): String {
        require(value.isNotBlank() && value.length <= 256 && !value.startsWith('/') &&
            '\\' !in value && ':' !in value && value.none { it.code < 32 }) { "Worker 入口路径无效" }
        val normalized = value.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        require(normalized.isNotEmpty() && normalized.split('/').none { it == ".." }) { "Worker 入口路径越界" }
        return normalized
    }

    private fun isSensitive(path: String): Boolean {
        val lower = path.lowercase()
        return lower.split('/').any { it == ".env" || it.startsWith(".env.") || it == ".git" ||
            it == ".ssh" || it == "node_modules" || it == "build" || it == ".cache" } ||
            lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12") ||
            lower.endsWith(".crt") || lower.endsWith(".cer") || lower.endsWith(".der") ||
            lower == "id_rsa" || lower == "id_ed25519" || lower.contains("secret") || lower.contains("credential") || lower.contains("token") ||
            lower.startsWith(".git/") || lower.startsWith("build/")
    }

    /** 文件名无法覆盖配置误命名场景；只拦截高置信凭据格式，避免把普通 Worker 代码误判为 Secret。 */
    private fun containsSensitiveContent(bytes: ByteArray, path: String): Boolean {
        if (bytes.isEmpty() || path.endsWith(".wasm", ignoreCase = true)) return false
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return false
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return false
        return text.contains("-----BEGIN PRIVATE KEY-----") ||
            Regex("(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*[\\\"']?[A-Za-z0-9_./+=-]{16,}").containsMatchIn(text)
    }

    /** 按剩余额度流式读取；文件在检查长度后增长也不能突破包总量限制。 */
    private fun readBounded(file: File, limit: Long): ByteArray = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Worker 文件或部署包超过大小限制" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
