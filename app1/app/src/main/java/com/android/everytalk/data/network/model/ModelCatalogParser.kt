package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.normalizeModelEndpointIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val modelCatalogJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseModelCatalog(
    responseBody: String,
    protocol: ModelParameterProtocol,
    apiAddress: String,
    fetchedAtEpochMillis: Long = System.currentTimeMillis(),
): List<ModelCapabilityCandidate> {
    val root = modelCatalogJson.parseToJsonElement(responseBody)
    val models = modelElements(root)
    val endpointIdentity = normalizeModelEndpointIdentity(apiAddress)
    return models.mapNotNull { element ->
        if (element is JsonPrimitive) {
            val modelId = element.contentOrNull
                ?.removePrefix("models/")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            return@mapNotNull ModelCapabilityCandidate(
                modelId = modelId,
                protocol = protocol,
                endpointIdentity = endpointIdentity,
                source = ModelCapabilitySource.LIVE_ENDPOINT,
                sourceUpdatedAt = fetchedAtEpochMillis,
            )
        }
        val model = element as? JsonObject ?: return@mapNotNull null
        val modelId = model.stringValue("id", "model", "name", "identifier")
            ?.removePrefix("models/")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val architecture = model["architecture"] as? JsonObject
        val capabilities = model["capabilities"] as? JsonObject
        val modalities = model["modalities"] as? JsonObject
        val limit = model["limit"] as? JsonObject
        val topProvider = model["top_provider"] as? JsonObject
        val supportedParameters = model.stringSet("supported_parameters")
        val reasoningEfforts = model.reasoningEffortSet(capabilities)
        val maxInputTokens = model.intValue(
            "max_input_tokens",
            "maxInputTokens",
            "inputTokenLimit",
        )
        ModelCapabilityCandidate(
            modelId = modelId,
            protocol = protocol,
            family = model.stringValue("family", "model_family"),
            endpointIdentity = endpointIdentity,
            contextWindowTokens = model.intValue(
                "inputTokenLimit",
                "context_length",
                "context_window",
                "context_size",
                "max_context_length",
                "max_model_len",
                "max_input_tokens",
            ) ?: limit?.intValue("context") ?: topProvider?.intValue("context_length"),
            maxInputTokens = maxInputTokens,
            maxOutputTokens = model.intValue(
                "outputTokenLimit",
                "max_completion_tokens",
                "max_output_tokens",
                "max_tokens",
            ) ?: limit?.intValue("output") ?: topProvider?.intValue(
                "max_completion_tokens",
                "max_output_tokens",
            ),
            inputModalities = architecture?.stringSet("input_modalities")
                .orEmpty()
                .ifEmpty { model.stringSet("input_modalities") }
                .ifEmpty { modalities?.stringSet("input").orEmpty() }
                .ifEmpty {
                    capabilities?.let {
                        buildSet {
                            add("text")
                            if (it.capabilitySupported("image_input") == true) add("image")
                        }
                    }.orEmpty()
                },
            outputModalities = architecture?.stringSet("output_modalities")
                .orEmpty()
                .ifEmpty { model.stringSet("output_modalities") }
                .ifEmpty { modalities?.stringSet("output").orEmpty() }
                .ifEmpty { setOf("text").takeIf { capabilities != null }.orEmpty() },
            supportsReasoning = model.booleanValue("supports_reasoning")
                ?: model.booleanValue("reasoning")
                ?: capabilities?.capabilitySupported("thinking")
                ?: reasoningEfforts.isNotEmpty().takeIf { reasoningEfforts.isNotEmpty() }
                ?: supportedParameters.any {
                    "reasoning" in it.lowercase() || "thinking" in it.lowercase()
                }.takeIf { supportedParameters.isNotEmpty() },
            reasoningEfforts = reasoningEfforts,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
            sourceUpdatedAt = fetchedAtEpochMillis,
        )
    }.distinctBy { it.modelId.lowercase() }
}

private fun modelElements(root: kotlinx.serialization.json.JsonElement): List<kotlinx.serialization.json.JsonElement> =
    when (root) {
        is JsonArray -> root
        is JsonObject -> {
            val wrapped = root["models"] ?: root["data"]
            when (wrapped) {
                is JsonArray -> wrapped
                is JsonObject -> listOf(wrapped)
                else -> {
                    val singleModel = root["model"] as? JsonObject
                    when {
                        singleModel != null -> listOf(singleModel)
                        root.stringValue("id", "name", "identifier") != null -> listOf(root)
                        else -> emptyList()
                    }
                }
            }
        }
        else -> emptyList()
    }

private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.intValue(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)
        ?.let { primitive -> primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() }
        ?.takeIf { it > 0 }
}

private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.capabilitySupported(key: String): Boolean? =
    (this[key] as? JsonObject)?.let { capability ->
        (capability["supported"] as? JsonPrimitive)?.booleanOrNull
            ?: (capability["type"] as? JsonPrimitive)
                ?.contentOrNull
                ?.equals("supported", ignoreCase = true)
    }

private fun JsonObject.stringSet(key: String): Set<String> = ((this[key] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) })
    .orEmpty()
    .toSet()

private fun JsonObject.reasoningEffortSet(capabilities: JsonObject?): Set<String> = buildSet {
    val directKeys = listOf(
        "reasoning_efforts",
        "supported_reasoning_efforts",
        "reasoning_levels",
        "thinking_levels",
        "supportedThinkingLevels",
    )
    directKeys.forEach { key -> addAll(stringSet(key)) }
    listOf("reasoning", "thinking").forEach { objectKey ->
        val value = this@reasoningEffortSet[objectKey] as? JsonObject ?: return@forEach
        listOf("efforts", "levels", "supported_efforts", "supported_levels", "values").forEach { key ->
            addAll(value.stringSet(key))
        }
    }
    val thinkingCapability = capabilities?.get("thinking") as? JsonObject
    listOf("efforts", "levels", "supported_efforts", "supported_levels", "values").forEach { key ->
        addAll(thinkingCapability?.stringSet(key).orEmpty())
    }
    (this@reasoningEffortSet["reasoning_options"] as? JsonArray).orEmpty().forEach { option ->
        val optionObject = option as? JsonObject ?: return@forEach
        addAll(optionObject.stringSet("values"))
    }
}.mapTo(linkedSetOf()) { it.trim().lowercase() }.filterTo(linkedSetOf(), String::isNotEmpty)
