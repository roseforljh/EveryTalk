// MediaSelectionTypes.kt
package com.example.everytalk.model // <<< 确保包名正确

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable // <--- 添加导入
import kotlinx.serialization.Contextual // <--- 添加导入
import java.util.UUID

// 我们将把 UriSerializer 放在 com.example.everytalk.util 包下
// import com.example.everytalk.util.UriSerializer // <--- 添加导入 (稍后创建此文件)

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

// --- SelectedMediaItem 修改开始 ---
@Serializable // <--- 使 sealed class 可序列化
sealed class SelectedMediaItem { // <--- 移除了构造函数中的 id，让子类实现
    abstract val id: String // <--- 将 id 声明为抽象属性

    @Serializable // <--- 使 data class 可序列化
    data class ImageFromUri(
        @Serializable(with = com.example.everytalk.util.UriSerializer::class) // <--- 指定 Uri 序列化器
        val uri: Uri,
        override val id: String = uri.toString() // id 可以基于 uri 或 UUID
    ) : SelectedMediaItem()

    @Serializable // <--- 使 data class 可序列化
    data class ImageFromBitmap(
        // Bitmap 不应该被直接序列化。
        // MessageSender 会将其保存为文件，并用 ImageFromUri(fileUri) 替换。
        // 如果你确信这个类实例永远不会进入需要序列化的 Message.attachments，
        // 那么这里的 @Contextual 可能不是严格必需的，但为了类型系统的一致性可以保留。
        // 或者，如果它确实只是临时的，可以给 bitmap 字段加上 @kotlinx.serialization.Transient。
        // 最安全的做法是确保 Message.attachments 中不存储 ImageFromBitmap。
        @Contextual val bitmap: Bitmap, // <--- Bitmap 是特殊情况
        override val id: String = "bitmap_${UUID.randomUUID()}"
    ) : SelectedMediaItem()

    @Serializable // <--- 使 data class 可序列化
    data class GenericFile(
        @Serializable(with = com.example.everytalk.util.UriSerializer::class) // <--- 指定 Uri 序列化器
        val uri: Uri,
        val displayName: String,
        val mimeType: String?,
        override val id: String = uri.toString() // id 可以基于 uri 或 UUID
    ) : SelectedMediaItem()
}
// --- SelectedMediaItem 修改结束 ---