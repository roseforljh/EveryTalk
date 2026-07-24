package com.android.everytalk.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw IllegalStateException("This serializer can be used only with Json")
        jsonEncoder.encodeJsonElement(serializeAny(value))
    }

    private fun serializeAny(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> {
                JsonObject(
                    value.mapKeys { (key, _) ->
                        key as? String ?: throw IllegalArgumentException("Unsupported map key type: ${key?.let { it::class }}")
                    }.mapValues { serializeAny(it.value) }
                )
            }
            is List<*> -> {
                JsonArray(value.map { serializeAny(it) })
            }
            else -> throw IllegalArgumentException("Unsupported type: ${value::class}")
        }
    }

    override fun deserialize(decoder: Decoder): Any {
        throw UnsupportedOperationException("Deserialization is not supported")
    }
}
