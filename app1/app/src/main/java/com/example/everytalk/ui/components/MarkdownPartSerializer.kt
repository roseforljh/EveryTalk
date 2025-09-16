package com.example.everytalk.ui.components

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object MarkdownPartSerializer : KSerializer<List<MarkdownPart>> {
    private val delegateSerializer = ListSerializer(MarkdownPart.serializer())
    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<MarkdownPart>) {
        delegateSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<MarkdownPart> {
        return delegateSerializer.deserialize(decoder)
    }
}