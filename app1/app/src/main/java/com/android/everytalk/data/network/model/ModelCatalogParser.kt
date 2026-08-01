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
    val models = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["models"] ?: root["data"]) as? JsonArray
        else -> null
    } ?: return emptyList()
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
        val supportedParameters = model.stringSet("supported_parameters")
        ModelCapabilityCandidate(
            modelId = modelId,
            protocol = protocol,
            endpointIdentity = endpointIdentity,
            contextWindowTokens = model.intValue(
                "inputTokenLimit",
                "context_length",
                "context_window",
                "context_size",
                "max_context_length",
                "max_model_len",
                "max_input_tokens",
            ),
            maxOutputTokens = model.intValue(
                "outputTokenLimit",
                "max_completion_tokens",
                "max_output_tokens",
                "max_tokens",
            ),
            inputModalities = architecture?.stringSet("input_modalities")
                .orEmpty()
                .ifEmpty { model.stringSet("input_modalities") }
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
                .ifEmpty { setOf("text").takeIf { capabilities != null }.orEmpty() },
            supportsReasoning = model.booleanValue("supports_reasoning")
                ?: capabilities?.capabilitySupported("thinking")
                ?: supportedParameters.any { "reasoning" in it.lowercase() }.takeIf { supportedParameters.isNotEmpty() },
            source = ModelCapabilitySource.LIVE_ENDPOINT,
            sourceUpdatedAt = fetchedAtEpochMillis,
        )
    }.distinctBy { it.modelId.lowercase() }
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
    ((this[key] as? JsonObject)?.get("supported") as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringSet(key: String): Set<String> = ((this[key] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) })
    .orEmpty()
    .toSet()
