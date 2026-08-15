package com.android.everytalk.data.skill

import android.content.Context
import android.util.Base64
import com.android.everytalk.BuildConfig
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

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
    private val proxyBaseUrl = BuildConfig.SKILL_CATALOG_BASE_URL.trim().trimEnd('/')
    @Volatile
    var usedOfflineCache: Boolean = false
        private set

    fun collection(type: SkillCatalogCollection): List<RemoteSkillCatalogItem> {
        val response = getJson(
            HttpUrl.Builder()
                .scheme("https")
                .host(SKILLS_HOST)
                .addPathSegments("api/skills/${type.path}/1")
                .build()
                .let { direct -> proxyUrl("collections/${type.proxyPath}") ?: direct },
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
                .build()
                .let { direct -> proxyUrl("search")?.newBuilder()?.addQueryParameter("q", query.trim())?.build() ?: direct },
        )
        return json.decodeFromString<RemoteSkillCatalogResponse>(response).skills
    }

    fun detail(item: RemoteSkillCatalogItem): RemoteSkillDetail {
        val url = proxyUrl(encodeRemoteId(item)) ?: error("未配置 Skill 转发服务")
        return json.decodeFromString(getJson(url))
    }

    fun currentHash(item: RemoteSkillCatalogItem): RemoteSkillHash {
        val url = proxyUrl("${encodeRemoteId(item)}/hash") ?: error("未配置 Skill 转发服务")
        return json.decodeFromString(getJson(url))
    }

    /**
     * 打开来源仓库的 ZIP 流。调用方必须在回调内读完，避免响应提前关闭。
     */
    suspend fun <T> withArchive(item: RemoteSkillCatalogItem, block: suspend (InputStream) -> T): T {
        require(item.githubRepository != null) { "当前只支持从 GitHub 下载 Skill" }
        val archiveUrl = proxyUrl("${encodeRemoteId(item)}/archive")?.toString()
            ?: "https://codeload.github.com/${item.source}/zip/HEAD"
        val request = Request.Builder()
            .url(archiveUrl)
            .header("User-Agent", "EveryTalk-Skill-Installer")
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Skill 仓库下载失败：HTTP ${response.code}" }
            val body = response.body
            val declaredSize = body.contentLength()
            require(declaredSize < 0 || declaredSize <= MAX_SKILL_BYTES) { "Skill 仓库压缩包超过 100 MB" }
            return block(body.byteStream())
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

    private fun proxyUrl(path: String): HttpUrl? = proxyBaseUrl.takeIf(String::isNotBlank)
        ?.toHttpUrl()
        ?.newBuilder()
        ?.addPathSegments(path)
        ?.build()

    private fun cacheFile(url: HttpUrl) = cacheDirectory?.resolve(
        MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray()).joinToString("") { "%02x".format(it) } + ".json",
    )

    private fun encodeRemoteId(item: RemoteSkillCatalogItem): String = Base64.encodeToString(
        "${item.source}#${item.skillId}".toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private companion object {
        const val SKILLS_HOST = "skills.sh"
    }
}

private val SkillCatalogCollection.proxyPath: String
    get() = when (this) {
        SkillCatalogCollection.POPULAR -> "popular"
        SkillCatalogCollection.TRENDING -> "trending"
        SkillCatalogCollection.OFFICIAL -> "official"
    }
