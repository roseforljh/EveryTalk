package com.android.everytalk.data.account

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.android.everytalk.util.image.decodeOrientedBitmap
import java.io.ByteArrayOutputStream

private const val ACCOUNT_AVATAR_MAX_INPUT_BYTES = 8 * 1024 * 1024

/**
 * 将用户选中的图片转换成最长边不超过 512px 的 PNG。
 * 先限制输入大小并读取尺寸，再采样解码，避免大照片直接占满内存；不保存本地副本。
 */
internal fun readAccountAvatar(context: Context, uri: Uri): ByteArray {
    require(uri.scheme == "content") { "请选择相册中的图片" }
    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取头像图片" }
        .use { it.readBytesAtMost(ACCOUNT_AVATAR_MAX_INPUT_BYTES + 1) }
    return prepareAccountAvatar(bytes)
}

/** 独立校验与转换入口，允许用本地图片验证大小、解码和采样边界而不读取真实相册。 */
internal fun prepareAccountAvatar(bytes: ByteArray): ByteArray {
    require(bytes.size in 1..ACCOUNT_AVATAR_MAX_INPUT_BYTES) { "请选择小于 8 MB 的图片" }
    val bitmap = requireNotNull(decodeOrientedBitmap(bytes, 512)) { "图片格式或尺寸无效" }
    return try {
        ByteArrayOutputStream().use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法处理头像图片" }
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
}

/** 不依赖流提供者声明的文件长度，读取到上限即停止，避免错误元数据绕过限制。 */
private fun java.io.InputStream.readBytesAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count == -1) break
        if (count == 0) {
            val single = read()
            if (single == -1) break
            output.write(single)
            continue
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
