package com.android.everytalk.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AttachmentPreviewKind { TEXT, IMAGE, PDF, AUDIO, VIDEO }

/** 只对已知本机格式启用预览。Office/压缩包仍由系统应用打开，HTML/SVG 不执行脚本。 */
internal fun attachmentPreviewKind(file: SelectedMediaItem.GenericFile): AttachmentPreviewKind? {
    val extension = file.displayName.substringAfterLast('.', "").lowercase()
    val mime = file.mimeType.substringBefore(';').lowercase()
    return when {
        mime in setOf("image/svg+xml", "application/xhtml+xml", "application/xml", "application/json") -> AttachmentPreviewKind.TEXT
        extension in setOf("html", "htm", "svg", "xml", "json", "md", "txt", "log", "csv", "tsv", "yaml", "yml",
            "js", "ts", "tsx", "jsx", "css", "py", "kt", "java", "sh", "sql", "rs", "go", "c", "cpp") -> AttachmentPreviewKind.TEXT
        extension == "pdf" || mime == "application/pdf" -> AttachmentPreviewKind.PDF
        mime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "bmp") -> AttachmentPreviewKind.IMAGE
        mime.startsWith("audio/") || extension in setOf("mp3", "wav", "ogg", "flac", "m4a") -> AttachmentPreviewKind.AUDIO
        mime.startsWith("video/") || extension in setOf("mp4", "webm", "mov", "mkv") -> AttachmentPreviewKind.VIDEO
        mime.startsWith("text/") || mime in setOf("application/json", "application/xml") -> AttachmentPreviewKind.TEXT
        else -> null
    }
}

/** 在所属消息组件内持有预览，不生成下载任务；不支持的格式直接使用已有系统打开入口。 */
@Composable
internal fun rememberMessageFileOpener(): (SelectedMediaItem.GenericFile) -> Unit {
    var preview by remember { mutableStateOf<SelectedMediaItem.GenericFile?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    preview?.let { MessageFilePreview(it) { preview = null } }
    return remember(context, scope) { { file ->
        if (attachmentPreviewKind(file) != null) preview = file
        else scope.launch { openMessageAttachment(context, file) }
    } }
}

internal data class AttachmentTextPreview(val text: String, val truncated: Boolean)

/** 只读取已有文件前 512 KiB，超大文件显示截断提示，不复制整文件，也不回源下载。 */
internal fun readAttachmentText(context: Context, uri: Uri): AttachmentTextPreview {
    val limit = 512 * 1024
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(limit + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        buffer.copyOf(count)
    }
        ?: error("无法读取本地文件")
    val charset = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    return AttachmentTextPreview(String(bytes, 0, minOf(bytes.size, limit), charset).removePrefix("\uFEFF"), bytes.size > limit)
}

/** 逐页打开/关闭 PDF 描述符与渲染器，最多渲染约 2M 像素，关闭预览时没有持久文件或后台下载。 */
internal fun renderAttachmentPdfPage(context: Context, uri: Uri, index: Int): Pair<Bitmap, Int> {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("无法读取 PDF")
    descriptor.use { PdfRenderer(it).use { renderer ->
        require(renderer.pageCount > 0) { "PDF 没有可显示的页面" }
        renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
            val scale = minOf(1400f / maxOf(page.width, page.height), 2f)
            val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap to renderer.pageCount
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
    } }
}

@Composable
internal fun MessageFilePreview(file: SelectedMediaItem.GenericFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = attachmentPreviewKind(file)
    var uri by remember(file) { mutableStateOf<Uri?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var text by remember(file) { mutableStateOf<AttachmentTextPreview?>(null) }
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var pdfPage by remember(file) { mutableStateOf<Pair<Bitmap, Int>?>(null) }
    LaunchedEffect(file, pageIndex) {
        error = null
        pdfPage = null
        try {
            val localUri = withContext(Dispatchers.IO) { localAttachmentUri(context, file).also { candidate ->
                context.contentResolver.openFileDescriptor(candidate, "r")?.use { } ?: error("文件不可用")
            } }
            uri = localUri
            if (kind == AttachmentPreviewKind.TEXT) text = withContext(Dispatchers.IO) { readAttachmentText(context, localUri) }
            if (kind == AttachmentPreviewKind.PDF) pdfPage = withContext(Dispatchers.IO) { renderAttachmentPdfPage(context, localUri, pageIndex) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法预览此本地文件，可尝试用其他应用打开。" }
    }
    if (kind == AttachmentPreviewKind.IMAGE && uri != null && error == null) {
        ImagePreviewDialog(url = uri.toString(), onDismiss = onDismiss)
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AttachmentTypeIcon(file)
                    Text(file.displayName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2)
                    TextButton(onClick = onDismiss) { Text("关闭预览") }
                }
                Text("本地预览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        error != null -> Text(error!!, Modifier.align(Alignment.Center))
                        kind == AttachmentPreviewKind.TEXT && text != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (text!!.truncated) Text("文件较大，仅预览前 512 KiB；完整内容请用其他应用打开。")
                            SelectionContainer { Text(text!!.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                        kind == AttachmentPreviewKind.PDF && pdfPage != null -> Column {
                            Image(pdfPage!!.first.asImageBitmap(), "PDF 第 ${pageIndex + 1} 页", Modifier.weight(1f).fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("上一页") }
                                Text("${pageIndex + 1} / ${pdfPage!!.second}")
                                TextButton(onClick = { pageIndex++ }, enabled = pageIndex + 1 < pdfPage!!.second) { Text("下一页") }
                            }
                        }
                        uri != null && kind in setOf(AttachmentPreviewKind.AUDIO, AttachmentPreviewKind.VIDEO) -> {
                            // 原生播放器只接收 content URI；组件离开时释放解码器，不自动播放。
                            AndroidView(factory = { ctx -> VideoView(ctx).apply {
                                val controls = MediaController(ctx)
                                controls.setAnchorView(this)
                                setMediaController(controls)
                                setVideoURI(uri)
                                setOnPreparedListener { controls.show(0) }
                                setOnErrorListener { _, _, _ -> error = "此格式无法预览，请用其他应用打开。"; true }
                            } }, modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
                        }
                        else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
                TextButton(onClick = { scope.launch { openMessageAttachment(context, file, true) } }) { Text("用其他应用打开") }
            }
        }
    }
}
