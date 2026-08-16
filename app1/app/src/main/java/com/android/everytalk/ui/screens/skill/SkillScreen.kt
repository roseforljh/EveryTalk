package com.android.everytalk.ui.screens.skill

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.skill.InstalledSkillPackage
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.RemoteSkillCatalogItem
import com.android.everytalk.data.skill.RemoteSkillPackageCatalogItem
import com.android.everytalk.data.skill.SkillCatalogClient
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.navigation.Screen
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
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
    val packages by repository.observePackages().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val catalog = remember(context) { SkillCatalogClient(context) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showTabMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDeletePackage by remember { mutableStateOf<InstalledSkillPackage?>(null) }

    val isDark = isSystemInDarkTheme()
    val dialogBg = appDialogContainerColor()
    val dialogBorder = appDialogBorderColor()
    val dialogContent = appDialogContentColor()

    LaunchedEffect(packages.map { it.packageId to it.children.map { child -> child.currentHash } }) {
        val repairFailures = withContext(Dispatchers.IO) {
            packages.filter { it.sourceType == SkillSourceType.REMOTE }.mapNotNull { skillPackage ->
                runCatching {
                    val item = skillPackage.remoteCatalogItem()
                    val remote = catalog.packageDetail(item)
                    val installedRemoteHash = repository.packageVersionLabel(skillPackage.packageId)
                    val migratedSingleInstall = skillPackage.children.any { it.packageName.contains('/') }
                    if (migratedSingleInstall) {
                        repository.importRemotePackage(remote) { detail, entry, target ->
                            catalog.downloadRemotePackageFile(detail, entry, target)
                        }
                    } else {
                        val update = remote.contentHash.takeIf { it != installedRemoteHash }
                        if (update != skillPackage.updateHash) {
                            repository.markPackageAvailableUpdate(skillPackage.packageId, update)
                        }
                    }
                }.exceptionOrNull()?.let { "${skillPackage.name} 补齐失败：${it.message}" }
            }
        }
        if (repairFailures.isNotEmpty()) message = repairFailures.joinToString("\n")
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    val packageName = uri.lastPathSegment?.substringAfterLast(':')?.substringBeforeLast('.')
                    context.contentResolver.openInputStream(uri)?.use { repository.importZip(it, packageName) }
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
                val packageName = uri.lastPathSegment?.substringAfterLast(':')
                withContext(Dispatchers.IO) { repository.importDocumentTree(uri, packageName) }
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

    fun returnToChatHome() {
        showTabMenu = false
        showAddMenu = false
        if (!navController.popBackStack(Screen.CHAT_SCREEN, inclusive = false)) {
            navController.navigate(Screen.CHAT_SCREEN) { launchSingleTop = true }
        }
    }

    BackHandler(onBack = ::returnToChatHome)

    val topButtonSize = 46.dp
    val buttonBackground = if (isDark) Color(0xFF303030) else Color.White
    val buttonContent = if (isDark) Color.White else Color(0xFF0D0D0D)
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topButtonSize + 24.dp

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (packages.isEmpty()) {
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
                    items(packages, key = InstalledSkillPackage::packageId) { skillPackage ->
                        SkillCard(
                            skillPackage = skillPackage,
                            onOpen = { navController.navigate(Screen.skillDetail(skillPackage.packageId)) },
                            onToggle = { enabled -> scope.launch(Dispatchers.IO) { repository.setPackageEnabled(skillPackage.packageId, enabled) } },
                            onDelete = { pendingDeletePackage = skillPackage },
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
                    onClick = ::returnToChatHome,
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
                        modifier = Modifier.widthIn(min = 100.dp, max = 136.dp),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { showSourcePicker = false },
            title = { Text("添加 Skill", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text("选择包含 SKILL.md 的文件夹或 ZIP 压缩包。", style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f)) },
            confirmButton = {
                Button(
                    onClick = { showSourcePicker = false; directoryPicker.launch(null) },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                ) { Text("选择文件夹", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showSourcePicker = false; zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text("选择 ZIP", fontWeight = FontWeight.SemiBold) }
            },
        )
    }
    pendingDeletePackage?.let { skillPackage ->
        AlertDialog(
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { pendingDeletePackage = null },
            title = { Text("删除 ${skillPackage.name}？", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text("已安装文件和记录会一起删除。历史消息中的标签仍会保留显示。", style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeletePackage = null
                        scope.launch(Dispatchers.IO) { repository.deletePackage(skillPackage.packageId) }
                    },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White),
                ) { Text("删除", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDeletePackage = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text("取消", fontWeight = FontWeight.SemiBold) }
            },
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
            modifier = Modifier
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { message = null },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = dialogContent) },
            confirmButton = {
                Button(
                    onClick = { message = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
                ) { Text("知道了", fontWeight = FontWeight.SemiBold) }
            },
        )
    }
}

private fun InstalledSkillPackage.remoteCatalogItem(): RemoteSkillPackageCatalogItem {
    val repository = sourceRepository?.removePrefix("https://github.com/") ?: error("Skill 来源仓库无效")
    return RemoteSkillPackageCatalogItem(
        source = repository,
        name = repository.substringAfterLast('/').replaceFirstChar(Char::uppercaseChar),
        matchedSkills = children.map { child ->
            RemoteSkillCatalogItem(source = repository, skillId = child.sourcePath.orEmpty(), name = child.name)
        },
    )
}

@Composable
private fun SkillCard(
    skillPackage: InstalledSkillPackage,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) Color(0xFF1E1E1E) else Color.White
    val cardBorder = if (isDark) Color(0xFF333333) else Color(0xFFECECEC)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skillPackage.name.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = skillPackage.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (isDark) Color.Black else Color.White,
                        checkedTrackColor = if (isDark) Color.White else Color.Black,
                        uncheckedThumbColor = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
                        uncheckedTrackColor = if (isDark) Color(0xFF383838) else Color(0xFFE0E0E0),
                    )
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Text(
                text = skillPackage.children.take(3).joinToString(" · ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            Text(
                text = "${skillPackage.children.size} 个 Skill · ${skillPackage.sourceType.name}" +
                    if (skillPackage.updateHash != null) " · 有更新" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SkillMenuRow(label: String, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
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
    val isDark = isSystemInDarkTheme()
    val dialogBg = appDialogContainerColor()
    val dialogBorder = appDialogBorderColor()
    val dialogContent = appDialogContentColor()

    AlertDialog(
        modifier = Modifier
            .wrapContentHeight()
            .border(1.dp, dialogBorder, AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = dialogContent,
        textContentColor = dialogContent,
        onDismissRequest = onDismiss,
        title = { Text("创建 Skill", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("用途说明") },
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it },
                    label = { Text("具体规则") },
                    minLines = 4,
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, rules) },
                enabled = name.isNotBlank() && description.isNotBlank() && rules.isNotBlank(),
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = dialogContent, contentColor = dialogBg),
            ) { Text("创建", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                border = BorderStroke(1.dp, dialogBorder),
            ) { Text("取消", fontWeight = FontWeight.SemiBold) }
        },
    )
}
