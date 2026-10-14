package com.android.everytalk.ui.screens.appinfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.database.entities.MemoryEntity
import com.android.everytalk.data.memory.MemoryCategory
import com.android.everytalk.data.memory.MemoryOrigin
import com.android.everytalk.data.memory.MemoryPolicy
import com.android.everytalk.data.memory.MemoryRepository
import com.android.everytalk.data.memory.MemoryState
import com.android.everytalk.data.memory.memoryCharacters
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 复用应用信息页容器和 Material 3；数据库 Flow 是唯一事实来源。
 * 编辑失败保留输入与旧数据，清空必须确认，停用仍允许用户手动管理资料。
 */
@Composable
fun MemoryManagementScreen(onBack: () -> Unit, modifier: Modifier = Modifier, repository: MemoryRepository? = null) {
    val context = LocalContext.current
    val store = repository ?: remember(context) { MemoryRepository(context) }
    val scope = rememberCoroutineScope()
    var state by remember(store) { mutableStateOf<MemoryState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    var showClear by remember { mutableStateOf(false) }

    LaunchedEffect(store, retry) {
        try { store.observeState().collect { state = it; error = null } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = context.getString(R.string.memory_load_failed) }
    }
    fun mutate(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: context.getString(R.string.memory_operation_failed) }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    ImmersiveInfoPage(title = stringResource(R.string.memory_title), onBack = onBack, modifier = modifier) {
        item(key = "memory_privacy") { Text(stringResource(R.string.memory_privacy), style = MaterialTheme.typography.bodyMedium) }
        val current = state
        if (current == null) {
            item { if (error == null) CircularProgressIndicator() else TextButton(onClick = { retry++ }) { Text(stringResource(R.string.memory_retry)) } }
        } else {
            item(key = "memory_switches") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MemorySwitch(stringResource(R.string.memory_use), current.settings.enabled, !busy, "memory_enabled") { checked ->
                        mutate { store.setSettings(checked, current.settings.autoCaptureEnabled) }
                    }
                    MemorySwitch(stringResource(R.string.memory_auto), current.settings.autoCaptureEnabled && current.settings.enabled,
                        current.settings.enabled && !busy, "memory_auto") { checked ->
                        mutate { store.setSettings(current.settings.enabled, checked) }
                    }
                    Text(stringResource(R.string.memory_auto_description), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.memory_capacity, current.entries.size, MemoryPolicy.MAX_ENTRIES, current.characters, MemoryPolicy.MAX_TOTAL_CHARACTERS),
                        modifier = Modifier.testTag("memory_capacity"))
                }
            }
            item(key = "memory_actions") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(enabled = !busy, onClick = { editing = null; showEditor = true }) { Text(stringResource(R.string.memory_add)) }
                    TextButton(enabled = !busy && current.entries.isNotEmpty(), onClick = { showClear = true }) { Text(stringResource(R.string.memory_clear)) }
                }
            }
            item(key = "memory_search") {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.memory_search)) }, modifier = Modifier.fillMaxWidth().testTag("memory_search"))
            }
            val filtered = current.entries.filter { it.content.contains(query.trim(), ignoreCase = true) }
            if (filtered.isEmpty()) item { Text(stringResource(if (current.entries.isEmpty()) R.string.memory_empty else R.string.memory_no_results)) }
            items(filtered, key = { it.id }) { entry ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(categoryLabel(entry.category)), style = MaterialTheme.typography.labelLarge)
                        Text(entry.content, modifier = Modifier.testTag("memory_content_${entry.id}"))
                        MemorySource(entry, store)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updatedAt)), style = MaterialTheme.typography.bodySmall)
                        MemorySwitch(stringResource(R.string.memory_entry_use), entry.enabled, !busy, "memory_entry_${entry.id}") {
                            mutate { store.setEntryEnabled(entry.id, it) }
                        }
                        Row {
                            TextButton(enabled = !busy, onClick = { editing = entry; showEditor = true }) { Text(stringResource(R.string.memory_edit)) }
                            TextButton(enabled = !busy, onClick = { deleting = entry }) { Text(stringResource(R.string.memory_delete)) }
                        }
                    }
                }
            }
        }
        error?.let { message -> item(key = "memory_error") { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error")) } }
    }
    if (showEditor) {
        MemoryEditor(editing, busy, error, onDismiss = { if (!busy) { showEditor = false; error = null } }) { content, category ->
            mutate { store.save(content, category, editing?.id); showEditor = false }
        }
    }
    val pendingDelete = deleting
    if (pendingDelete != null || showClear) {
        AlertDialog(
            onDismissRequest = { if (!busy) { deleting = null; showClear = false } },
            title = { Text(stringResource(if (showClear) R.string.memory_clear else R.string.memory_delete)) },
            text = { Text(stringResource(if (showClear) R.string.memory_clear_confirmation else R.string.memory_delete_confirmation)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    mutate { if (showClear) store.clear() else pendingDelete?.let { store.delete(it.id) }; deleting = null; showClear = false }
                }, modifier = Modifier.testTag("memory_confirm_delete")) { Text(stringResource(R.string.memory_confirm)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null; showClear = false }) { Text(stringResource(R.string.memory_cancel)) } },
        )
    }
}

@Composable
private fun MemorySwitch(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
}

@Composable
private fun MemorySource(entry: MemoryEntity, repository: MemoryRepository) {
    var source by remember(entry.id, entry.updatedAt) { mutableStateOf<String?>(null) }
    var loaded by remember(entry.id, entry.updatedAt) { mutableStateOf(false) }
    LaunchedEffect(entry.id, entry.updatedAt) {
        try { source = entry.sourceConversationId?.let { repository.sourceTitle(it) }; loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loaded = false }
    }
    val origin = when (entry.origin) {
        MemoryOrigin.AUTO.name -> if (entry.enabled) R.string.memory_origin_auto else R.string.memory_origin_review
        MemoryOrigin.CHAT_EXPLICIT.name -> R.string.memory_origin_chat
        else -> R.string.memory_origin_manual
    }
    Text(stringResource(origin), style = MaterialTheme.typography.bodySmall)
    if (entry.sourceConversationId != null && loaded) {
        Text(source ?: stringResource(R.string.memory_source_deleted), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MemoryEditor(entry: MemoryEntity?, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String, MemoryCategory) -> Unit) {
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    var category by remember(entry?.id) { mutableStateOf(runCatching { MemoryCategory.valueOf(entry?.category.orEmpty()) }.getOrDefault(MemoryCategory.USER_PREFERENCE)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (entry == null) R.string.memory_add else R.string.memory_edit)) },
        text = {
            Column {
                OutlinedTextField(value = content, onValueChange = { content = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.memory_content)) }, minLines = 3, maxLines = 6,
                    supportingText = { Text("${content.memoryCharacters()} / ${MemoryPolicy.MAX_ENTRY_CHARACTERS}") },
                    modifier = Modifier.testTag("memory_editor"))
                MemoryCategory.entries.forEach { option ->
                    val label = stringResource(categoryLabel(option.name))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category == option, onClick = { category = option }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = label })
                        Text(label)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && content.isNotBlank(), onClick = { onSave(content, category) }, modifier = Modifier.testTag("memory_save")) { Text(stringResource(R.string.memory_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } },
    )
}

private fun categoryLabel(category: String): Int = when (category) {
    MemoryCategory.USER_PREFERENCE.name -> R.string.memory_category_preference
    MemoryCategory.PROJECT_FACT.name -> R.string.memory_category_project
    MemoryCategory.WORKFLOW.name -> R.string.memory_category_workflow
    else -> R.string.memory_category_other
}
