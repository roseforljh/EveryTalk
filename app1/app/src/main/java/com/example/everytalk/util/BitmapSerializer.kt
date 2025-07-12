package com.example.everytalk.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.ByteArrayOutputStream

/**
 * A KSerializer for Android's Bitmap class.
 *
 * This serializer converts Bitmap objects to Base64 encoded strings for serialization,
 * and parses Base64 strings back to Bitmap objects during deserialization.
 */
object BitmapSerializer : KSerializer<Bitmap> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("android.graphics.Bitmap", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Bitmap) {
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            value.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            val base64String = Base64.encodeToString(byteArray, Base64.DEFAULT)
            encoder.encodeString(base64String)
        } catch (e: Exception) {
            throw SerializationException("Failed to serialize Bitmap", e)
        }
    }

    override fun deserialize(decoder: Decoder): Bitmap {
        try {
            val base64String = decoder.decodeString()
            val byteArray = Base64.decode(base64String, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                ?: throw SerializationException("Failed to deserialize Bitmap")
        } catch (e: Exception) {
            throw SerializationException("Failed to deserialize Bitmap", e)
        }
    }
}