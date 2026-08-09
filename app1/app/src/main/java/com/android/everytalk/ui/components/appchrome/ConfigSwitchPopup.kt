package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs
import com.android.everytalk.ui.screens.settings.localizedChannelLabel
import com.android.everytalk.ui.screens.settings.localizedProviderLabel

data class ConfigGroup(
    val provider: String,
    val address: String,
    val key: String,
    val channel: String,
    val models: List<ApiConfig>
) {
    val displayName: String
        get() = provider.ifBlank { channel }
}

fun List<ApiConfig>.groupByConfig(): List<ConfigGroup> {
    return groupBy { Triple(it.provider, it.address, it.key) }
        .map { (key, configs) ->
            ConfigGroup(
                provider = key.first,
                address = key.second,
                key = key.third,
                channel = configs.first().channel,
                models = sortModelConfigs(configs)
            )
        }
        .sortedBy { it.displayName.lowercase() }
}

@Composable
fun ConfigSwitchPopup(
    visible: Boolean,
    allConfigs: List<ApiConfig>,
    selectedApiConfig: ApiConfig?,
    onModelSelected: (ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf<ConfigGroup?>(null) }
    var displayedGroup by remember { mutableStateOf<ConfigGroup?>(null) }

    LaunchedEffect(selectedGroup) {
        selectedGroup?.let { displayedGroup = it }
    }

    LaunchedEffect(visible) {
        if (!visible) selectedGroup = null
    }

    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)

    val configGroups = remember(allConfigs) { allConfigs.groupByConfig() }

    AppFloatingCardPopup(
        visible = visible && selectedGroup == null,
        alignment = Alignment.TopStart,
        offset = IntOffset(0, with(LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .widthIn(max = 280.dp)
            .heightIn(max = 400.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_configuration_switch_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = subtextColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            configGroups.forEach { group ->
                val isCurrentGroup = selectedApiConfig?.let {
                    it.provider == group.provider &&
                        it.address == group.address &&
                        it.key == group.key
                } == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedGroup = group }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isCurrentGroup) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (group.provider.isBlank()) {
                            localizedChannelLabel(group.channel)
                        } else {
                            localizedProviderLabel(group.provider)
                        },
                        fontSize = 14.sp,
                        fontWeight = if (isCurrentGroup) FontWeight.Medium else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    displayedGroup?.let { group ->
        ModelPickerDialog(
            visible = visible && selectedGroup != null,
            group = group,
            selectedApiConfig = selectedApiConfig,
            onModelSelected = { config ->
                onModelSelected(config)
                onDismiss()
            },
            onDismiss = { selectedGroup = null }
        )
    }
}

@Composable
private fun ModelPickerDialog(
    visible: Boolean,
    group: ConfigGroup,
    selectedApiConfig: ApiConfig?,
    onModelSelected: (ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)

    AppFloatingCardPopup(
        visible = visible,
        alignment = Alignment.TopStart,
        offset = IntOffset(0, with(LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .widthIn(max = 280.dp)
            .heightIn(max = 400.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (group.provider.isBlank()) {
                    localizedChannelLabel(group.channel)
                } else {
                    localizedProviderLabel(group.provider)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = subtextColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            sortModelConfigs(group.models).forEach { config ->
                val isSelected = config.id == selectedApiConfig?.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onModelSelected(config) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isSelected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = config.name.ifEmpty { config.model },
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
