package com.android.everytalk.data.skill

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

enum class SkillCatalogCollection(val path: String) {
    POPULAR("all-time"),
    TRENDING("trending"),
    OFFICIAL("all-time"),
}

/**
 * skills.sh 云目录客户端。
 * 搜索和榜单直接使用官方公开接口，Skill 文件始终从条目声明的 GitHub 仓库下载。
 */
class SkillCatalogClient(
    context: Context? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val cacheDirectory = context?.applicationContext?.cacheDir?.resolve("skill-catalog")
    @Volatile
    var usedOfflineCache: Boolean = false
        private set

    fun collection(type: SkillCatalogCollection): List<RemoteSkillCatalogItem> {
        val response = getJson(
            HttpUrl.Builder()
                .scheme("https")
                .host(SKILLS_HOST)
                .addPathSegments("api/skills/${type.path}/1")
                .build(),
        )
        val items = json.decodeFromString<RemoteSkillCatalogResponse>(response).skills
        return if (type == SkillCatalogCollection.OFFICIAL) items.filter(RemoteSkillCatalogItem::isOfficial) else items
    }

    fun search(query: String): List<RemoteSkillCatalogItem> {
        if (query.isBlank()) return emptyList()
        val response = getJson(
            HttpUrl.Builder()
                .scheme("https")
                .host(SKILLS_HOST)
                .addPathSegments("api/search")
                .addQueryParameter("q", query.trim())
                .addQueryParameter("limit", "100")
                .build(),
        )
        return json.decodeFromString<RemoteSkillCatalogResponse>(response).skills
    }

    /**
     * 直接读取 GitHub 默认分支和完整 Tree，发现仓库内所有独立 SKILL.md。
     * 资源目录中的示例 SKILL.md 会被排除，避免把文档样例误装成可调用 Skill。
     */
    fun packageDetail(item: RemoteSkillPackageCatalogItem): RemoteSkillPackageDetail {
        val repository = requireGithubRepository(item.source)
        val branch = "HEAD"
        val treeUrl = githubApiUrl("repos/${repository[0]}/${repository[1]}/git/trees/$branch")
            .newBuilder()
            .addQueryParameter("recursive", "1")
            .build()
        val tree = json.decodeFromString<GithubTreeResponse>(getJson(treeUrl))
        require(!tree.truncated) { "Skill 仓库文件树过大，GitHub 未返回完整结果" }
        val blobs = tree.tree.filter { it.type == "blob" && it.size >= 0 }
        val discoveredRoots = blobs.asSequence()
            .map(GithubTreeEntry::path)
            .filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
            .map { it.removeSuffix("/SKILL.md").takeUnless(String::isBlank).orEmpty() }
            .filterNot(::isIgnoredSkillRoot)
            .distinct()
            .sorted()
            .toList()
        // 同一仓库可能为不同 Agent 放多套兼容副本。按目录名去重，优先标准 skills/ 路径。
        val roots = discoveredRoots
            .groupBy { it.substringAfterLast('/').lowercase() }
            .values
            .map { duplicates -> duplicates.minWith(compareBy(::skillRootPreference).thenBy(String::length)) }
            .sorted()
        require(roots.isNotEmpty()) { "仓库中没有找到有效的 SKILL.md" }

        val skills = roots.map { root ->
            val markdownPath = repositoryPath(root, "SKILL.md")
            val markdown = getText(remoteRepositoryFileUrl(item.source, branch, markdownPath))
            val frontmatter = parseSkillFrontmatter(markdown)
            val name = frontmatter["name"]?.trim()?.takeIf(String::isNotBlank)
                ?: root.substringAfterLast('/').ifBlank { item.name }
            val description = frontmatter["description"]?.trim()?.takeIf(String::isNotBlank)
                ?: markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: "用户添加的 Skill"
            val files = blobs.asSequence()
                .filter { entry -> belongsToSkillRoot(entry.path, root, roots) }
                .map { entry ->
                    RemoteSkillPackageFile(
                        path = relativeToSkillRoot(entry.path, root),
                        repositoryPath = entry.path,
                        size = entry.size,
                    )
                }
                .sortedBy(RemoteSkillPackageFile::path)
                .toList()
            require(files.any { it.path == "SKILL.md" }) { "Skill 缺少 SKILL.md：$name" }
            RemoteSkillPackageChild(
                name = name,
                description = description,
                sourcePath = root.ifBlank { "." },
                invocationMode = if (frontmatter["disable-model-invocation"].toBoolean()) {
                    SkillInvocationMode.MANUAL_ONLY
                } else {
                    SkillInvocationMode.AUTO
                },
                files = files,
            )
        }
        val allFiles = skills.flatMap(RemoteSkillPackageChild::files)
        require(allFiles.size <= MAX_SKILL_FILES) { "Skill 包文件数超过 $MAX_SKILL_FILES" }
        require(allFiles.sumOf(RemoteSkillPackageFile::size) <= MAX_SKILL_BYTES) { "Skill 包超过 100 MB" }
        val contentHash = MessageDigest.getInstance("SHA-256").run {
            skills.forEach { skill ->
                update(skill.sourcePath.toByteArray(Charsets.UTF_8))
                skill.files.forEach { file ->
                    val entry = blobs.first { it.path == file.repositoryPath }
                    update(file.repositoryPath.toByteArray(Charsets.UTF_8))
                    update(0)
                    update(entry.sha.toByteArray(Charsets.US_ASCII))
                    update(0)
                }
            }
            digest().joinToString("") { "%02x".format(it) }
        }
        return RemoteSkillPackageDetail(
            packageId = item.packageId,
            name = item.name,
            source = item.source,
            sourceRepository = "https://github.com/${item.source}",
            branch = branch,
            contentHash = contentHash,
            skills = skills,
        )
    }

    /** 只下载当前 Skill 清单中的一个文件，避免为了几 KB 的规则拉取整个 GitHub 仓库。 */
    fun downloadRemotePackageFile(
        detail: RemoteSkillPackageDetail,
        entry: RemoteSkillPackageFile,
        target: File,
    ) {
        val fileUrl = remoteRepositoryFileUrl(detail.source, detail.branch, entry.repositoryPath)
        val request = Request.Builder()
            .url(fileUrl)
            .header("User-Agent", "EveryTalk-Skill-Installer")
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Skill 文件下载失败：${entry.path} · HTTP ${response.code}" }
            val body = response.body
            val declaredSize = body.contentLength()
            require(declaredSize < 0 || declaredSize == entry.size) { "Skill 文件已变化，请重新打开详情后下载" }
            target.parentFile?.mkdirs()
            var received = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        require(received <= entry.size) { "Skill 文件已变化，请重新打开详情后下载" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(received == entry.size) { "Skill 文件下载不完整：${entry.path}" }
        }
    }

    private fun getJson(url: HttpUrl): String {
        val cacheFile = cacheFile(url)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "EveryTalk-Skill-Catalog")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Skill 云目录请求失败：HTTP ${response.code}" }
                val body = response.body.string()
                usedOfflineCache = false
                cacheFile?.let { file -> file.parentFile?.mkdirs(); file.writeText(body, Charsets.UTF_8) }
                return body
            }
        } catch (error: Exception) {
            cacheFile?.takeIf { it.isFile }?.let {
                usedOfflineCache = true
                return it.readText(Charsets.UTF_8)
            }
            throw if (error is IOException) IOException("网络不可用，且没有本地目录缓存", error) else error
        }
    }

    private fun cacheFile(url: HttpUrl) = cacheDirectory?.resolve(
        MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray()).joinToString("") { "%02x".format(it) } + ".json",
    )

    private fun getText(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("User-Agent", "EveryTalk-Skill-Installer").build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Skill 文件下载失败：HTTP ${response.code}" }
            return response.body.string()
        }
    }

    private companion object {
        const val SKILLS_HOST = "skills.sh"
    }
}

@Serializable
private data class GithubTreeResponse(
    val sha: String,
    val truncated: Boolean = false,
    val tree: List<GithubTreeEntry> = emptyList(),
)

@Serializable
private data class GithubTreeEntry(
    val path: String,
    val type: String,
    val sha: String,
    val size: Long = 0,
)

private val ignoredSkillRootSegments = setOf(
    ".git", ".github", "node_modules", "build", "dist", "references", "templates",
    "assets", "scripts", "examples", "example", "test", "tests",
)

private fun isIgnoredSkillRoot(root: String): Boolean =
    root.split('/').any { it.lowercase() in ignoredSkillRootSegments }

private fun skillRootPreference(root: String): Int = when {
    root == "skills" || root.startsWith("skills/") -> 0
    root.substringBefore('/').startsWith('.') -> 2
    else -> 1
}

private fun belongsToSkillRoot(path: String, root: String, roots: List<String>): Boolean {
    if (root.isEmpty()) {
        if (path == "SKILL.md") return true
        val first = path.substringBefore('/').lowercase()
        if (first !in setOf("scripts", "references", "templates", "assets")) return false
    } else if (path != "$root/SKILL.md" && !path.startsWith("$root/")) {
        return false
    }
    return roots.none { nested ->
        nested != root && nested.isNotEmpty() &&
            (path == "$nested/SKILL.md" || path.startsWith("$nested/"))
    }
}

private fun relativeToSkillRoot(path: String, root: String): String =
    if (root.isEmpty()) path else path.removePrefix("$root/")

private fun repositoryPath(root: String, relative: String): String =
    if (root.isBlank()) relative else "$root/$relative"

private fun requireGithubRepository(source: String): List<String> = source.split('/').also { repository ->
    require(repository.size == 2 && repository.all { it.isNotBlank() }) { "Skill 来源仓库无效" }
}

private fun githubApiUrl(path: String): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host("api.github.com")
    .addPathSegments(path)
    .build()

internal fun remoteRepositoryFileUrl(source: String, branch: String, repositoryPath: String): HttpUrl {
    val repository = requireGithubRepository(source)
    val path = safeRemotePathSegments(repositoryPath, allowRoot = false)
    return HttpUrl.Builder()
        .scheme("https")
        .host("raw.githubusercontent.com")
        .addPathSegment(repository[0])
        .addPathSegment(repository[1])
        .addPathSegment(branch)
        .apply { path.forEach(::addPathSegment) }
        .build()
}

private fun safeRemotePathSegments(value: String, allowRoot: Boolean): List<String> {
    val normalized = value.trim().replace('\\', '/').trim('/')
    if (allowRoot && (normalized.isBlank() || normalized == ".")) return emptyList()
    val segments = normalized.split('/')
    require(segments.isNotEmpty() && segments.all { it.isNotBlank() && it != "." && it != ".." }) {
        "Skill 文件路径无效"
    }
    return segments
}
