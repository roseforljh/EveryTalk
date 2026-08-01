package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.normalizeModelEndpointIdentity
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MODEL_CAPABILITY_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L

@Serializable
private data class CachedModelCapability(
    val candidate: ModelCapabilityCandidate,
    val cachedAtEpochMillis: Long,
)

internal class ModelCapabilityCache(
    private val cacheFile: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = MODEL_CAPABILITY_CACHE_TTL_MILLIS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun put(catalog: List<ModelCapabilityCandidate>) {
        val now = nowEpochMillis()
        val incoming = catalog.mapNotNull { candidate ->
            val endpoint = candidate.endpointIdentity
                ?.let(::normalizeModelEndpointIdentity)
                ?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            CachedModelCapability(
                candidate = candidate.copy(endpointIdentity = endpoint),
                cachedAtEpochMillis = now,
            )
        }
        if (incoming.isEmpty()) return
        val merged = (readEntries().filter { it.isFresh(now) } + incoming)
            .associateBy { it.cacheKey() }
            .values
            .toList()
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(json.encodeToString(merged), Charsets.UTF_8)
    }

    @Synchronized
    fun get(
        protocol: ModelParameterProtocol,
        apiAddress: String,
    ): List<ModelCapabilityCandidate> {
        val now = nowEpochMillis()
        val endpoint = normalizeModelEndpointIdentity(apiAddress)
        return readEntries()
            .asSequence()
            .filter { it.isFresh(now) }
            .map(CachedModelCapability::candidate)
            .filter { it.protocol == protocol }
            .filter { normalizeModelEndpointIdentity(it.endpointIdentity.orEmpty()) == endpoint }
            .map { candidate ->
                candidate.copy(
                    source = ModelCapabilitySource.LOCAL_CACHE,
                    cachedSource = candidate.cachedSource ?: candidate.source,
                )
            }
            .toList()
    }

    private fun readEntries(): List<CachedModelCapability> = runCatching {
        if (!cacheFile.isFile) return@runCatching emptyList()
        json.decodeFromString<List<CachedModelCapability>>(cacheFile.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    private fun CachedModelCapability.isFresh(now: Long): Boolean =
        cachedAtEpochMillis >= now - ttlMillis

    private fun CachedModelCapability.cacheKey(): String = listOf(
        candidate.protocol.name,
        normalizeModelEndpointIdentity(candidate.endpointIdentity.orEmpty()),
        candidate.modelId.trim().lowercase(),
    ).joinToString("\u0000")
}
