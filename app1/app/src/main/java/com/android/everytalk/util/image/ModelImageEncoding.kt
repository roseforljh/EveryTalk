package com.android.everytalk.util.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.util.storage.readAtMost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

/** 主流模型都会把图片缩到 2048px 以内再识别，超出部分只是白白占内存和流量。 */
private const val MODEL_IMAGE_MAX_SIDE = 2048
private const val MODEL_IMAGE_RAW_MAX_BYTES = 1024 * 1024

/**
 * 按 2 的幂采样解码到最长边不超过 [maxSide]，并按 EXIF 校正旋转和镜像。
 * 尺寸无效或超过像素上限时返回 null。
 */
internal fun decodeOrientedBitmap(bytes: ByteArray, maxSide: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0 ||
        options.outWidth.toLong() * options.outHeight > 80_000_000) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSide) options.inSampleSize *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // 手机照片常把方向保存在 EXIF 中；使用平台支持校正旋转和镜像，兼容最低 Android 8.1。
    val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
        }
    }
    val bitmap = try { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) }
        catch (error: Exception) { decoded.recycle(); throw error }
    if (bitmap !== decoded) decoded.recycle()
    return bitmap
}

/**
 * 发给模型的图片唯一编码入口。发送管线和手动压缩必须共用，否则同一图片字节不同，检查点指纹对不上。
 * 小图原样发送；长边超过 2048px 或体积超过 1MB 时缩小，不透明图转 JPEG，透明图保留 PNG。
 * 历史图片每轮都会重发，这一步决定整个请求体的大小。
 */
internal fun encodeImageForModel(context: Context, uri: Uri): ApiContentPart.InlineData? = try {
    val bytes = context.contentResolver.openInputStream(uri)
        ?.use { readAtMost(it, ImageHandlingLimits.USER_UPLOAD_MAX_BYTES) }
    bytes?.let { encodeImageBytesForModel(it, context.contentResolver.getType(uri)) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    null
}

internal fun encodeImageBytesForModel(bytes: ByteArray, declaredMimeType: String?): ApiContentPart.InlineData {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val mimeType = bounds.outMimeType ?: declaredMimeType ?: "application/octet-stream"
    fun raw() = ApiContentPart.InlineData(Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType)
    if (bytes.size <= MODEL_IMAGE_RAW_MAX_BYTES && maxOf(bounds.outWidth, bounds.outHeight) <= MODEL_IMAGE_MAX_SIDE) {
        return raw()
    }
    val bitmap = decodeOrientedBitmap(bytes, MODEL_IMAGE_MAX_SIDE) ?: return raw()
    return try {
        val png = bitmap.hasAlpha()
        val output = ByteArrayOutputStream()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, output)
        ApiContentPart.InlineData(
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            if (png) "image/png" else "image/jpeg",
        )
    } finally {
        bitmap.recycle()
    }
}
