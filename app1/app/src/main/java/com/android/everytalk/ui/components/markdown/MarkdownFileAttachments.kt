package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.AttachmentTypeIcon
import com.android.everytalk.ui.components.MessageFileActions
import com.android.everytalk.ui.components.rememberMessageFileOpener
import com.android.everytalk.util.AppToast
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.service.SyncScheduler

internal const val MESSAGE_FILE_SCHEME = "everytalk-file:"
internal val LocalMessageFileAttachments = compositionLocalOf<List<SelectedMediaItem.GenericFile>> { emptyList() }

/** 以消息为边界解析附件 ID，不能通过伪造 Markdown 链接访问别的会话或任意手机路径。 */
internal fun resolveMessageFile(uri: String, files: List<SelectedMediaItem.GenericFile>): SelectedMediaItem.GenericFile? =
    uri.takeIf { it.startsWith(MESSAGE_FILE_SCHEME) }?.removePrefix(MESSAGE_FILE_SCHEME)?.let { id -> files.singleOrNull { it.id == id } }

@Composable
fun MessageFileAttachmentScope(
    attachments: List<SelectedMediaItem>,
    sessionId: String? = null,
    messageId: String? = null,
    content: @Composable () -> Unit,
) {
    val files = remember(attachments) { attachments.filterIsInstance<SelectedMediaItem.GenericFile>() }
    val fallback = LocalUriHandler.current
    val context = LocalContext.current
    val open = rememberMessageFileOpener()
    val scope = rememberCoroutineScope()
    val handler = remember(files, fallback, context, open, scope, sessionId, messageId) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (openCloudPreview(context, scope, uri)) return
                if (!uri.startsWith(MESSAGE_FILE_SCHEME)) { fallback.openUri(uri); return }
                val file = resolveMessageFile(uri, files)
                if (file != null) { open(file); return }
                scope.launch {
                    // 只查当前会话中的当前消息；不按链接 ID 查全库，更不从链接推导磁盘路径。
                    val latest = if (sessionId != null && messageId != null) withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(context).chatDao().getMessage(sessionId, messageId)?.attachments.orEmpty()
                    } else emptyList()
                    val restored = resolveMessageFile(uri, latest.filterIsInstance<SelectedMediaItem.GenericFile>())
                    if (restored != null) open(restored)
                    else {
                        SyncScheduler.requestNow(context)
                        AppToast.show(context, "附件尚未可用，请等同步完成后再试")
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalMessageFileAttachments provides files, LocalUriHandler provides handler, content = content)
}

/**
 * 云端任务生成的私有预览链接本身不是凭据：先用当前登录账号换一个一次性进入码，再交给浏览器打开。
 * 不是本同步服务器的预览链接返回 false，按普通链接处理。
 */
internal fun openCloudPreview(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, uri: String): Boolean {
    if (com.android.everytalk.data.agent.previewToken(com.android.everytalk.BuildConfig.EVERYTALK_SYNC_BASE_URL, uri) == null) return false
    val client = com.android.everytalk.data.agent.CloudAccountClient.current
    if (client == null) { AppToast.show(context, "私有预览需要登录生成它的账号后打开"); return true }
    scope.launch {
        val entry = try { client.previewEntry(uri) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (entry == null) AppToast.show(context, "预览已过期、已撤销，或不属于当前账号")
        else runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(entry))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppToast.show(context, "没有可以打开网页的应用") }
    }
    return true
}

internal fun messageFileInlineKey(file: SelectedMediaItem.GenericFile): String = "message-file-icon:${file.id}"

internal fun messageFileInlineContent(files: List<SelectedMediaItem.GenericFile>): Map<String, InlineTextContent> = files.associate { file ->
    messageFileInlineKey(file) to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
        var menu by remember(file.id) { mutableStateOf(false) }
        // semantics 块不是 @Composable，文案必须先在外面取好。
        val actionsLabel = stringResource(R.string.ui_file_actions_content_description, file.displayName)
        AttachmentTypeIcon(file, Modifier.fillMaxSize().semantics { contentDescription = actionsLabel }
            .clickable(role = Role.Button, onClickLabel = "选择文件操作") { menu = true })
        if (menu) MessageFileActions(file) { menu = false }
    }
}

internal data class MessageFileMention(val start: Int, val end: Int, val file: SelectedMediaItem.GenericFile, val label: String)

/**
 * 复用 Markdown AST：显式附件链接、正文文件名和独立反引号文件名均可点击。
 * 代码块、图片、外部网址中的同名文字不替换；重名附件必须使用工具返回的唯一 ID。
 */
internal fun messageFileMentions(content: String, node: ASTNode, files: List<SelectedMediaItem.GenericFile>, definitions: Map<String, String> = emptyMap()): Pair<String, List<MessageFileMention>>? {
    if (files.isEmpty()) return null
    var ancestor = node.parent
    while (ancestor != null) {
        if (ancestor.type in setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN,
                MarkdownElementTypes.IMAGE, MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK)) return null
        ancestor = ancestor.parent
    }
    val source = node.safeTextInNode(content) ?: return null
    val destination = node.markdownLinkLogoDestination(content, definitions)
    if (destination != null) {
        val byId = resolveMessageFile(destination, files)
        // 兼容模型已给出的服务器相对路径链接，但绝不将普通 https 链接劫持为附件。
        val localDestination = !destination.contains(":") || destination.startsWith("sandbox:/")
        val name = decodedDestinationName(destination)
        val file = byId ?: files.singleOrNull { localDestination && it.displayName.substringAfterLast('/') == name } ?: return null
        val label = source.substringAfter('[', "").substringBefore(']').takeIf { it.isNotBlank() } ?: file.displayName
        return source to listOf(MessageFileMention(0, source.length, file, label))
    }
    if (node.type != MarkdownTokenTypes.TEXT && node.type != MarkdownElementTypes.CODE_SPAN) return null
    val text = if (node.type == MarkdownElementTypes.CODE_SPAN) source.trim('`') else source
    val unique = files.groupBy { it.displayName.substringAfterLast('/') }.filter { it.key.isNotBlank() && it.value.size == 1 }
    val found = unique.flatMap { (name, matches) ->
        Regex(Regex.escape(name)).findAll(text).filter { match ->
            val next = text.getOrNull(match.range.last + 1)
            val previous = text.getOrNull(match.range.first - 1)
            (next == null || !(next.isLetterOrDigit() && next.code < 128 || next in "._-")) &&
                (previous == null || !(previous.isLetterOrDigit() && previous.code < 128 || previous in "_-"))
        }.map { MessageFileMention(it.range.first, it.range.last + 1, matches.single(), it.value) }.toList()
    }.sortedWith(compareBy<MessageFileMention> { it.start }.thenByDescending { it.end })
    var lastEnd = 0
    val mentions = found.filter { if (it.start < lastEnd) false else { lastEnd = it.end; true } }
    return if (mentions.isEmpty()) null else text to mentions
}

private fun decodedDestinationName(destination: String): String = android.net.Uri.decode(destination.substringAfterLast('/'))

/** 与正文使用同一识别规则，只隐藏确实已经生成可点击链接的末尾附件。 */
internal fun inlineMessageFileIds(content: String, files: List<SelectedMediaItem.GenericFile>): Set<String> {
    if (files.isEmpty()) return emptySet()
    val state = parseMarkdown(content, flavour = EveryTalkMarkdownFlavourDescriptor) as? State.Success ?: return emptySet()
    val definitions = markdownLinkLogoIndex(content, state).definitions
    return buildSet {
        fun visit(node: ASTNode) {
            val found = messageFileMentions(content, node, files, definitions)
            if (found != null) found.second.forEach { add(it.file.id) } else node.children.forEach(::visit)
        }
        visit(state.node)
    }
}
