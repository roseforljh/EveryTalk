package com.android.everytalk.models

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import com.android.everytalk.util.serialization.UriSerializer
import com.android.everytalk.util.storage.CappedByteArrayOutputStream

interface IMediaItem {
    val id: String
    val mimeType: String
}

enum class ImageSourceOption(val label: String, val icon: ImageVector) {
    ALBUM("相册", Icons.Outlined.PhotoLibrary),
    CAMERA("相机", Icons.Outlined.PhotoCamera)
}

object AttachmentMimeTypes {
    val TYPES = arrayOf(
        // 文档类型
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/csv",
        "text/html",
        "application/rtf",
        "application/epub+zip",
        // 视频类型
        "video/*",
        // 音频类型
        "audio/*"
    )
}

enum class MoreOptionsType(val label: String, val icon: ImageVector, val mimeTypes: Array<String>) {
    ATTACHMENT("附件", Icons.Outlined.AttachFile, AttachmentMimeTypes.TYPES),
    MCP("MCP", Icons.Outlined.Extension, arrayOf()),
    CONVERSATION_PARAMS("会话参数", Icons.Outlined.Settings, arrayOf())
}

@Serializable
sealed class SelectedMediaItem : IMediaItem {
    @Serializable
    data class ImageFromUri(
        @Serializable(with = UriSerializer::class)
        val uri: Uri,
        override val id: String,
        override val mimeType: String = "image/jpeg",
        val filePath: String? = null
    ) : SelectedMediaItem()

    @Serializable
    data class ImageFromBitmap(
        // 使用 Base64 字符串保存 Bitmap 数据，确保可序列化
        val bitmapData: String, // Base64 编码的图片数据
        override val id: String,
        override val mimeType: String = "image/png",
        val filePath: String? = null
    ) : SelectedMediaItem() {
        // 提供便捷方法来处理 Bitmap 和 Base64 的转换
        companion object {
            private const val MAX_BITMAP_BYTES = 16L * 1024L * 1024L

            fun fromBitmap(bitmap: Bitmap, id: String, mimeType: String = "image/png", filePath: String? = null): ImageFromBitmap {
                val baos = CappedByteArrayOutputStream(MAX_BITMAP_BYTES)
                val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, 100, baos)) { "Bitmap 压缩失败" }
                val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                return ImageFromBitmap(base64, id, mimeType, filePath)
            }
        }

        /**
         * 图片展示源优先使用持久化文件，避免 Compose 重组时反复解码 Base64。
         */
        val model: String
            get() = filePath?.takeIf { it.isNotBlank() } ?: "data:$mimeType;base64,$bitmapData"

        // 获取 Bitmap 对象（从 Base64 解码）
        // 仅保留给确实需要像素处理的调用方，普通展示和发送应使用 model/bitmapData。
        val bitmap: Bitmap?
            get() {
                return try {
                val encodedLength = bitmapData.count { !it.isWhitespace() }.toLong()
                val estimatedBytes = ((encodedLength + 3L) / 4L) * 3L
                if (estimatedBytes > 16L * 1024L * 1024L) return null
                val bytes = android.util.Base64.decode(bitmapData, android.util.Base64.NO_WRAP)
                if (bytes.size > 16 * 1024 * 1024) return null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
            }
    }

    @Serializable
    data class GenericFile(
        @Serializable(with = UriSerializer::class)
        val uri: Uri,
        override val id: String,
        val displayName: String,
        override val mimeType: String,
        val filePath: String? = null
    ) : SelectedMediaItem()

    @Serializable
    data class Audio(
        override val id: String,
        override val mimeType: String,
        val data: String
    ) : SelectedMediaItem()
}
