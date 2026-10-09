package com.android.everytalk.ui.screens.skill

import com.android.everytalk.util.locale.localizeUiMessage
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.skill.SkillFileManifestEntry
import com.android.everytalk.data.skill.InstalledSkillPackage
import com.android.everytalk.data.skill.RemoteSkillCatalogItem
import com.android.everytalk.data.skill.RemoteSkillPackageCatalogItem
import com.android.everytalk.data.skill.RemoteSkillPackageDetail
import com.android.everytalk.data.skill.SkillCatalogClient
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.data.skill.SkillSecretMetadata
import com.android.everytalk.data.skill.SkillSecretStore
import com.android.everytalk.data.skill.effectivePackageId
import com.android.everytalk.data.skill.toInstalledSkillPackages
import com.android.everytalk.navigation.Screen
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
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
    val packages = remember(installations) { installations.toInstalledSkillPackages() }
    val skillPackage = packages.firstOrNull { it.packageId == skillId }
        ?: packages.firstOrNull { candidate -> candidate.children.any { it.skillId == skillId } }
    var selectedChildId by remember(skillPackage?.packageId) {
        mutableStateOf(skillPackage?.children?.firstOrNull()?.skillId)
    }
    val installation = skillPackage?.children?.firstOrNull { it.skillId == selectedChildId }
        ?: skillPackage?.children?.firstOrNull()
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<SkillFileManifestEntry>>(emptyList()) }
    var markdown by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var remoteDetail by remember { mutableStateOf<RemoteSkillPackageDetail?>(null) }
    var updating by remember { mutableStateOf(false) }
    var savedSecrets by remember { mutableStateOf<List<SkillSecretMetadata>>(emptyList()) }
    var pendingSecretDelete by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val dialogBg = appDialogContainerColor()
    val dialogBorder = appDialogBorderColor()
    val dialogContent = appDialogContentColor()
    val cardBackground = if (isDark) Color(0xFF1E1E1E) else Color.White
    val cardBorder = if (isDark) Color(0xFF333333) else Color(0xFFECECEC)

    LaunchedEffect(installation?.currentHash, skillPackage?.packageId) {
        val current = installation ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            files = repository.manifest(current.skillId, current.currentHash)
            markdown = repository.readSkillMarkdown(current.skillId, current.currentHash)
            savedSecrets = secretStore.list(current.skillId)
            if (current.sourceType == SkillSourceType.REMOTE.name && skillPackage != null) {
                runCatching {
                    val item = skillPackage.toRemoteItem()
                    val detail = catalog.packageDetail(item)
                    remoteDetail = detail
                    val installedRemoteHash = repository.packageVersionLabel(skillPackage.packageId)
                    val available = detail.contentHash.takeIf { it != installedRemoteHash }
                    repository.markPackageAvailableUpdate(skillPackage.packageId, available)
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
                    } ?: error(context.getString(R.string.skill_file_unreadable))
                }
                context.getString(R.string.skill_file_saved)
            }.getOrElse { it.message ?: context.getString(R.string.skill_file_save_failed) }
        }
    }

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 62.dp
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0.dp)) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (installation == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.skill_deleted)) }
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
                        Text(skillPackage?.name?.substringAfterLast('/').orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.skill_count, skillPackage?.children?.size ?: 0, skillPackage?.children?.size ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(skillPackage?.children.orEmpty(), key = { it.skillId }) { child ->
                        val selected = child.skillId == installation.skillId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (selected) dialogContent.copy(alpha = 0.22f) else cardBorder, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { selectedChildId = child.skillId },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) dialogContent.copy(alpha = if (isDark) 0.10f else 0.06f) else cardBackground,
                            ),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Text(child.name, fontWeight = FontWeight.SemiBold)
                                Text(context.localizeUiMessage(child.description), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        Text(installation.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(context.localizeUiMessage(installation.description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        DetailCard(stringResource(R.string.skill_source), installation.sourceRepository ?: (runCatching { SkillSourceType.valueOf(installation.sourceType) }.getOrNull()?.let { stringResource(it.labelRes) } ?: installation.sourceType))
                        DetailCard(stringResource(R.string.skill_current_version), installation.currentHash)
                        skillPackage?.updateHash?.let { DetailCard(stringResource(R.string.skill_available_version), it) }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (installation.sourceType == SkillSourceType.REMOTE.name) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { repository.copyAsUserSkill(installation.skillId) } }
                                                .onSuccess { copy -> navController.navigate(Screen.skillDetail(copy.effectivePackageId())) }
                                                .onFailure { message = it.message ?: context.getString(R.string.ui_message_copy_failed) }
                                        }
                                    },
                                    shape = AppDialogButtonShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                                ) {
                                    Icon(painterResource(R.drawable.ic_gpt_edit), null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.skill_copy_and_edit), fontWeight = FontWeight.SemiBold)
                                }
                                if (skillPackage?.updateHash != null && remoteDetail != null) {
                                    OutlinedButton(
                                        onClick = { showUpdateDialog = true },
                                        shape = AppDialogButtonShape,
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                                        border = BorderStroke(1.dp, dialogBorder),
                                    ) {
                                        Text(stringResource(R.string.skill_view_update), fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { showEditor = true },
                                    shape = AppDialogButtonShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                                ) {
                                    Icon(painterResource(R.drawable.ic_gpt_edit), null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.skill_edit_rules), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (installation.sourceType != SkillSourceType.REMOTE.name) {
                        item {
                            Text(stringResource(R.string.skill_attached_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("scripts" to stringResource(R.string.skill_scripts), "references" to stringResource(R.string.skill_references), "templates" to stringResource(R.string.skill_templates), "assets" to stringResource(R.string.chat_input_image)).forEach { (path, label) ->
                                    OutlinedButton(
                                        onClick = { pendingCategory = path; filePicker.launch(arrayOf("*/*")) },
                                        modifier = Modifier.weight(1f),
                                        shape = AppDialogButtonShape,
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                                        border = BorderStroke(1.dp, dialogBorder),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                    ) {
                                        Icon(painterResource(R.drawable.ic_plus), null, modifier = Modifier.size(14.dp))
                                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                    items(files, key = SkillFileManifestEntry::path) { file ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, cardBorder, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBackground),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text("${file.size} B · ${file.sha256.take(10)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                                if (installation.sourceType != SkillSourceType.REMOTE.name && file.path != "SKILL.md") {
                                    IconButton(onClick = { pendingDelete = file.path }) { Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.skill_delete_file), tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                    if (savedSecrets.isNotEmpty()) {
                        item { Text(stringResource(R.string.skill_saved_secrets), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                        items(savedSecrets, key = SkillSecretMetadata::name) { secret ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(secret.name, Modifier.weight(1f))
                                IconButton(onClick = { pendingSecretDelete = secret.name }) { Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.skill_delete_secret), tint = MaterialTheme.colorScheme.error) }
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
                    contentDescription = stringResource(R.string.account_back),
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { navController.popBackStack() },
                )
                Text(stringResource(R.string.skill_detail_title), Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showEditor && installation != null) {
        var edited by remember(markdown) { mutableStateOf(markdown) }
        AlertDialog(
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { showEditor = false },
            title = { Text(stringResource(R.string.skill_edit_markdown), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    minLines = 12,
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            message = runCatching { withContext(Dispatchers.IO) { repository.updateSkillMarkdown(installation.skillId, edited) }; showEditor = false; context.getString(R.string.skill_rules_saved) }
                                .getOrElse { it.message ?: context.getString(R.string.skill_save_failed) }
                        }
                    },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showEditor = false },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text(stringResource(R.string.account_cancel), fontWeight = FontWeight.SemiBold) }
            },
        )
    }
    pendingDelete?.let { path ->
        val currentInstallation = installation ?: return@let
        AlertDialog(
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.skill_delete_file_title), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text(path, style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        scope.launch { message = runCatching { withContext(Dispatchers.IO) { repository.deleteFile(currentInstallation.skillId, path) }; context.getString(R.string.skill_file_deleted) }.getOrElse { it.message ?: context.getString(R.string.skill_delete_failed) } }
                    },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White),
                ) { Text(stringResource(R.string.action_delete), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDelete = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text(stringResource(R.string.account_cancel), fontWeight = FontWeight.SemiBold) }
            },
        )
    }
    pendingSecretDelete?.let { name ->
        val currentInstallation = installation ?: return@let
        AlertDialog(
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { pendingSecretDelete = null },
            title = { Text(stringResource(R.string.skill_delete_secret_title), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text(name, style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingSecretDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) { secretStore.delete(currentInstallation.skillId, name); savedSecrets = secretStore.list(currentInstallation.skillId) }
                            message = context.getString(R.string.skill_secret_deleted)
                        }
                    },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White),
                ) { Text(stringResource(R.string.action_delete), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingSecretDelete = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text(stringResource(R.string.account_cancel), fontWeight = FontWeight.SemiBold) }
            },
        )
    }
    val pendingUpdate = remoteDetail?.takeIf { showUpdateDialog && skillPackage?.updateHash != null }
    if (pendingUpdate != null && skillPackage != null) {
        val detail = pendingUpdate
        AlertDialog(
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { if (!updating) showUpdateDialog = false },
            title = { Text(stringResource(R.string.skill_update_title, skillPackage.name.substringAfterLast('/')), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.skill_update_version, detail.contentHash.take(12)), fontWeight = FontWeight.Medium)
                    Text(pluralStringResource(R.plurals.skill_update_count, detail.skills.size, detail.skills.size))
                    detail.skills.take(12).forEach { child ->
                        Text(child.name, style = MaterialTheme.typography.labelSmall, color = dialogContent.copy(alpha = 0.7f))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !updating,
                    onClick = {
                        updating = true
                        scope.launch {
                            message = runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.importRemotePackage(
                                        detail = detail,
                                        downloadArchive = { target, progress ->
                                            catalog.downloadRemotePackageArchive(detail, target, progress)
                                        },
                                    )
                                }
                                showUpdateDialog = false
                                context.getString(R.string.skill_updated)
                            }.getOrElse { it.message ?: context.getString(R.string.skill_update_failed) }
                            updating = false
                        }
                    },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                ) { Text(stringResource(R.string.skill_confirm_update), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !updating,
                    onClick = { showUpdateDialog = false },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text(stringResource(R.string.account_cancel), fontWeight = FontWeight.SemiBold) }
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { message = null },
            text = { Text(context.localizeUiMessage(text), style = MaterialTheme.typography.bodyMedium, color = dialogContent) },
            confirmButton = {
                Button(
                    onClick = { message = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                ) { Text(stringResource(R.string.action_got_it), fontWeight = FontWeight.SemiBold) }
            },
        )
    }
}

private fun InstalledSkillPackage.toRemoteItem(): RemoteSkillPackageCatalogItem {
    val source = sourceRepository?.removePrefix("https://github.com/") ?: error("Skill 来源仓库无效")
    return RemoteSkillPackageCatalogItem(
        source = source,
        name = source.substringAfterLast('/').replaceFirstChar(Char::uppercaseChar),
        matchedSkills = children.map { child ->
            RemoteSkillCatalogItem(source = source, skillId = child.sourcePath.orEmpty(), name = child.name)
        },
    )
}

@Composable
private fun DetailCard(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.65f), fontWeight = FontWeight.Medium)
    }
}
