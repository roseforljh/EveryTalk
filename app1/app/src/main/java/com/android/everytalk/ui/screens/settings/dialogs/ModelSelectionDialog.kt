package com.android.everytalk.ui.screens.settings.dialogs
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import com.android.everytalk.ui.components.search.ExpandableSearchBar
import java.util.Locale

internal enum class ModelCatalogTab {
    NEW,
    ADDED,
    REMOVED,
}

internal data class ModelCatalogGroups(
    val newModels: List<String>,
    val addedModels: List<String>,
    val removedModels: List<String>,
)

/** 按远端最新列表和本地已添加列表，把模型分成互不重叠的三类。 */
internal fun classifyModelCatalog(
    remoteModels: List<String>,
    existingModels: List<String>,
): ModelCatalogGroups {
    fun distinctModels(models: List<String>): List<String> = models
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }

    val remote = distinctModels(remoteModels)
    val existing = distinctModels(existingModels)
    val remoteIds = remote.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    val existingIds = existing.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    return ModelCatalogGroups(
        newModels = remote.filter { it.lowercase(Locale.ROOT) !in existingIds },
        addedModels = existing.filter { it.lowercase(Locale.ROOT) in remoteIds },
        removedModels = existing.filter { it.lowercase(Locale.ROOT) !in remoteIds },
    )
}

@Composable
fun ModelSelectionDialog(
    showDialog: Boolean,
    models: List<String>,
    existingModels: List<String>,
    onDismiss: () -> Unit,
    onSelectModels: (List<String>) -> Unit,
    onRemoveModels: (List<String>) -> Unit,
    onManualInput: () -> Unit
) {
    if (!showDialog) return

    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) Color.Black else Color.White
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)
    val selectedColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)

    var selectedModels by remember { mutableStateOf(setOf<String>()) }
    var searchText by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(ModelCatalogTab.NEW) }
    val groups = remember(models, existingModels) { classifyModelCatalog(models, existingModels) }

    LaunchedEffect(showDialog, models, existingModels) {
        if (showDialog) {
            selectedModels = emptySet()
            searchText = ""
            isSearchExpanded = false
            selectedTab = ModelCatalogTab.NEW
        }
    }

    val currentModels = when (selectedTab) {
        ModelCatalogTab.NEW -> groups.newModels
        ModelCatalogTab.ADDED -> groups.addedModels
        ModelCatalogTab.REMOVED -> groups.removedModels
    }
    val filteredModels = remember(currentModels, searchText) {
        if (searchText.isBlank()) currentModels
        else currentModels.filter { it.contains(searchText, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.86f)
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = dialogBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            stringResource(R.string.settings_select_models_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        Text(
                            stringResource(R.string.settings_model_tab_count, currentModels.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = subtextColor
                        )
                    }
                    if (selectedTab != ModelCatalogTab.ADDED && currentModels.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedModels = if (selectedModels.size == currentModels.size) {
                                    emptySet()
                                } else {
                                    currentModels.toSet()
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (selectedModels.size == currentModels.size) {
                                        R.string.action_clear_selection
                                    } else {
                                        R.string.action_select_all
                                    }
                                ),
                                color = contentColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                ExpandableSearchBar(
                    query = searchText,
                    onQueryChange = { searchText = it },
                    isExpanded = isSearchExpanded,
                    onToggle = {
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded && searchText.isNotEmpty()) {
                            searchText = ""
                        }
                    },
                    placeholder = stringResource(R.string.settings_search_hint),
                    collapsedContent = {
                        ModelCatalogTabs(
                            selectedTab = selectedTab,
                            groups = groups,
                            onSelect = {
                                selectedTab = it
                                selectedModels = emptySet()
                                searchText = ""
                            },
                        )
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 模型列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (filteredModels.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(
                                    if (searchText.isNotBlank()) {
                                        R.string.settings_no_matching_models
                                    } else {
                                        when (selectedTab) {
                                            ModelCatalogTab.NEW -> R.string.settings_no_new_models
                                            ModelCatalogTab.ADDED -> R.string.settings_no_added_models
                                            ModelCatalogTab.REMOVED -> R.string.settings_no_removed_models
                                        }
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = subtextColor
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                        ) {
                            items(filteredModels) { model ->
                                val isSelected = model in selectedModels
                                val canSelect = selectedTab != ModelCatalogTab.ADDED
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = canSelect) {
                                            selectedModels = if (isSelected) {
                                                selectedModels - model
                                            } else {
                                                selectedModels + model
                                            }
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (canSelect && isSelected) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = selectedColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = model,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = when {
                                            isSelected -> selectedColor
                                            selectedTab == ModelCatalogTab.REMOVED -> subtextColor
                                            else -> contentColor
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // 顶部渐变
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(20.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dialogBg, dialogBg.copy(alpha = 0f))
                                )
                            )
                    )

                    // 底部渐变
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dialogBg.copy(alpha = 0f), dialogBg)
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 手动输入模型按钮
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = onManualInput
                    ) {
                        Text(
                            stringResource(R.string.settings_enter_model_manually),
                            color = contentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 底部按钮栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, borderColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = contentColor
                        )
                    ) {
                        Text(
                            stringResource(R.string.action_cancel),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            when (selectedTab) {
                                ModelCatalogTab.NEW -> onSelectModels(
                                    selectedModels.ifEmpty { groups.newModels.toSet() }.toList()
                                )
                                ModelCatalogTab.REMOVED -> onRemoveModels(selectedModels.toList())
                                ModelCatalogTab.ADDED -> Unit
                            }
                        },
                        enabled = when (selectedTab) {
                            ModelCatalogTab.NEW -> groups.newModels.isNotEmpty()
                            ModelCatalogTab.REMOVED -> selectedModels.isNotEmpty()
                            ModelCatalogTab.ADDED -> false
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = dialogBg,
                            disabledContainerColor = borderColor,
                            disabledContentColor = subtextColor
                        )
                    ) {
                        Text(
                            when {
                                selectedTab == ModelCatalogTab.REMOVED -> stringResource(
                                    R.string.settings_remove_selected_models,
                                    selectedModels.size,
                                )
                                selectedTab == ModelCatalogTab.ADDED -> stringResource(R.string.settings_models_no_action)
                                selectedModels.isEmpty() -> stringResource(R.string.settings_add_all_models)
                                else -> stringResource(R.string.settings_add_selected_models, selectedModels.size)
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogTabs(
    selectedTab: ModelCatalogTab,
    groups: ModelCatalogGroups,
    onSelect: (ModelCatalogTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            Triple(ModelCatalogTab.NEW, R.string.settings_model_tab_new, groups.newModels.size),
            Triple(ModelCatalogTab.ADDED, R.string.settings_model_tab_added, groups.addedModels.size),
            Triple(ModelCatalogTab.REMOVED, R.string.settings_model_tab_removed, groups.removedModels.size),
        ).forEach { (tab, labelRes, count) ->
            val selected = selectedTab == tab
            val selectedBackground = if (isSystemInDarkTheme()) Color.White else Color.Black
            val selectedContent = if (isSystemInDarkTheme()) Color.Black else Color.White
            FilterChip(
                selected = selected,
                onClick = { onSelect(tab) },
                label = {
                    Text(
                        text = "${stringResource(labelRes)} $count",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = selectedBackground,
                    selectedLabelColor = selectedContent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = if (isSystemInDarkTheme()) Color(0xFF414141) else Color(0xFFE0E0E0),
                    selectedBorderColor = selectedBackground,
                ),
            )
        }
    }
}
