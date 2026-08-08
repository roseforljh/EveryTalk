package com.android.everytalk.util.image

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.android.everytalk.util.storage.ImagePersistenceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片选择阶段的快速校验，发送阶段仍会执行完整的原字节校验。 */
internal suspend fun validateUserImageForSelection(
    context: Context,
    uri: Uri,
    fileName: String? = null,
    onShowError: (String) -> Unit,
): Boolean {
    val result = ImagePersistenceService(context).checkUserImageSize(uri)
    if (result is ImageSizeCheckResult.Accepted) return true

    val failure = (result as ImageSizeCheckResult.Rejected).reason
    val resolvedName = fileName?.takeIf(String::isNotBlank) ?: resolveImageDisplayName(context, uri)
    withContext(Dispatchers.Main.immediate) {
        onShowError(failure.toUserImageMessage(resolvedName))
    }
    return false
}

private fun resolveImageDisplayName(context: Context, uri: Uri): String? {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && !cursor.isNull(index)) return cursor.getString(index)
            }
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment
}
