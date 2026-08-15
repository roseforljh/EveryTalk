package com.android.everytalk.ui.screens.skill

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.android.everytalk.data.skill.RemoteSkillCatalogItem
import com.android.everytalk.data.skill.RemoteSkillDetail
import com.android.everytalk.data.skill.SkillAuditStatus
import com.android.everytalk.data.skill.SkillCatalogClient
import com.android.everytalk.data.skill.SkillCatalogCollection
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.computer.TopCircleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SkillDownloadScreen(navController: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { SkillRepository(context) }
    val installed by repository.observeAll().collectAsState(initial = emptyList())
    val catalog = remember(context) { SkillCatalogClient(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf(SkillCatalogCollection.POPULAR) }
    var skills by remember { mutableStateOf<List<RemoteSkillCatalogItem>>(emptyList()) }
    var selected by remember { mutableStateOf<RemoteSkillCatalogItem?>(null) }
    var detail by remember { mutableStateOf<RemoteSkillDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var riskConfirmation by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var usingOfflineCache by remember { mutableStateOf(false) }

    LaunchedEffect(query, collection) {
        if (query.isNotBlank()) delay(250)
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val items = if (query.isBlank()) catalog.collection(collection) else catalog.search(query)
                items to catalog.usedOfflineCache
            }
        }.onSuccess { (items, offline) -> skills = items; usingOfflineCache = offline }
            .onFailure { error = it.message ?: "云目录加载失败" }
        loading = false
    }

    LaunchedEffect(selected?.source, selected?.skillId) {
        val item = selected ?: return@LaunchedEffect
        detail = null
        detailError = null
        detailLoading = true
        runCatching { withContext(Dispatchers.IO) { catalog.detail(item) } }
            .onSuccess { detail = it }
            .onFailure { detailError = it.message ?: "详情加载失败" }
        detailLoading = false
    }

    fun install(item: RemoteSkillCatalogItem) {
        installing = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    catalog.withArchive(item) { input ->
                        repository.importRemoteArchive(
                            input = input,
                            sourceRepository = requireNotNull(item.githubRepository),
                            skillName = item.skillId,
                            auditStatus = detail?.auditStatus ?: SkillAuditStatus.UNVERIFIED,
                            versionLabel = detail?.contentHash,
                            auditJson = detail?.audit?.toString(),
                        )
                    }
                }
            }.onSuccess { selected = null }
                .onFailure { detailError = it.message ?: "Skill 安装失败" }
            installing = false
        }
    }

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(top = topPadding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("搜索 Skill") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatalogChip("热门", collection == SkillCatalogCollection.POPULAR) {
                        query = ""
                        collection = SkillCatalogCollection.POPULAR
                    }
                    CatalogChip("趋势", collection == SkillCatalogCollection.TRENDING) {
                        query = ""
                        collection = SkillCatalogCollection.TRENDING
                    }
                    CatalogChip("官方精选", collection == SkillCatalogCollection.OFFICIAL) {
                        query = ""
                        collection = SkillCatalogCollection.OFFICIAL
                    }
                }
                if (usingOfflineCache) {
                    Text(
                        "当前离线，显示上次缓存的目录",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                    skills.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有找到相关 Skill", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(skills, key = { "${it.source}#${it.skillId}" }) { skill ->
                            val isInstalled = installed.any {
                                it.sourceRepository == skill.githubRepository && it.name.equals(skill.name, ignoreCase = true)
                            }
                            RemoteSkillCard(skill, isInstalled) { selected = skill }
                        }
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
                Text(
                    "下载 Skill",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    selected?.let { skill ->
        val isInstalled = installed.any {
            it.sourceRepository == skill.githubRepository && it.name.equals(skill.name, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { if (!installing) selected = null },
            title = { Text(skill.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("来源：${skill.source}")
                    Text("安装量：${formatInstalls(skill.installs)}")
                    Text(if (skill.isOfficial) "skills.sh 官方标记" else "第三方维护")
                    when {
                        detailLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                        detail != null -> {
                            Text("路径：${detail?.sourcePath}")
                            Text("哈希：${detail?.contentHash?.take(16)}")
                            Text("安全审计：${detail?.auditStatus}")
                            detail?.updatedAt?.let { Text("更新时间：$it") }
                            Text("文件：${detail?.files?.size ?: 0} 个")
                            detail?.files?.take(8)?.forEach { file ->
                                Text("${file.path} · ${file.size} B", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        else -> Text(
                            "${detailError ?: "未取得审计结果"}。仍可按未审计 Skill 安装，安装后默认关闭。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (skill.githubRepository == null) {
                        Text("当前来源暂不支持 Android 端下载", color = MaterialTheme.colorScheme.error)
                    }
                    if (installing) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(28.dp))
                }
            },
            confirmButton = {
                Button(
                    enabled = !installing && !isInstalled && skill.githubRepository != null && detail?.auditStatus != SkillAuditStatus.FAIL,
                    onClick = {
                        if (detail?.auditStatus == SkillAuditStatus.PASS) install(skill) else riskConfirmation = true
                    },
                ) { Text(if (isInstalled) "已安装" else if (detail?.auditStatus == SkillAuditStatus.FAIL) "审计失败" else "下载并安装") }
            },
            dismissButton = { TextButton(enabled = !installing, onClick = { selected = null }) { Text("取消") } },
        )
    }

    if (riskConfirmation) {
        val item = selected
        AlertDialog(
            onDismissRequest = { riskConfirmation = false },
            title = { Text("确认安装未通过审计的 Skill？") },
            text = { Text("该 Skill 将默认关闭。开启前请查看文件内容，Skill 也不能绕过现有工具权限。") },
            confirmButton = { TextButton(onClick = { riskConfirmation = false; if (item != null) install(item) }) { Text("确认安装") } },
            dismissButton = { TextButton(onClick = { riskConfirmation = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CatalogChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun RemoteSkillCard(
    skill: RemoteSkillCatalogItem,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (skill.isOfficial) {
                        Spacer(Modifier.width(6.dp))
                        Text("官方", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(skill.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${formatInstalls(skill.installs)} 次安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installed) Icon(Icons.Default.CheckCircle, "已安装", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatInstalls(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}
