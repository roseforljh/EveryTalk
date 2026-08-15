package com.android.everytalk.ui.screens.skill

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.skill.SkillFileManifestEntry
import com.android.everytalk.data.skill.RemoteSkillCatalogItem
import com.android.everytalk.data.skill.RemoteSkillDetail
import com.android.everytalk.data.skill.SkillAuditStatus
import com.android.everytalk.data.skill.SkillCatalogClient
import com.android.everytalk.data.skill.SkillFileDiff
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.data.skill.SkillSecretMetadata
import com.android.everytalk.data.skill.SkillSecretStore
import com.android.everytalk.navigation.Screen
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.computer.TopCircleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Skill 详情只修改本地副本。每次保存都会生成新哈希版本，运行中的旧快照不受影响。 */
@Composable
fun SkillDetailScreen(navController: NavController, skillId: String) {
    val context = LocalContext.current
    val repository = remember(context) { SkillRepository(context) }
    val catalog = remember(context) { SkillCatalogClient(context) }
    val secretStore = remember(context) { SkillSecretStore(context) }
    val installations by repository.observeAll().collectAsState(initial = emptyList())
    val installation = installations.firstOrNull { it.skillId == skillId }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<SkillFileManifestEntry>>(emptyList()) }
    var markdown by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var remoteDetail by remember { mutableStateOf<RemoteSkillDetail?>(null) }
    var updateDiff by remember { mutableStateOf<SkillFileDiff?>(null) }
    var updating by remember { mutableStateOf(false) }
    var savedSecrets by remember { mutableStateOf<List<SkillSecretMetadata>>(emptyList()) }
    var pendingSecretDelete by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(installation?.currentHash) {
        val current = installation ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            files = repository.manifest(current.skillId, current.currentHash)
            markdown = repository.readSkillMarkdown(current.skillId, current.currentHash)
            savedSecrets = secretStore.list(current.skillId)
            if (current.sourceType == SkillSourceType.REMOTE.name) {
                runCatching {
                    val item = current.toRemoteItem()
                    val detail = catalog.detail(item)
                    remoteDetail = detail
                    val installedRemoteHash = repository.versionLabel(current.skillId, current.currentHash) ?: current.currentHash
                    val available = detail.contentHash.takeIf { it != installedRemoteHash }
                    repository.markAvailableUpdate(current.skillId, available)
                    updateDiff = available?.let { repository.diff(current.skillId, detail.files) }
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val category = pendingCategory
        pendingCategory = null
        if (uri == null || category == null || installation == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.filter { it != '/' && it != '\\' && it != '\u0000' }
                    ?.takeIf(String::isNotBlank)
                    ?: "file"
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        repository.addOrReplaceFile(installation.skillId, "$category/$displayName", input)
                    } ?: error("无法读取文件")
                }
                "文件已保存"
            }.getOrElse { it.message ?: "文件保存失败" }
        }
    }

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 62.dp
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0.dp)) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (installation == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Skill 已删除") }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topPadding,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(installation.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(installation.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        DetailCard("来源", installation.sourceRepository ?: installation.sourceType)
                        DetailCard("当前版本", installation.currentHash)
                        DetailCard("安全审计", installation.auditStatus)
                        installation.updateHash?.let { DetailCard("可更新版本", it) }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (installation.sourceType == SkillSourceType.REMOTE.name) {
                                Button(onClick = {
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { repository.copyAsUserSkill(skillId) } }
                                            .onSuccess { copy -> navController.navigate(Screen.skillDetail(copy.skillId)) }
                                            .onFailure { message = it.message ?: "复制失败" }
                                    }
                                }) { Icon(Icons.Default.Edit, null); Text("复制并编辑") }
                                if (installation.updateHash != null && remoteDetail != null) {
                                    Button(onClick = { showUpdateDialog = true }) {
                                        Text("查看更新")
                                    }
                                }
                            } else {
                                Button(onClick = { showEditor = true }) { Icon(Icons.Default.Edit, null); Text("编辑规则") }
                            }
                        }
                    }
                    if (installation.sourceType != SkillSourceType.REMOTE.name) {
                        item {
                            Text("附带文件", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("scripts" to "脚本", "references" to "参考", "templates" to "模板", "assets" to "图片").forEach { (path, label) ->
                                    TextButton(
                                        onClick = { pendingCategory = path; filePicker.launch(arrayOf("*/*")) },
                                        modifier = Modifier.weight(1f),
                                    ) { Icon(Icons.Default.Add, null); Text(label) }
                                }
                            }
                        }
                    }
                    items(files, key = SkillFileManifestEntry::path) { file ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${file.size} B · ${file.sha256.take(10)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (installation.sourceType != SkillSourceType.REMOTE.name && file.path != "SKILL.md") {
                                    IconButton(onClick = { pendingDelete = file.path }) { Icon(Icons.Default.Delete, "删除文件") }
                                }
                            }
                        }
                    }
                    if (savedSecrets.isNotEmpty()) {
                        item { Text("已保存的密钥名", style = MaterialTheme.typography.titleMedium) }
                        items(savedSecrets, key = SkillSecretMetadata::name) { secret ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(secret.name, Modifier.weight(1f))
                                IconButton(onClick = { pendingSecretDelete = secret.name }) { Icon(Icons.Default.Delete, "删除密钥") }
                            }
                        }
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)
                    .windowInsetsPadding(WindowInsets.statusBars).padding(12.dp),
            ) {
                TopCircleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = "返回",
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { navController.popBackStack() },
                )
                Text("Skill 详情", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showEditor && installation != null) {
        var edited by remember(markdown) { mutableStateOf(markdown) }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("编辑 SKILL.md") },
            text = { OutlinedTextField(edited, { edited = it }, minLines = 12, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        message = runCatching { withContext(Dispatchers.IO) { repository.updateSkillMarkdown(skillId, edited) }; showEditor = false; "规则已保存" }
                            .getOrElse { it.message ?: "保存失败" }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } },
        )
    }
    pendingDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件？") },
            text = { Text(path) },
            confirmButton = { TextButton(onClick = {
                pendingDelete = null
                scope.launch { message = runCatching { withContext(Dispatchers.IO) { repository.deleteFile(skillId, path) }; "文件已删除" }.getOrElse { it.message ?: "删除失败" } }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    pendingSecretDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingSecretDelete = null },
            title = { Text("删除已保存的密钥？") },
            text = { Text(name) },
            confirmButton = { TextButton(onClick = {
                pendingSecretDelete = null
                scope.launch {
                    withContext(Dispatchers.IO) { secretStore.delete(skillId, name); savedSecrets = secretStore.list(skillId) }
                    message = "密钥已删除"
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingSecretDelete = null }) { Text("取消") } },
        )
    }
    val pendingUpdate = updateDiff?.takeIf { showUpdateDialog && installation?.updateHash != null }
    if (pendingUpdate != null && installation != null && remoteDetail != null) {
        val detail = remoteDetail ?: return
        AlertDialog(
            onDismissRequest = { if (!updating) showUpdateDialog = false },
            title = { Text("更新 Skill？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${installation.currentHash.take(12)} → ${detail.contentHash.take(12)}")
                    Text("审计：${installation.auditStatus} → ${detail.auditStatus}")
                    Text("新增 ${pendingUpdate.added.size}，修改 ${pendingUpdate.modified.size}，删除 ${pendingUpdate.removed.size}")
                    (pendingUpdate.added + pendingUpdate.modified + pendingUpdate.removed).take(12).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updating && detail.auditStatus != SkillAuditStatus.FAIL,
                    onClick = {
                        updating = true
                        scope.launch {
                            message = runCatching {
                                withContext(Dispatchers.IO) {
                                    val item = installation.toRemoteItem()
                                    catalog.withArchive(item) { input ->
                                        repository.importRemoteArchive(
                                            input = input,
                                            sourceRepository = requireNotNull(installation.sourceRepository),
                                            skillName = item.skillId,
                                            auditStatus = detail.auditStatus,
                                            versionLabel = detail.contentHash,
                                            auditJson = detail.audit?.toString(),
                                        )
                                    }
                                }
                                showUpdateDialog = false
                                "Skill 已更新"
                            }.getOrElse { it.message ?: "更新失败" }
                            updating = false
                        }
                    },
                ) { Text(if (detail.auditStatus == SkillAuditStatus.FAIL) "审计失败" else "确认更新") }
            },
            dismissButton = { TextButton(enabled = !updating, onClick = { showUpdateDialog = false }) { Text("取消") } },
        )
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } })
    }
}

private fun com.android.everytalk.data.database.entities.SkillInstallationEntity.toRemoteItem(): RemoteSkillCatalogItem {
    val source = sourceRepository?.removePrefix("https://github.com/") ?: error("Skill 来源仓库无效")
    val remoteName = sourcePath?.trim('/')?.substringAfterLast('/')?.takeIf { it != "." }.orEmpty().ifBlank { name }
    return RemoteSkillCatalogItem(source = source, skillId = remoteName, name = name)
}

@Composable
private fun DetailCard(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.65f))
    }
}
