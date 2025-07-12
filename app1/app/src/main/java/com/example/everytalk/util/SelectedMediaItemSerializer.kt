package com.example.everytalk.util

import com.example.everytalk.models.SelectedMediaItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.serializer

/**
 * A custom serializer for SelectedMediaItem sealed class.
 * This handles polymorphic serialization for the different subtypes.
 */
object SelectedMediaItemSerializer : KSerializer<SelectedMediaItem> {
    private const val TYPE_IMAGE_FROM_URI = "ImageFromUri"
    private const val TYPE_IMAGE_FROM_BITMAP = "ImageFromBitmap"
    private const val TYPE_GENERIC_FILE = "GenericFile"
    private const val TYPE_AUDIO = "Audio"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SelectedMediaItem") {
        // We'll use a polymorphic approach with a type discriminator
    }

    override fun serialize(encoder: Encoder, value: SelectedMediaItem) {
        when (value) {
            is SelectedMediaItem.ImageFromUri -> {
                encoder.encodeStructure(buildClassSerialDescriptor(TYPE_IMAGE_FROM_URI)) {
                    encodeStringElement(buildClassSerialDescriptor("uri"), 0, value.uri.toString())
                    encodeStringElement(buildClassSerialDescriptor("id"), 1, value.id)
                    if (value.filePath != null) {
                        encodeStringElement(buildClassSerialDescriptor("filePath"), 3, value.filePath)
                    }
                }
            }
            is SelectedMediaItem.ImageFromBitmap -> {
                // Use the BitmapSerializer for the bitmap field
                val bitmapSerializer = BitmapSerializer
                encoder.encodeStructure(buildClassSerialDescriptor(TYPE_IMAGE_FROM_BITMAP)) {
                    encodeSerializableElement(buildClassSerialDescriptor("bitmap"), 0, bitmapSerializer, value.bitmap)
                    encodeStringElement(buildClassSerialDescriptor("id"), 1, value.id)
                }
            }
            is SelectedMediaItem.GenericFile -> {
                encoder.encodeStructure(buildClassSerialDescriptor(TYPE_GENERIC_FILE)) {
                    encodeStringElement(buildClassSerialDescriptor("uri"), 0, value.uri.toString())
                    encodeStringElement(buildClassSerialDescriptor("id"), 1, value.id)
                    encodeStringElement(buildClassSerialDescriptor("displayName"), 2, value.displayName)
                    if (value.mimeType != null) {
                        encodeStringElement(buildClassSerialDescriptor("mimeType"), 3, value.mimeType)
                    }
                    if (value.filePath != null) {
                        encodeStringElement(buildClassSerialDescriptor("filePath"), 4, value.filePath)
                    }
                }
            }
            is SelectedMediaItem.Audio -> {
                encoder.encodeStructure(buildClassSerialDescriptor(TYPE_AUDIO)) {
                    encodeStringElement(buildClassSerialDescriptor("id"), 0, value.id)
                    encodeStringElement(buildClassSerialDescriptor("mimeType"), 1, value.mimeType)
                    encodeStringElement(buildClassSerialDescriptor("data"), 2, value.data)
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): SelectedMediaItem {
        throw UnsupportedOperationException("Deserialization not needed for this use case")
    }
}