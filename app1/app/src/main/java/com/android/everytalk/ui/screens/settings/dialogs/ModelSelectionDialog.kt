package com.android.everytalk.ui.screens.settings.dialogs
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
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
    var selectedTab by remember { mutableStateOf(ModelCatalogTab.NEW) }
    val groups = remember(models, existingModels) { classifyModelCatalog(models, existingModels) }

    LaunchedEffect(showDialog, models, existingModels) {
        if (showDialog) {
            selectedModels = emptySet()
            searchText = ""
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
                    if (selectedTab == ModelCatalogTab.NEW && groups.newModels.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedModels = if (selectedModels.size == groups.newModels.size) {
                                    emptySet()
                                } else {
                                    groups.newModels.toSet()
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (selectedModels.size == groups.newModels.size) {
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

                ModelCatalogTabs(
                    selectedTab = selectedTab,
                    groups = groups,
                    onSelect = {
                        selectedTab = it
                        searchText = ""
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))

                SkillStyleModelSearchField(
                    query = searchText,
                    onQueryChange = { searchText = it },
                    placeholder = stringResource(R.string.settings_search_hint),
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
                                val canSelect = selectedTab == ModelCatalogTab.NEW
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
                TextButton(
                    onClick = onManualInput,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.settings_enter_model_manually),
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = contentColor
                        ),
                        border = BorderStroke(1.dp, borderColor)
                    ) {
                        Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (selectedModels.isNotEmpty()) {
                                onSelectModels(selectedModels.toList())
                            } else {
                                onSelectModels(groups.newModels)
                            }
                        },
                        enabled = selectedTab == ModelCatalogTab.NEW && groups.newModels.isNotEmpty(),
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
                                selectedTab != ModelCatalogTab.NEW -> stringResource(R.string.settings_models_no_action)
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        fontSize = 12.sp,
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

/** 与 Skill 页面相同的胶囊搜索框视觉。 */
@Composable
private fun SkillStyleModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    val isDark = isSystemInDarkTheme()
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val fieldBackground = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF7F7F8)
    val fieldBorder = if (isDark) Color(0xFF383838) else Color(0xFFE5E5E5)
    val mutedColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(fieldBackground, RoundedCornerShape(percent = 50))
            .border(1.dp, fieldBorder, RoundedCornerShape(percent = 50))
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = mutedColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(placeholder, color = mutedColor, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                cursorBrush = SolidColor(contentColor),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.settings_search_clear),
                    tint = mutedColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
