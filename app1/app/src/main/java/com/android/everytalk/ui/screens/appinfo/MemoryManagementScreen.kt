package com.android.everytalk.ui.screens.appinfo

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.database.entities.MemoryEntity
import com.android.everytalk.data.memory.MemoryRepository
import com.android.everytalk.data.memory.MemoryState
import com.android.everytalk.data.memory.MemoryStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 记忆按“目录 + 文件”组织，和记忆系统本身一致：总览是目录，会话摘要和笔记是文件。 */
private sealed interface MemoryPage {
    data object Root : MemoryPage
    data object Overview : MemoryPage
    data object Conversations : MemoryPage
    data class Conversation(val id: String) : MemoryPage
    data object Notes : MemoryPage
    /** id 为空表示新建笔记。 */
    data class Note(val id: String?) : MemoryPage
}

@Composable
fun MemoryManagementScreen(onBack: () -> Unit, modifier: Modifier = Modifier, repository: MemoryRepository? = null) {
    val context = LocalContext.current
    val store = repository ?: remember(context) { MemoryRepository(context) }
    val scope = rememberCoroutineScope()
    var state by remember(store) { mutableStateOf<MemoryState?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val stack = remember { mutableStateListOf<MemoryPage>(MemoryPage.Root) }
    val overview by store.observeOverview().collectAsState(initial = null)
    val stats by store.observeStats().collectAsState(initial = MemoryStats())
    val summaries by store.observeConversationSummaries().collectAsState(initial = emptyList())

    LaunchedEffect(store, retry) {
        try { store.observeState().collect { state = it; loadError = false } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadError = true }
    }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    fun open(page: MemoryPage) { stack.add(page) }
    // 仅在深入子页面时拦截返回栈，根页面交给 NavHost 以展示预测式侧滑返回预览。
    BackHandler(enabled = stack.size > 1, onBack = back)

    val current = state
    if (current == null) {
        ImmersiveInfoPage(title = stringResource(R.string.memory_title), onBack = back, modifier = modifier) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!loadError) CircularProgressIndicator()
                    else TextButton(onClick = { retry++ }) { Text(stringResource(R.string.memory_retry)) }
                }
            }
        }
        return
    }
    when (val page = stack.last()) {
        MemoryPage.Root -> ImmersiveInfoPage(title = stringResource(R.string.memory_title), onBack = back, modifier = modifier) {
            item(key = "memory_switch") {
                SettingsGroupCard {
                    val label = stringResource(R.string.memory_use)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.memory_use_description), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = current.settings.enabled, enabled = !busy,
                            onCheckedChange = { checked ->
                                busy = true
                                scope.launch { try { store.setSettings(checked) } finally { busy = false } }
                            },
                            modifier = Modifier.testTag("memory_enabled").semantics { contentDescription = label })
                    }
                }
            }
            item(key = "memory_stats") {
                SettingsGroupCard {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        StatCell(stringResource(R.string.memory_stat_updated), formatMemoryTime(stats.lastUpdatedAt, context), Modifier.weight(1f))
                        StatCell(stringResource(R.string.memory_stat_conversations), stats.conversations.toString(), Modifier.weight(1f))
                    }
                    SettingsRowDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        StatCell(stringResource(R.string.memory_stat_calls), stats.modelCalls.toString(), Modifier.weight(1f))
                        StatCell(stringResource(R.string.memory_stat_tokens), formatTokenCount(stats.tokens), Modifier.weight(1f))
                    }
                }
            }
            item(key = "memory_files") {
                SettingsGroupCard {
                    FileRow(R.drawable.ic_gpt_file_document, stringResource(R.string.memory_overview_title),
                        overview?.takeIf { it.content.isNotBlank() }?.let { stringResource(R.string.memory_overview_subtitle, formatMemoryTime(it.generatedAt, context)) }
                            ?: stringResource(R.string.memory_overview_not_ready),
                        tag = "memory_file_overview") { open(MemoryPage.Overview) }
                    SettingsRowDivider()
                    FileRow(R.drawable.gpt_folder, stringResource(R.string.memory_folder_conversations),
                        stringResource(R.string.memory_folder_count, summaries.size), tag = "memory_folder_conversations") { open(MemoryPage.Conversations) }
                    SettingsRowDivider()
                    FileRow(R.drawable.gpt_folder, stringResource(R.string.memory_content),
                        stringResource(R.string.memory_folder_count, current.entries.size), tag = "memory_folder_notes") { open(MemoryPage.Notes) }
                }
            }
            item(key = "memory_description") { Footnote(stringResource(R.string.memory_privacy)) }
        }

        MemoryPage.Overview -> ImmersiveInfoPage(title = stringResource(R.string.memory_overview_title), onBack = back, modifier = modifier) {
            val sections = parseMemoryOverview(overview?.content.orEmpty())
            if (sections.isEmpty()) item { Footnote(stringResource(R.string.memory_overview_empty)) }
            sections.forEachIndexed { index, section ->
                item(key = "overview_$index") {
                    Section(overviewSectionTitle(section.title)) {
                        SettingsGroupCard {
                            section.items.forEach { entry ->
                                OverviewEntry(entry) { id -> open(MemoryPage.Conversation(id)) }
                            }
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                }
            }
            overview?.takeIf { it.content.isNotBlank() }?.let {
                item(key = "overview_footer") { Footnote(stringResource(R.string.memory_overview_footer)) }
            }
        }

        MemoryPage.Conversations -> ImmersiveInfoPage(title = stringResource(R.string.memory_folder_conversations), onBack = back, modifier = modifier) {
            if (summaries.isEmpty()) item { Footnote(stringResource(R.string.memory_overview_empty)) }
            else item(key = "conversation_files") {
                SettingsGroupCard {
                    summaries.forEachIndexed { index, summary ->
                        if (index > 0) SettingsRowDivider()
                        FileRow(R.drawable.ic_gpt_file_document, summary.title.ifBlank { stringResource(R.string.memory_untitled) },
                            formatMemoryDate(summary.sourceUpdatedAt), tag = "memory_conversation_${summary.id}") {
                            open(MemoryPage.Conversation(summary.id))
                        }
                    }
                }
            }
        }

        is MemoryPage.Conversation -> {
            val document by store.observeDocument(page.id).collectAsState(initial = null)
            var confirm by remember { mutableStateOf(false) }
            val summary = document?.takeIf { it.content.isNotBlank() }
            ImmersiveInfoPage(title = summary?.title?.ifBlank { null } ?: stringResource(R.string.memory_untitled), onBack = back, modifier = modifier) {
                if (summary == null) item { Footnote(stringResource(R.string.memory_file_missing)) }
                else {
                    item(key = "conversation_meta") { Footnote(stringResource(R.string.memory_conversation_meta, formatMemoryDate(summary.sourceUpdatedAt))) }
                    item(key = "conversation_body") {
                        SettingsGroupCard {
                            SelectionContainer {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    parseMemoryMarkdown(summary.content).forEach { MarkdownBlock(it) }
                                }
                            }
                        }
                    }
                    item(key = "conversation_forget") {
                        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().testTag("memory_conversation_forget"),
                            shape = RoundedCornerShape(20.dp)) {
                            Text(stringResource(R.string.memory_conversation_forget), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (confirm) ConfirmDialog(stringResource(R.string.memory_conversation_forget_confirm), onDismiss = { confirm = false }) {
                confirm = false
                scope.launch { store.forgetConversationSummary(page.id); back() }
            }
        }

        MemoryPage.Notes -> ImmersiveInfoPage(title = stringResource(R.string.memory_content), onBack = back, modifier = modifier) {
            item(key = "notes_files") {
                SettingsGroupCard {
                    FileRow(R.drawable.ic_plus, stringResource(R.string.memory_new_note), null, tag = "memory_new_note") { open(MemoryPage.Note(null)) }
                    current.entries.forEach { note ->
                        SettingsRowDivider()
                        FileRow(R.drawable.ic_gpt_file_document, note.content.lineSequence().first().take(60),
                            formatMemoryDate(note.updatedAt) + if (note.enabled) "" else " · " + stringResource(R.string.memory_note_disabled),
                            tag = "memory_note_${note.id}") { open(MemoryPage.Note(note.id)) }
                    }
                }
            }
            item(key = "notes_footer") { Footnote(stringResource(R.string.memory_empty)) }
        }

        is MemoryPage.Note -> NoteEditor(
            note = page.id?.let { id -> current.entries.find { it.id == id } },
            isNew = page.id == null,
            store = store, onBack = back, modifier = modifier,
        )
    }
}

/** 单条笔记：直接编辑和删除，保存失败时保留输入。 */
@Composable
private fun NoteEditor(note: MemoryEntity?, isNew: Boolean, store: MemoryRepository, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(note?.id) { mutableStateOf(note?.content.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun run(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { operation(); onBack() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: context.getString(R.string.memory_operation_failed) }
            finally { busy = false }
        }
    }
    ImmersiveInfoPage(title = stringResource(if (isNew) R.string.memory_new_note else R.string.memory_note_title), onBack = onBack, modifier = modifier) {
        if (!isNew && note == null) { item { Footnote(stringResource(R.string.memory_file_missing)) }; return@ImmersiveInfoPage }
        item(key = "note_editor") {
            SettingsGroupCard {
                TextField(
                    value = draft, onValueChange = { draft = it }, enabled = !busy,
                    placeholder = { Text(stringResource(R.string.memory_empty)) },
                    minLines = 8, maxLines = 24,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("memory_editor"),
                )
            }
        }
        error?.let { message -> item(key = "note_error") {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("memory_error"))
        } }
        item(key = "note_actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (note != null) OutlinedButton(onClick = { confirm = true }, enabled = !busy, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f).testTag("memory_note_delete")) {
                    Text(stringResource(R.string.memory_note_delete), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { val text = draft; run { store.save(text, note?.category?.let { runCatching { com.android.everytalk.data.memory.MemoryCategory.valueOf(it) }.getOrNull() } ?: com.android.everytalk.data.memory.MemoryCategory.OTHER, note?.id) } },
                    enabled = !busy && draft.isNotBlank() && draft != note?.content,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f).testTag("memory_save"),
                ) { Text(stringResource(R.string.memory_save)) }
            }
        }
    }
    if (confirm && note != null) ConfirmDialog(stringResource(R.string.memory_note_delete_confirm), onDismiss = { confirm = false }) {
        confirm = false
        run { store.delete(note.id) }
    }
}

@Composable
private fun FileRow(@DrawableRes icon: Int, title: String, subtitle: String?, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(painterResource(R.drawable.gpt_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun OverviewEntry(item: MemoryOverviewItem, onOpenConversation: (String) -> Unit) {
    when (item) {
        is MemoryOverviewItem.Heading -> Text(item.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        is MemoryOverviewItem.Bullet -> if (item.conversationId != null) {
            // 指向会话文件的条目可点开，对应 AI 按目录读取文件的方式。
            Row(Modifier.fillMaxWidth().clickable { onOpenConversation(item.conversationId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_gpt_file_document), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    item.date?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(item.text, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(painterResource(R.drawable.gpt_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        } else BulletText(item.text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

@Composable
private fun MarkdownBlock(block: MemoryBlock) {
    when (block) {
        is MemoryBlock.Heading -> Text(block.text, fontWeight = FontWeight.SemiBold,
            style = if (block.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp))
        is MemoryBlock.Bullet -> BulletText(block.text)
        is MemoryBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BulletText(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp))
        content()
    }
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ConfirmDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun overviewSectionTitle(title: String): String = when (title.lowercase()) {
    "user profile" -> stringResource(R.string.memory_section_profile)
    "user preferences" -> stringResource(R.string.memory_section_preferences)
    "general tips" -> stringResource(R.string.memory_section_tips)
    "what's in memory", "what’s in memory" -> stringResource(R.string.memory_section_contents)
    else -> title
}

internal sealed interface MemoryBlock {
    data class Heading(val text: String, val level: Int) : MemoryBlock
    data class Bullet(val text: String) : MemoryBlock
    data class Paragraph(val text: String) : MemoryBlock
}

internal data class MemoryOverviewSection(val title: String, val items: List<MemoryOverviewItem>)

internal sealed interface MemoryOverviewItem {
    data class Heading(val text: String) : MemoryOverviewItem
    data class Bullet(val text: String, val date: String? = null, val conversationId: String? = null) : MemoryOverviewItem
}

private val CONVERSATION_POINTER = Regex("""^\[conv:([^\]]+)]\s*(\d{4}-\d{2}-\d{2})?\s*[—–-]?\s*""")

private fun cleanInline(text: String) = text.replace("**", "").replace("`", "").trim()

/** 会话摘要等普通 Markdown：标题、列表项和段落，去掉行内标记。 */
internal fun parseMemoryMarkdown(markdown: String): List<MemoryBlock> =
    markdown.lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
        when {
            line.startsWith("#") -> MemoryBlock.Heading(cleanInline(line.trimStart('#')), line.takeWhile { it == '#' }.length)
            line.startsWith("- ") || line.startsWith("* ") -> MemoryBlock.Bullet(cleanInline(line.drop(2)))
            else -> MemoryBlock.Paragraph(cleanInline(line))
        }
    }.filter { block -> when (block) { is MemoryBlock.Heading -> block.text.isNotEmpty(); else -> true } }.toList()

/** 把总览拆成段落与条目：会话指针保留 ID 供点开，空段与 None 不显示。 */
internal fun parseMemoryOverview(markdown: String): List<MemoryOverviewSection> {
    val sections = mutableListOf<MemoryOverviewSection>()
    var title: String? = null
    var items = mutableListOf<MemoryOverviewItem>()
    fun flush() {
        title?.let { if (items.isNotEmpty()) sections += MemoryOverviewSection(it, items) }
        items = mutableListOf()
    }
    markdown.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
        when {
            line.startsWith("## ") -> { flush(); title = cleanInline(line.removePrefix("## ")) }
            line.startsWith("#") -> cleanInline(line.trimStart('#')).takeIf { it.isNotEmpty() }?.let { items += MemoryOverviewItem.Heading(it) }
            title == null -> Unit
            else -> {
                val body = cleanInline(line.removePrefix("- ").removePrefix("* "))
                if (body.equals("none", true) || body == "无") return@forEach
                val pointer = CONVERSATION_POINTER.find(body)
                items += if (pointer != null) MemoryOverviewItem.Bullet(body.substring(pointer.range.last + 1).trim(),
                    pointer.groupValues[2].ifEmpty { null }, pointer.groupValues[1])
                    else MemoryOverviewItem.Bullet(body)
            }
        }
    }
    flush()
    return sections
}

private fun formatMemoryTime(time: Long, context: android.content.Context): String =
    if (time <= 0) context.getString(R.string.memory_stat_never)
    else android.text.format.DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()

private fun formatMemoryDate(time: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(time))

/** 12,345 → 12.3K；1,234,567 → 1.2M，统计卡片不换行。 */
internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(java.util.Locale.ROOT, tokens / 1_000.0)
    else -> tokens.toString()
}
