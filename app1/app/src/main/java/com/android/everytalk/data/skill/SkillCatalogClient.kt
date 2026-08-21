package com.android.everytalk.data.skill

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private val client: OkHttpClient = sharedClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val cacheDirectory = context?.applicationContext?.filesDir?.resolve("skill-catalog")
    /**
     * 详情里包含完整仓库文件树，单项可能很大，必须限制常驻数量。
     * 使用访问顺序淘汰，用户刚看过的条目仍能直接复用。
     */
    private val detailCache = object : LinkedHashMap<String, CachedPackageDetail>(DETAIL_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPackageDetail>?): Boolean =
            size > DETAIL_CACHE_MAX_ENTRIES
    }
    private val collectionPageLocks = ConcurrentHashMap<String, Any>()
    @Volatile
    var usedOfflineCache: Boolean = false
        private set

    fun collection(type: SkillCatalogCollection): List<RemoteSkillCatalogItem> {
        return collectionPage(type, 1).skills
    }

    /**
     * 读取一页榜单并保留分页信息。
     * 页面先展示第一页，滚动到底部后自动追加后续页，避免一次等待全部云目录。
     */
    fun collectionPage(type: SkillCatalogCollection, page: Int): RemoteSkillCatalogPage {
        require(page > 0) { "Skill 云目录页码无效" }
        val lock = collectionPageLocks.getOrPut("${type.name}:$page") { Any() }
        return synchronized(lock) {
            decodeCollectionPage(type, page, getJson(collectionUrl(type, page)))
        }
    }

    /** 读取磁盘中的旧页面，让重复进入云目录时无需先等待网络。 */
    fun cachedCollectionPage(type: SkillCatalogCollection, page: Int): RemoteSkillCatalogPage? {
        require(page > 0) { "Skill 云目录页码无效" }
        val cached = cacheFile(collectionUrl(type, page))?.takeIf(File::isFile)?.readText(Charsets.UTF_8) ?: return null
        return runCatching { decodeCollectionPage(type, page, cached) }.getOrNull()
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
        synchronized(detailCache) {
            detailCache[item.packageId]
                ?.takeIf { System.currentTimeMillis() - it.savedAt < DETAIL_CACHE_MILLIS }
                ?.let { return it.detail }
        }
        val repository = requireGithubRepository(item.source)
        val commit = json.decodeFromString<GithubCommitResponse>(
            getJson(githubApiUrl("repos/${repository[0]}/${repository[1]}/commits/HEAD")),
        )
        val treeUrl = githubApiUrl("repos/${repository[0]}/${repository[1]}/git/trees/${commit.sha}")
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
            // 详情阶段只使用目录名。真实名称、简介和调用方式在文件下载完成后统一校验读取。
            val name = root.substringAfterLast('/').ifBlank { item.name }
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
                description = "安装后读取完整说明",
                sourcePath = root.ifBlank { "." },
                invocationMode = SkillInvocationMode.AUTO,
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
        val detail = RemoteSkillPackageDetail(
            packageId = item.packageId,
            name = item.name,
            source = item.source,
            sourceRepository = "https://github.com/${item.source}",
            branch = commit.sha,
            contentHash = contentHash,
            skills = skills,
        )
        synchronized(detailCache) {
            detailCache[item.packageId] = CachedPackageDetail(System.currentTimeMillis(), detail)
        }
        return detail
    }

    /**
     * 一次下载当前仓库的固定 commit ZIP。
     * ZIP 只在用户确认安装或更新后请求，云目录浏览阶段不会触发。
     */
    fun downloadRemotePackageArchive(
        detail: RemoteSkillPackageDetail,
        target: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val request = Request.Builder()
            .url(remoteRepositoryArchiveUrl(detail.source, detail.branch))
            .header("Accept", "application/zip")
            .header("User-Agent", "EveryTalk-Skill-Installer")
            .build()
        val call = client.newCall(request).apply {
            timeout().timeout(ARCHIVE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        try {
            call.execute().use { response ->
                require(response.isSuccessful) { "Skill 压缩包下载失败：HTTP ${response.code}" }
                val body = response.body
                val declaredSize = body.contentLength()
                require(declaredSize < 0 || declaredSize <= MAX_REMOTE_ARCHIVE_BYTES) { "Skill 仓库压缩包超过 200 MB" }
                target.parentFile?.mkdirs()
                var received = 0L
                onProgress(0, declaredSize)
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            received += read
                            require(received <= MAX_REMOTE_ARCHIVE_BYTES) { "Skill 仓库压缩包超过 200 MB" }
                            output.write(buffer, 0, read)
                            onProgress(received, declaredSize)
                        }
                    }
                }
                require(received > 0) { "Skill 压缩包为空" }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun getJson(url: HttpUrl): String {
        val cacheFile = cacheFile(url)
        // skills.sh 榜单变化不需要秒级刷新。短时间内直接复用缓存，避免每次进入页面都等待远端生成榜单。
        cacheFile
            ?.takeIf { url.host == SKILLS_HOST && it.isFile && System.currentTimeMillis() - it.lastModified() < CATALOG_CACHE_MILLIS }
            ?.let {
                usedOfflineCache = false
                return it.readText(Charsets.UTF_8)
            }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "EveryTalk-Skill-Catalog")
            .build()
        try {
            val call = client.newCall(request).apply {
                timeout().timeout(METADATA_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            call.execute().use { response ->
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

    private fun collectionUrl(type: SkillCatalogCollection, page: Int): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(SKILLS_HOST)
        .addPathSegments("api/skills/${type.path}/$page")
        .build()

    private fun decodeCollectionPage(
        type: SkillCatalogCollection,
        page: Int,
        responseBody: String,
    ): RemoteSkillCatalogPage {
        val response = json.decodeFromString<RemoteSkillCatalogResponse>(responseBody)
        val items = if (type == SkillCatalogCollection.OFFICIAL) {
            response.skills.filter(RemoteSkillCatalogItem::isOfficial)
        } else {
            response.skills
        }
        return RemoteSkillCatalogPage(items, page, response.total, response.skills.size, response.hasMore)
    }

    private companion object {
        // 三个 Skill 页面共享连接池，避免每次页面跳转都新建 OkHttp 线程和连接。
        val sharedClient = OkHttpClient()
        const val SKILLS_HOST = "skills.sh"
        const val CATALOG_CACHE_MILLIS = 10 * 60 * 1_000L
        const val DETAIL_CACHE_MILLIS = 5 * 60 * 1_000L
        const val DETAIL_CACHE_MAX_ENTRIES = 24
        const val METADATA_CALL_TIMEOUT_SECONDS = 15L
        const val ARCHIVE_CALL_TIMEOUT_SECONDS = 120L
        const val MAX_REMOTE_ARCHIVE_BYTES = 200L * 1024L * 1024L
    }
}

private data class CachedPackageDetail(
    val savedAt: Long,
    val detail: RemoteSkillPackageDetail,
)

@Serializable
private data class GithubCommitResponse(val sha: String)

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

private fun requireGithubRepository(source: String): List<String> = source.split('/').also { repository ->
    require(repository.size == 2 && repository.all { it.isNotBlank() }) { "Skill 来源仓库无效" }
}

private fun githubApiUrl(path: String): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host("api.github.com")
    .addPathSegments(path)
    .build()

internal fun remoteRepositoryArchiveUrl(source: String, commit: String): HttpUrl {
    val repository = requireGithubRepository(source)
    require(commit.isNotBlank() && commit.all { it.isLetterOrDigit() || it in setOf('-', '_', '.') }) {
        "Skill 版本无效"
    }
    return HttpUrl.Builder()
        .scheme("https")
        .host("codeload.github.com")
        .addPathSegment(repository[0])
        .addPathSegment(repository[1])
        .addPathSegment("zip")
        .addPathSegment(commit)
        .build()
}
