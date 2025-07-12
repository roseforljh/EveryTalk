package com.example.everytalk.data.network

import com.example.everytalk.data.DataClass.AbstractApiMessage
import com.example.everytalk.data.DataClass.SimpleTextApiMessage
import com.example.everytalk.data.DataClass.PartsApiMessage
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ApiMessageSerializer : JsonContentPolymorphicSerializer<AbstractApiMessage>(AbstractApiMessage::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "parts" in element.jsonObject -> PartsApiMessage.serializer()
        else -> SimpleTextApiMessage.serializer()
    }
}