package com.android.everytalk.ui.screens.skill

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.RemoteSkillCatalogItem
import com.android.everytalk.data.skill.SkillCatalogClient
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.navigation.Screen
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.ui.screens.computer.TopCircleButton
import com.android.everytalk.ui.screens.settings.SettingsTabMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SkillScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { SkillRepository(context) }
    val skills by repository.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val catalog = remember(context) { SkillCatalogClient(context) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showTabMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDeleteSkill by remember { mutableStateOf<SkillInstallationEntity?>(null) }

    LaunchedEffect(skills.map { it.skillId to it.currentHash }) {
        withContext(Dispatchers.IO) {
            skills.filter { it.sourceType == SkillSourceType.REMOTE.name }.forEach { skill ->
                runCatching {
                    val item = skill.remoteCatalogItem()
                    val remote = catalog.currentHash(item)
                    val installedRemoteHash = repository.versionLabel(skill.skillId, skill.currentHash) ?: skill.currentHash
                    val update = remote.contentHash.takeIf { it != installedRemoteHash }
                    if (update != skill.updateHash) repository.markAvailableUpdate(skill.skillId, update)
                }
            }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { repository.importZip(it) }
                        ?: error("无法读取压缩包")
                }
                "Skill 已添加"
            }.getOrElse { it.message ?: "Skill 添加失败" }
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) { repository.importDocumentTree(uri) }
                "Skill 已添加"
            }.getOrElse { it.message ?: "Skill 添加失败" }
        }
    }

    fun returnToSettings(tabIndex: Int? = null, openImportExport: Boolean = false) {
        val settingsEntry = runCatching { navController.getBackStackEntry(Screen.SETTINGS_SCREEN) }.getOrNull()
        if (settingsEntry != null) {
            tabIndex?.let { settingsEntry.savedStateHandle[Screen.SETTINGS_TAB_REQUEST_KEY] = it }
            if (openImportExport) settingsEntry.savedStateHandle[Screen.SETTINGS_IMPORT_EXPORT_REQUEST_KEY] = true
            navController.popBackStack(Screen.SETTINGS_SCREEN, inclusive = false)
        } else {
            navController.navigate(Screen.SETTINGS_SCREEN) { launchSingleTop = true }
        }
        showTabMenu = false
    }

    val topButtonSize = 46.dp
    val isDark = isSystemInDarkTheme()
    val buttonBackground = if (isDark) Color(0xFF303030) else Color.White
    val buttonContent = if (isDark) Color.White else Color(0xFF0D0D0D)
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topButtonSize + 24.dp

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (skills.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_prompt),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("还没有 Skill", style = MaterialTheme.typography.titleLarge)
                    Text("通过右上角加号添加、下载或创建", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    items(skills, key = SkillInstallationEntity::skillId) { skill ->
                        SkillCard(
                            skill = skill,
                            onOpen = { navController.navigate(Screen.skillDetail(skill.skillId)) },
                            onToggle = { enabled -> scope.launch(Dispatchers.IO) { repository.setEnabled(skill.skillId, enabled) } },
                            onDelete = { pendingDeleteSkill = skill },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp),
            ) {
                TopCircleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = "返回",
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { navController.popBackStack() },
                )
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    Row(
                        modifier = Modifier
                            .width(topButtonSize * 2)
                            .height(topButtonSize)
                            .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(buttonBackground),
                    ) {
                        Box(
                            modifier = Modifier.size(topButtonSize).clip(CircleShape).clickable { showAddMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_plus), "添加", tint = buttonContent, modifier = Modifier.size(20.dp))
                        }
                        Box(
                            modifier = Modifier.size(topButtonSize).clip(CircleShape).clickable { showTabMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_dots_horizontal), "更多", tint = buttonContent, modifier = Modifier.size(20.dp))
                        }
                    }
                    AppFloatingCardPopup(
                        visible = showAddMenu,
                        alignment = Alignment.TopEnd,
                        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
                        onDismissRequest = { showAddMenu = false },
                        modifier = Modifier.widthIn(min = 150.dp, max = 190.dp),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            SkillMenuRow("添加") { showAddMenu = false; showSourcePicker = true }
                            SkillMenuRow("下载") {
                                showAddMenu = false
                                navController.navigate(Screen.SKILL_DOWNLOAD_SCREEN)
                            }
                            SkillMenuRow("创建 Skill") { showAddMenu = false; showCreateDialog = true }
                        }
                    }
                    SettingsTabMenu(
                        expanded = showTabMenu,
                        tabs = listOf("配置", "联网搜索", "MCP"),
                        currentTabIndex = -1,
                        onTabSelected = { returnToSettings(it) },
                        onImportExport = { returnToSettings(openImportExport = true) },
                        onOpenComputers = { navController.navigate(Screen.COMPUTER_SCREEN) },
                        onOpenSkills = { showTabMenu = false },
                        isSkillSelected = true,
                        onDismiss = { showTabMenu = false },
                    )
                }
            }
        }
    }

    if (showSourcePicker) {
        AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            title = { Text("添加 Skill") },
            text = { Text("本地 Skill 未经过云端审计。确认来源可信后，再选择包含 SKILL.md 的文件夹或 ZIP 压缩包。") },
            confirmButton = {
                TextButton(onClick = { showSourcePicker = false; directoryPicker.launch(null) }) { Text("选择文件夹") }
            },
            dismissButton = {
                TextButton(onClick = { showSourcePicker = false; zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text("选择 ZIP")
                }
            },
        )
    }
    pendingDeleteSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSkill = null },
            title = { Text("删除 ${skill.name}？") },
            text = { Text("已安装文件和记录会一起删除。历史消息中的标签仍会保留显示。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteSkill = null
                    scope.launch(Dispatchers.IO) { repository.delete(skill.skillId) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSkill = null }) { Text("取消") } },
        )
    }
    if (showCreateDialog) {
        CreateSkillDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description, rules ->
                scope.launch {
                    message = runCatching {
                        withContext(Dispatchers.IO) { repository.create(name, description, rules) }
                        showCreateDialog = false
                        "Skill 已创建"
                    }.getOrElse { it.message ?: "Skill 创建失败" }
                }
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

private fun SkillInstallationEntity.remoteCatalogItem(): RemoteSkillCatalogItem {
    val repository = sourceRepository?.removePrefix("https://github.com/") ?: error("Skill 来源仓库无效")
    val remoteName = sourcePath?.trim('/')?.substringAfterLast('/')?.takeIf { it != "." }.orEmpty().ifBlank { name }
    return RemoteSkillCatalogItem(source = repository, skillId = remoteName, name = name)
}

@Composable
private fun SkillCard(
    skill: SkillInstallationEntity,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${skill.sourceType} · ${skill.auditStatus} · ${skill.currentHash.take(10)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = skill.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SkillMenuRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建 Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("用途说明") })
                OutlinedTextField(rules, { rules = it }, label = { Text("具体规则") }, minLines = 4)
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, rules) },
                enabled = name.isNotBlank() && description.isNotBlank() && rules.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
