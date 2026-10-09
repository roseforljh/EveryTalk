package com.android.everytalk.ui.screens.skill

import com.android.everytalk.util.locale.localizeUiMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource

import com.android.everytalk.util.AppToast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.skill.RemoteSkillCatalogItem
import com.android.everytalk.data.skill.RemoteSkillInstallProgress
import com.android.everytalk.data.skill.RemoteSkillInstallStage
import com.android.everytalk.data.skill.SkillCatalogClient
import com.android.everytalk.data.skill.SkillCatalogCollection
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.catalogPrefetchPages
import com.android.everytalk.data.skill.toRemotePackageCatalogItem
import com.android.everytalk.data.skill.toInstalledSkillPackages
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.search.ExpandableSearchBar
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.computer.TopCircleButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

@Composable
fun SkillDownloadScreen(navController: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { SkillRepository(context) }
    val installed by repository.observeAll().collectAsState(initial = emptyList())
    val installedPackages = remember(installed) { installed.toInstalledSkillPackages() }
    val catalog = remember(context) { SkillCatalogClient(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf(SkillCatalogCollection.POPULAR) }
    var catalogItems by remember { mutableStateOf<List<RemoteSkillCatalogItem>>(emptyList()) }
    var loadedPage by remember { mutableStateOf(0) }
    var maxCatalogPage by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var loadMoreRetry by remember { mutableStateOf(0) }
    var refreshRetry by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RemoteSkillCatalogItem?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var usingOfflineCache by remember { mutableStateOf(false) }
    var installStartedAt by remember { mutableStateOf(0L) }
    var installElapsedSeconds by remember { mutableStateOf(0L) }
    var installStatusText by remember { mutableStateOf(context.getString(R.string.skill_preparing_download)) }
    val installProgress = remember { AtomicReference<RemoteSkillInstallProgress?>(null) }

    val dialogBg = appDialogContainerColor()
    val dialogBorder = appDialogBorderColor()
    val dialogContent = appDialogContentColor()

    LaunchedEffect(query, collection, refreshRetry) {
        if (query.isNotBlank()) delay(250)
        loading = true
        refreshing = true
        error = null
        usingOfflineCache = false
        catalogItems = emptyList()
        loadedPage = 0
        maxCatalogPage = 1
        hasMore = false
        loadMoreError = null
        if (query.isBlank()) {
            withContext(Dispatchers.IO) { catalog.cachedCollectionPage(collection, 1) }?.let { cached ->
                catalogItems = cached.skills
                loadedPage = cached.page
                maxCatalogPage = catalogPageCount(cached.total, cached.pageSize)
                hasMore = cached.hasMore
                loading = false
            }
        }
        try {
            if (query.isBlank()) {
                val page = withContext(Dispatchers.IO) { catalog.collectionPage(collection, 1) }
                catalogItems = page.skills
                loadedPage = page.page
                maxCatalogPage = catalogPageCount(page.total, page.pageSize)
                hasMore = page.hasMore
                usingOfflineCache = catalog.usedOfflineCache
            } else {
                catalogItems = withContext(Dispatchers.IO) { catalog.search(query) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            if (catalogItems.isEmpty()) error = cause.message ?: context.getString(R.string.skill_catalog_failed)
        } finally {
            loading = false
            refreshing = false
        }
    }

    /** 当前页变化时静默预取前后各三页。滚动加载只会读取已经写好的持久缓存。 */
    LaunchedEffect(query, collection, loadedPage, maxCatalogPage, refreshing) {
        if (query.isNotBlank() || loadedPage <= 0 || refreshing) return@LaunchedEffect
        val pages = catalogPrefetchPages(loadedPage, maxCatalogPage)
        withContext(Dispatchers.IO) {
            coroutineScope {
                pages.map { page ->
                    async { runCatching { catalog.collectionPage(collection, page) } }
                }.awaitAll()
            }
        }
    }

    LaunchedEffect(installing, installStartedAt) {
        while (installing) {
            installElapsedSeconds = ((System.currentTimeMillis() - installStartedAt) / 1_000L).coerceAtLeast(0)
            installProgress.get()?.let { progress ->
                installStatusText = when (progress.stage) {
                    RemoteSkillInstallStage.DOWNLOADING -> formatDownloadProgress(context, progress)
                    RemoteSkillInstallStage.INSTALLING -> context.getString(R.string.skill_installing_files, progress.completed, progress.total)
                }
            }
            delay(250)
        }
    }

    fun install(item: RemoteSkillCatalogItem) {
        installing = true
        installError = null
        installStartedAt = System.currentTimeMillis()
        installElapsedSeconds = 0
        installStatusText = context.getString(R.string.skill_reading_version)
        installProgress.set(null)
        val packageItem = item.toRemotePackageCatalogItem()
        scope.launch {
            runCatching {
                val installDetail = withContext(Dispatchers.IO) { catalog.packageDetail(packageItem) }
                withContext(Dispatchers.IO) {
                    repository.importRemotePackage(
                        detail = installDetail,
                        downloadArchive = { target, report ->
                            catalog.downloadRemotePackageArchive(installDetail, target, report)
                        },
                        onProgress = installProgress::set,
                    )
                }
            }.onSuccess { installedPackage ->
                AppToast.show(context, context.resources.getQuantityString(R.plurals.skill_installed_count, installedPackage.children.size, item.name, installedPackage.children.size))
                selected = null
            }.onFailure { installError = it.message ?: context.getString(R.string.skill_install_failed) }
            installing = false
        }
    }

    val topButtonSize = 46.dp
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topButtonSize + 16.dp
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topPadding,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (usingOfflineCache) {
                    item {
                        Text(
                            stringResource(R.string.skill_catalog_cached),
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .clickable { refreshRetry += 1 },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                when {
                    loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = dialogContent, strokeWidth = 2.5.dp)
                            }
                        }
                    }
                    error != null -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(context.localizeUiMessage(error.orEmpty()), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    catalogItems.isEmpty() -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(R.string.skill_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> {
                        items(catalogItems, key = { "${it.source}#${it.skillId}" }) { skill ->
                            val isInstalled = installedPackages.any { it.packageId == "remote:${skill.source}" }
                            RemoteSkillCard(skill, isInstalled) { selected = skill }
                        }
                        if (query.isBlank() && hasMore && !refreshing) {
                            item(key = "load-more-${collection.name}-${loadedPage + 1}") {
                                LaunchedEffect(collection, loadedPage, loadMoreRetry) {
                                    if (loadingMore) return@LaunchedEffect
                                    loadingMore = true
                                    loadMoreError = null
                                    try {
                                        val next = withContext(Dispatchers.IO) {
                                            catalog.collectionPage(collection, loadedPage + 1)
                                        }
                                        catalogItems = (catalogItems + next.skills)
                                            .distinctBy { it.source to it.skillId }
                                        loadedPage = next.page
                                        hasMore = next.hasMore
                                        usingOfflineCache = usingOfflineCache || catalog.usedOfflineCache
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (cause: Exception) {
                                        loadMoreError = cause.message ?: context.getString(R.string.skill_more_failed)
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .then(
                                            if (loadMoreError != null) {
                                                Modifier.clickable { loadMoreRetry += 1 }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (loadMoreError == null) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = dialogContent,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(
                                            stringResource(R.string.skill_load_more_failed),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 悬浮顶栏：整合返回按钮、展开式搜索框、三标签筛选栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topButtonSize),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopCircleButton(
                        iconRes = R.drawable.ic_arrow_back,
                        contentDescription = stringResource(R.string.account_back),
                        modifier = Modifier,
                        onClick = { navController.popBackStack() },
                    )

                    Spacer(Modifier.width(8.dp))

                    ExpandableSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        isExpanded = isSearchExpanded,
                        onToggle = {
                            isSearchExpanded = !isSearchExpanded
                            if (!isSearchExpanded && query.isNotEmpty()) {
                                query = ""
                            }
                        },
                        placeholder = stringResource(R.string.skill_search_hint),
                        modifier = Modifier.weight(1f),
                        collapsedContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CatalogChip(stringResource(R.string.skill_popular), collection == SkillCatalogCollection.POPULAR) {
                                    query = ""
                                    collection = SkillCatalogCollection.POPULAR
                                }
                                CatalogChip(stringResource(R.string.skill_trending), collection == SkillCatalogCollection.TRENDING) {
                                    query = ""
                                    collection = SkillCatalogCollection.TRENDING
                                }
                                CatalogChip(stringResource(R.string.skill_official_collection), collection == SkillCatalogCollection.OFFICIAL) {
                                    query = ""
                                    collection = SkillCatalogCollection.OFFICIAL
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    selected?.let { skill ->
        val isInstalled = installedPackages.any { it.packageId == "remote:${skill.source}" }
        AlertDialog(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 340.dp)
                .wrapContentHeight()
                .border(1.dp, dialogBorder, AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            onDismissRequest = { if (!installing) selected = null },
            title = { Text(skill.name, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.skill_source_value, skill.source), style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f))
                    Text(stringResource(R.string.skill_installs_value, formatInstalls(skill.installs)), style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f))
                    Text(if (skill.isOfficial) stringResource(R.string.skill_official_label) else stringResource(R.string.skill_third_party), style = MaterialTheme.typography.bodyMedium, color = dialogContent.copy(alpha = 0.8f))
                    Text(
                        stringResource(R.string.skill_download_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = dialogContent.copy(alpha = 0.7f),
                    )
                    if (skill.githubRepository == null) {
                        Text(stringResource(R.string.skill_download_unsupported), color = MaterialTheme.colorScheme.error)
                    }
                    if (installing) {
                        Surface(
                            color = dialogContent.copy(alpha = 0.07f),
                            contentColor = dialogContent,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(installStatusText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.skill_install_elapsed, installElapsedSeconds), style = MaterialTheme.typography.labelSmall, color = dialogContent.copy(alpha = 0.65f))
                            }
                        }
                    } else {
                        // 未在下载时使用等高空白占位，确保无论是否正在下载，对话框高度完全一致不跳动
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                    installError?.let { message ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.skill_install_error, context.localizeUiMessage(message)),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.widthIn(min = 144.dp),
                    enabled = !installing && !isInstalled && skill.githubRepository != null,
                    onClick = { install(skill) },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dialogContent,
                        contentColor = dialogBg,
                        disabledContainerColor = dialogContent,
                        disabledContentColor = dialogBg,
                    ),
                ) {
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = dialogBg,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (isInstalled) stringResource(R.string.skill_installed) else stringResource(R.string.skill_download_install), fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !installing,
                    onClick = { selected = null },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = dialogContent),
                    border = BorderStroke(1.dp, dialogBorder),
                ) { Text(stringResource(R.string.account_cancel), fontWeight = FontWeight.SemiBold) }
            },
        )
    }

}

@Composable
private fun CatalogChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val activeColor = if (isDark) Color.White else Color.Black
    val activeOnColor = if (isDark) Color.Black else Color.White
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = activeColor,
            selectedLabelColor = activeOnColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (isDark) Color(0xFF414141) else Color(0xFFE0E0E0),
            selectedBorderColor = activeColor,
        ),
    )
}

@Composable
private fun RemoteSkillCard(
    skill: RemoteSkillCatalogItem,
    installed: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) Color(0xFF1E1E1E) else Color.White
    val cardBorder = if (isDark) Color(0xFF333333) else Color(0xFFECECEC)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (skill.isOfficial) {
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.skill_official_badge), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(skill.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.skill_installs_badge, formatInstalls(skill.installs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            if (installed) Icon(painterResource(R.drawable.ic_check_circle), stringResource(R.string.skill_installed), tint = if (isDark) Color.White else Color.Black)
        }
    }
}

private fun catalogPageCount(total: Int, pageSize: Int): Int =
    if (pageSize <= 0) 1 else ceil(total.toDouble() / pageSize).toInt().coerceAtLeast(1)

private fun formatDownloadProgress(context: android.content.Context, progress: RemoteSkillInstallProgress): String {
    val completed = formatBytes(progress.completed)
    return if (progress.total > 0) context.getString(R.string.skill_downloading_progress, completed, formatBytes(progress.total)) else context.getString(R.string.skill_downloading, completed)
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun formatInstalls(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}
