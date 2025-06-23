package com.example.everytalk.util // 确保包名正确

import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A robust KSerializer for Android's Uri class.
 *
 * This serializer handles:
 * - Empty or blank strings, mapping them to `Uri.EMPTY`.
 * - Potential parsing errors by wrapping them in a `SerializationException`.
 */
object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("android.net.Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri) {
        // Encode the Uri object to its string representation.
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        val uriString = decoder.decodeString()

        if (uriString.isBlank()) {
            return Uri.EMPTY
        }

        return try {
            // Parse the string back to a Uri object.
            // If parsing results in null (for certain invalid inputs), default to Uri.EMPTY.
            Uri.parse(uriString) ?: Uri.EMPTY
        } catch (e: Exception) {
            // Wrap any parsing exceptions in a more informative SerializationException.
            throw SerializationException("Invalid URI format: '$uriString'", e)
        }
    }
}