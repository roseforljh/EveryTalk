package com.android.everytalk.util.serialization

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
import com.android.everytalk.util.image.ImageHandlingLimits
import com.android.everytalk.util.image.decodedBase64SizeOrNull
import com.android.everytalk.util.storage.CappedByteArrayOutputStream

/**
 * A KSerializer for Android's Bitmap class.
 *
 * This serializer converts Bitmap objects to Base64 encoded strings for serialization,
 * and parses Base64 strings back to Bitmap objects during deserialization.
 */
object BitmapSerializer : KSerializer<Bitmap> {
    private const val MAX_BITMAP_BYTES = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("android.graphics.Bitmap", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Bitmap) {
        try {
            val byteArrayOutputStream = CappedByteArrayOutputStream(MAX_BITMAP_BYTES)
            if (!value.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)) {
                throw SerializationException("Bitmap 压缩失败")
            }
            val byteArray = byteArrayOutputStream.toByteArray()
            val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            encoder.encodeString(base64String)
        } catch (e: Exception) {
            throw SerializationException("Failed to serialize Bitmap", e)
        }
    }

    override fun deserialize(decoder: Decoder): Bitmap {
        try {
            val base64String = decoder.decodeString()
            val decodedSize = decodedBase64SizeOrNull(base64String)
                ?: throw SerializationException("Bitmap Base64 数据无效")
            if (decodedSize > MAX_BITMAP_BYTES) {
                throw SerializationException("Bitmap 数据超过 ${MAX_BITMAP_BYTES / (1024 * 1024)} MiB 上限")
            }
            val byteArray = Base64.decode(base64String, Base64.NO_WRAP)
            if (byteArray.size.toLong() > MAX_BITMAP_BYTES) {
                throw SerializationException("Bitmap 数据超过 ${MAX_BITMAP_BYTES / (1024 * 1024)} MiB 上限")
            }
            return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                ?: throw SerializationException("Failed to deserialize Bitmap")
        } catch (e: Exception) {
            throw SerializationException("Failed to deserialize Bitmap", e)
        }
    }

}
