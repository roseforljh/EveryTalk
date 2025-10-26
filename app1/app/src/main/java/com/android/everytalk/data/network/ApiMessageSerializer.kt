package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

object ApiMessageSerializer : JsonContentPolymorphicSerializer<AbstractApiMessage>(AbstractApiMessage::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "parts" in element.jsonObject -> PartsApiMessage.serializer()
        else -> SimpleTextApiMessage.serializer()
    }
}