package com.android.everytalk.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.AppToast
import com.android.everytalk.util.storage.FileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 所有附件入口复用同一规则；服务端只给 octet-stream 时按扩展名识别，不再退成回形针。 */
@DrawableRes
fun attachmentFileIcon(mimeType: String, name: String): Int {
    val mime = mimeType.substringBefore(';').lowercase()
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/pdf" || extension == "pdf" -> R.drawable.ic_gpt_file_pdf
        extension in setOf("xls", "xlsx", "csv", "tsv", "ods") || "spreadsheet" in mime || "excel" in mime -> R.drawable.ic_gpt_file_spreedsheet
        extension in setOf("ppt", "pptx", "odp") || "presentation" in mime || "powerpoint" in mime -> R.drawable.ic_gpt_file_powerpoint
        extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") || "zip" in mime || "compressed" in mime -> R.drawable.ic_gpt_file_zip
        mime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "heic") -> R.drawable.ic_gpt_file_image
        mime.startsWith("audio/") || extension in setOf("mp3", "wav", "ogg", "flac", "m4a") -> R.drawable.ic_gpt_file_audio
        mime.startsWith("video/") || extension in setOf("mp4", "mov", "mkv", "webm", "avi") -> R.drawable.ic_gpt_file_video
        extension in setOf("json", "xml", "html", "css", "js", "ts", "tsx", "jsx", "py", "kt", "java", "sh", "sql", "yaml", "yml", "rs", "go", "c", "cpp") -> R.drawable.ic_gpt_code
        else -> R.drawable.ic_gpt_file_document
    }
}

/** 优先使用附件已经持有的 MIME，仅在未知时补全，外部应用据此选择对应文件类型。 */
internal fun attachmentOpenMime(file: SelectedMediaItem.GenericFile): String =
    file.mimeType.takeUnless { it.isBlank() || it == "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.displayName.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

internal fun attachmentViewIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
    require(uri.scheme == "content") { "只允许通过内容提供器共享附件" }
    setDataAndType(uri, mime)
    clipData = ClipData.newRawUri("附件", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** 位置来自本机保存回执，不能把 AI 文本或任意 file:// 地址交给系统文件管理器。 */
internal fun attachmentFolderIntent(file: SelectedMediaItem.GenericFile): Intent? {
    val path = file.savedDownloadPath ?: return null
    val parts = path.split('/')
    if (!path.startsWith("Download/App/AppTalk/") || parts.any { it.isBlank() || it == "." || it == ".." || '\\' in it }) return null
    val folder = path.substringBeforeLast('/')
    return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        // 系统文件浏览器定位到实际目录；不请求整个目录的写入授权。
        putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$folder"))
    }
}

/** 只读取当前消息已持有的附件；私有路径失效时给出明确提示，不让 ActivityNotFound 导致闪退。 */
internal suspend fun openMessageAttachment(context: Context, file: SelectedMediaItem.GenericFile, chooseApp: Boolean = false) {
    try {
        val uri = withContext(Dispatchers.IO) {
            val local = file.filePath?.let(::File)?.takeIf(File::isFile)
            val candidate = if (local != null) FileManager(context).getFileProviderUri(local) else file.uri
            require(candidate.scheme == "content") { "附件文件已不可用，请重新下载" }
            context.contentResolver.openFileDescriptor(candidate, "r")?.use { } ?: error("附件文件已不可用，请重新下载")
            candidate
        }
        val intent = attachmentViewIntent(uri, attachmentOpenMime(file))
        context.startActivity(if (chooseApp) Intent.createChooser(intent, "选择应用打开 ${file.displayName}") else intent)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: ActivityNotFoundException) { AppToast.show(context, "没有能打开此类文件的应用，请安装对应应用后重试") }
    catch (error: Exception) { AppToast.show(context, error.message ?: "无法打开文件，请重新下载") }
}

/** 正文图标和兜底文件链接共用此菜单，操作始终绑定到被点击的附件。 */
@Composable
fun MessageFileActions(file: SelectedMediaItem.GenericFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AttachmentTypeIcon(file) },
        title = { Text(file.displayName) },
        text = {
            Column {
                Text(file.savedDownloadPath ?: "此文件保存在当前聊天附件中", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { openMessageAttachment(context, file) }; onDismiss() }) { Text("打开文件") }
                TextButton(onClick = { scope.launch { openMessageAttachment(context, file, true) }; onDismiss() }) { Text("用其他应用打开") }
                val folderIntent = attachmentFolderIntent(file)
                if (folderIntent != null) TextButton(onClick = {
                    try { context.startActivity(folderIntent) }
                    catch (_: ActivityNotFoundException) { AppToast.show(context, "系统未提供文件浏览器，文件位于 ${file.savedDownloadPath}") }
                    catch (_: SecurityException) { AppToast.show(context, "系统不允许定位此目录，文件位于 ${file.savedDownloadPath}") }
                    onDismiss()
                }) { Text("查看所在文件夹") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun AttachmentTypeIcon(file: SelectedMediaItem.GenericFile, modifier: Modifier = Modifier) {
    val icon = attachmentFileIcon(file.mimeType, file.displayName)
    Icon(painterResource(icon), contentDescription = null, modifier = modifier.size(22.dp),
        tint = if (icon == R.drawable.ic_gpt_file_powerpoint) Color.Unspecified else MaterialTheme.chatColors.loadingIndicator)
}

/** 仅供未在正文引用的附件和用户附件使用；避免旧会话、失败回复中的文件变得不可访问。 */
@Composable
fun MessageFileLink(file: SelectedMediaItem.GenericFile, maxWidth: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember(file.id) { mutableStateOf(false) }
    Row(Modifier.widthIn(max = maxWidth).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(attachmentFileIcon(file.mimeType, file.displayName)), "文件操作：${file.displayName}",
                Modifier.size(22.dp), tint = if (attachmentFileIcon(file.mimeType, file.displayName) == R.drawable.ic_gpt_file_powerpoint) Color.Unspecified else MaterialTheme.chatColors.loadingIndicator)
        }
        Text(file.displayName, modifier = Modifier.weight(1f, fill = false).clickable { scope.launch { openMessageAttachment(context, file) } }.padding(vertical = 12.dp),
            color = MaterialTheme.chatColors.loadingIndicator, textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (menu) MessageFileActions(file) { menu = false }
}
