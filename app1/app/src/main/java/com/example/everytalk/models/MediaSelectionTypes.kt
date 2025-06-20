package com.example.everytalk.model
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.util.UUID

enum class ImageSourceOption(val label: String, val icon: ImageVector) {
    ALBUM("相册", Icons.Outlined.PhotoLibrary),
    CAMERA("相机", Icons.Outlined.PhotoCamera)
}

object DocumentMimeTypes {
    val TYPES = arrayOf(
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
        "application/epub+zip"
    )
}

enum class MoreOptionsType(val label: String, val icon: ImageVector, val mimeTypes: Array<String>) {
    FILE("文档", Icons.Outlined.AttachFile, DocumentMimeTypes.TYPES),
    VIDEO("视频", Icons.Outlined.Videocam, arrayOf("video/*")),
    AUDIO("音频", Icons.Outlined.Audiotrack, arrayOf("audio/*"))
}

@Serializable
sealed class SelectedMediaItem {
    abstract val id: String
 
    @Serializable
    data class ImageFromUri(
        @Serializable(with = com.example.everytalk.util.UriSerializer::class)
        val uri: Uri,
        val filePath: String? = null,
        override val id: String = UUID.randomUUID().toString()
    ) : SelectedMediaItem()
 
    @Serializable
    data class ImageFromBitmap(
        @Contextual val bitmap: Bitmap,
        override val id: String = "bitmap_${UUID.randomUUID()}"
    ) : SelectedMediaItem()
 
    @Serializable
    data class GenericFile(
        @Serializable(with = com.example.everytalk.util.UriSerializer::class)
        val uri: Uri,
        val displayName: String,
        val mimeType: String?,
        val filePath: String? = null,
        override val id: String = UUID.randomUUID().toString()
    ) : SelectedMediaItem()
}