package com.android.everytalk.ui.screens.appinfo

import com.android.everytalk.ui.theme.AppInputFieldShape

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.android.everytalk.data.memory.MemoryRepository
import com.android.everytalk.data.memory.MemoryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 全会话共用一个记忆编辑区，复用信息页容器和 Material 3 控件。
 * 未编辑时实时跟随数据库；编辑中保留草稿，保存失败也不丢失用户输入。
 */
@Composable
fun MemoryManagementScreen(onBack: () -> Unit, modifier: Modifier = Modifier, repository: MemoryRepository? = null) {
    val context = LocalContext.current
    val store = repository ?: remember(context) { MemoryRepository(context) }
    val scope = rememberCoroutineScope()
    var state by remember(store) { mutableStateOf<MemoryState?>(null) }
    var baseline by remember(store) { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(store, retry) {
        try {
            store.observeState().collect { latest ->
                state = latest
                // 不用 AI 的新快照覆盖正在输入的草稿；保存事务会保留期间新增的条目。
                if (!dirty) {
                    draft = latest.document
                    baseline = latest.entries
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = context.getString(R.string.memory_load_failed) }
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
        val current = state
        if (current == null) {
            item {
                if (error == null) CircularProgressIndicator()
                else TextButton(onClick = { error = null; retry++ }) { Text(stringResource(R.string.memory_retry)) }
            }
        } else {
            item(key = "memory_switch") {
                val label = stringResource(R.string.memory_use)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, modifier = Modifier.weight(1f))
                    Switch(checked = current.settings.enabled, enabled = !busy,
                        onCheckedChange = { checked -> mutate { store.setSettings(checked) } },
                        modifier = Modifier.testTag("memory_enabled").semantics { contentDescription = label })
                }
            }
            item(key = "memory_description") {
                Text(stringResource(R.string.memory_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "memory_document") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; dirty = true },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.memory_content)) },
                        placeholder = { Text(stringResource(R.string.memory_empty)) },
                        minLines = 14, maxLines = 20,
                        shape = AppInputFieldShape,
                        modifier = Modifier.fillMaxWidth().testTag("memory_editor"),
                    )
                    error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("memory_error"))
                    }
                    Button(
                        enabled = !busy && dirty,
                        onClick = {
                            val content = draft
                            val previous = baseline
                            mutate {
                                val saved = store.saveDocument(content, previous)
                                draft = saved.document
                                baseline = saved.entries
                                dirty = false
                            }
                        },
                        modifier = Modifier.align(Alignment.End).testTag("memory_save"),
                    ) { Text(stringResource(R.string.memory_save)) }
                }
            }
        }
        if (current == null) error?.let { message ->
            item(key = "memory_error") {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error"))
            }
        }
    }
}
