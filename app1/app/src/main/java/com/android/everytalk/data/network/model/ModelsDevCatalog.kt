package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.MAX_MODEL_TOKEN_LIMIT
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal const val MODELS_DEV_URL = "https://models.dev/api.json"
internal const val MAX_MODELS_DEV_RESPONSE_BYTES = 6L * 1024L * 1024L

internal data class ModelsDevEntry(
    val providerId: String,
    val providerName: String,
    val providerApiHost: String?,
    val capability: ModelCapabilityCandidate,
)

internal data class ModelsDevIndex(
    val byModelId: Map<String, List<ModelsDevEntry>>,
)

internal class ModelsDevCatalog(
    private val cacheFile: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = MODEL_CAPABILITY_CACHE_TTL_MILLIS,
) {
    private data class MemoryEntry(
        val index: ModelsDevIndex,
        val loadedAtEpochMillis: Long,
    )

    private val mutex = Mutex()
    @Volatile
    private var memoryEntry: MemoryEntry? = null

    suspend fun findCapabilities(
        modelId: String,
        providerHint: String,
        apiAddress: String,
        protocol: ModelParameterProtocol,
        fetchRemote: suspend () -> String,
    ): List<ModelCapabilityCandidate> {
        val index = loadIndex(fetchRemote) ?: return emptyList()
        val normalized = normalizeCatalogModelId(modelId)
        val candidates = buildList {
            addAll(index.byModelId[normalized].orEmpty())
            if ('/' in normalized) addAll(index.byModelId[normalized.substringAfter('/')].orEmpty())
        }.distinctBy { "${it.providerId.lowercase()}\u0000${it.capability.modelId.lowercase()}" }
        if (candidates.isEmpty()) return emptyList()

        val scored = candidates.map { entry ->
            entry to providerMatchScore(entry, providerHint, apiAddress, protocol)
        }
        val bestScore = scored.maxOf { it.second }
        val selected = if (bestScore > 0) {
            scored.filter { it.second == bestScore }.map { it.first }
        } else {
            candidates
        }
        return listOf(mergeModelsDevEntries(modelId, protocol, selected))
    }

    private suspend fun loadIndex(fetchRemote: suspend () -> String): ModelsDevIndex? {
        val now = nowEpochMillis()
        memoryEntry?.takeIf { it.loadedAtEpochMillis >= now - ttlMillis }?.let { return it.index }
        return mutex.withLock {
            val lockedNow = nowEpochMillis()
            memoryEntry?.takeIf { it.loadedAtEpochMillis >= lockedNow - ttlMillis }?.let {
                return@withLock it.index
            }
            val diskText = readDiskText()
            val diskTimestamp = cacheFile.lastModified().takeIf { it > 0L } ?: 0L
            if (diskText != null && diskTimestamp >= lockedNow - ttlMillis) {
                parseModelsDevCatalog(diskText, diskTimestamp)?.let { index ->
                    memoryEntry = MemoryEntry(index, diskTimestamp)
                    return@withLock index
                }
            }

            val remoteIndex = try {
                val body = fetchRemote()
                parseModelsDevCatalog(body, lockedNow)?.also {
                    writeDiskTextAtomically(body)
                    memoryEntry = MemoryEntry(it, lockedNow)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            remoteIndex ?: diskText?.let { stale ->
                parseModelsDevCatalog(stale, diskTimestamp).also { index ->
                    if (index != null) memoryEntry = MemoryEntry(index, lockedNow)
                }
            }
        }
    }

    private fun readDiskText(): String? = runCatching {
        cacheFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun writeDiskTextAtomically(body: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temporary.writeText(body, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }
}

internal fun parseModelsDevCatalog(
    responseBody: String,
    fetchedAtEpochMillis: Long,
): ModelsDevIndex? = runCatching {
    val root = modelsDevJson.parseToJsonElement(responseBody) as? JsonObject ?: return@runCatching null
    val entries = root.flatMap { (providerKey, providerElement) ->
        val provider = providerElement as? JsonObject ?: return@flatMap emptyList()
        val providerId = provider.string("id")?.ifBlank { providerKey } ?: providerKey
        val providerName = provider.string("name").orEmpty()
        val providerApiHost = provider.string("api")?.let(::hostOf)
        val models = provider["models"] as? JsonObject ?: return@flatMap emptyList()
        models.mapNotNull { (modelKey, modelElement) ->
            val model = modelElement as? JsonObject ?: return@mapNotNull null
            val id = model.string("id")?.ifBlank { modelKey } ?: modelKey
            val limit = model["limit"] as? JsonObject
            val modalities = model["modalities"] as? JsonObject
            val efforts = modelsDevReasoningEfforts(model)
            ModelsDevEntry(
                providerId = providerId,
                providerName = providerName,
                providerApiHost = providerApiHost,
                capability = ModelCapabilityCandidate(
                    modelId = id,
                    protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    providerId = providerId,
                    family = model.string("family"),
                    contextWindowTokens = limit?.positiveInt("context"),
                    maxInputTokens = limit?.positiveInt("input"),
                    maxOutputTokens = limit?.positiveInt("output"),
                    inputModalities = modalities?.stringSet("input").orEmpty(),
                    outputModalities = modalities?.stringSet("output").orEmpty(),
                    supportsReasoning = model.boolean("reasoning")
                        ?: true.takeIf { efforts.isNotEmpty() },
                    reasoningEfforts = efforts,
                    source = ModelCapabilitySource.COMMUNITY_CATALOG,
                    sourceUpdatedAt = fetchedAtEpochMillis,
                ),
            )
        }
    }
    ModelsDevIndex(entries.groupBy { normalizeCatalogModelId(it.capability.modelId) })
}.getOrNull()

private fun mergeModelsDevEntries(
    requestedModelId: String,
    protocol: ModelParameterProtocol,
    entries: List<ModelsDevEntry>,
): ModelCapabilityCandidate {
    val capabilities = entries.map(ModelsDevEntry::capability)
    return ModelCapabilityCandidate(
        modelId = requestedModelId.removePrefix("models/").trim(),
        protocol = protocol,
        providerId = entries.singleOrNull()?.providerId,
        family = capabilities.mapNotNull(ModelCapabilityCandidate::family).distinct().singleOrNull(),
        contextWindowTokens = capabilities.mapNotNull(ModelCapabilityCandidate::contextWindowTokens).minOrNull(),
        maxInputTokens = capabilities.mapNotNull(ModelCapabilityCandidate::maxInputTokens).minOrNull(),
        maxOutputTokens = capabilities.mapNotNull(ModelCapabilityCandidate::maxOutputTokens).minOrNull(),
        inputModalities = capabilities.map(ModelCapabilityCandidate::inputModalities).commonValues(),
        outputModalities = capabilities.map(ModelCapabilityCandidate::outputModalities).commonValues(),
        supportsReasoning = when {
            capabilities.any { it.supportsReasoning == false } -> false
            capabilities.all { it.supportsReasoning == true } -> true
            else -> null
        },
        reasoningEfforts = capabilities
            .map(ModelCapabilityCandidate::reasoningEfforts)
            .commonValues()
            .takeIf { capabilities.all { it.supportsReasoning == true } }
            .orEmpty(),
        source = ModelCapabilitySource.COMMUNITY_CATALOG,
        sourceUpdatedAt = capabilities.mapNotNull(ModelCapabilityCandidate::sourceUpdatedAt).maxOrNull(),
    )
}

private fun providerMatchScore(
    entry: ModelsDevEntry,
    providerHint: String,
    apiAddress: String,
    protocol: ModelParameterProtocol,
): Int {
    val hint = providerHint.trim().lowercase()
    val providerId = entry.providerId.lowercase()
    val providerName = entry.providerName.lowercase()
    val endpointHost = hostOf(apiAddress)
    var score = 0
    if (hint.isNotEmpty()) {
        if (hint == providerId) score += 120
        else if (
            hint.contains(providerId) ||
            (providerName.isNotEmpty() && (providerName.contains(hint) || hint.contains(providerName)))
        ) score += 80
    }
    if (!endpointHost.isNullOrBlank() && endpointHost == entry.providerApiHost) score += 100
    score += when (protocol) {
        ModelParameterProtocol.CODEX -> 40.takeIf { providerId == "openai" } ?: 0
        ModelParameterProtocol.ANTHROPIC -> 40.takeIf { providerId == "anthropic" } ?: 0
        ModelParameterProtocol.GEMINI -> 40.takeIf { providerId in setOf("google", "google-vertex") } ?: 0
        ModelParameterProtocol.OPENAI_COMPATIBLE -> 0
    }
    return score
}

private fun modelsDevReasoningEfforts(model: JsonObject): Set<String> = buildSet {
    (model["reasoning_options"] as? JsonArray).orEmpty().forEach { option ->
        val objectValue = option as? JsonObject ?: return@forEach
        addAll(objectValue.stringSet("values"))
    }
}.mapTo(linkedSetOf()) { it.trim().lowercase() }.filterTo(linkedSetOf(), String::isNotEmpty)

private fun List<Set<String>>.commonValues(): Set<String> {
    val nonEmpty = filter(Set<String>::isNotEmpty)
    if (nonEmpty.isEmpty()) return emptySet()
    return nonEmpty.drop(1).fold(nonEmpty.first()) { common, values -> common intersect values }
}

private fun normalizeCatalogModelId(modelId: String): String =
    modelId.removePrefix("models/").trim().lowercase()

private fun hostOf(rawUrl: String): String? {
    val normalized = rawUrl.trim().let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }
    return runCatching { URI(normalized).host?.lowercase() }.getOrNull()
}

private val modelsDevJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.positiveInt(key: String): Int? =
    (this[key] as? JsonPrimitive)
        ?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        ?.takeIf { it in 1..MAX_MODEL_TOKEN_LIMIT }

private fun JsonObject.stringSet(key: String): Set<String> =
    (this[key] as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        .toSet()
