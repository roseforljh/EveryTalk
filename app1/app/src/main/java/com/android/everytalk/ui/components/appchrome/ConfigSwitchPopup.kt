package com.android.everytalk.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                models = configs
            )
        }
}

@Composable
fun ConfigSwitchPopup(
    visible: Boolean,
    allConfigs: List<ApiConfig>,
    selectedApiConfig: ApiConfig?,
    onModelSelected: (ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var showPopup by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    var selectedGroup by remember { mutableStateOf<ConfigGroup?>(null) }

    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(visible) {
        if (visible) {
            showPopup = true
            scaleAnim.snapTo(0.8f)
            alphaAnim.snapTo(0f)
            coroutineScope {
                launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
            }
        } else if (showPopup) {
            coroutineScope {
                launch { alphaAnim.animateTo(0f, tween(75, easing = decelerateEasing)) }
                launch { delay(74); scaleAnim.snapTo(0.8f) }
            }
            showPopup = false
            selectedGroup = null
        }
    }

    if (!showPopup) return

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val popupBorderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)

    val configGroups = remember(allConfigs) { allConfigs.groupByConfig() }

    if (selectedGroup == null) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, with(LocalDensity.current) { 48.dp.toPx().toInt() }),
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true)
        ) {
        Surface(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .widthIn(max = 280.dp)
                .heightIn(max = 400.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0.2f, 0f)
                }
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .border(1.dp, popupBorderColor, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = cardBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "切换配置",
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
                            text = group.displayName,
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
    }
    }

    if (selectedGroup != null) {
        ModelPickerDialog(
            group = selectedGroup!!,
            selectedApiConfig = selectedApiConfig,
            onModelSelected = { config ->
                onModelSelected(config)
                selectedGroup = null
                onDismiss()
            },
            onDismiss = { selectedGroup = null }
        )
    }
}

@Composable
private fun ModelPickerDialog(
    group: ConfigGroup,
    selectedApiConfig: ApiConfig?,
    onModelSelected: (ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val popupBorderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)

    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }
    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(Unit) {
        launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
        launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, with(LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .widthIn(max = 280.dp)
                .heightIn(max = 400.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0.2f, 0f)
                }
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .border(1.dp, popupBorderColor, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = cardBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = group.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = subtextColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                group.models.forEach { config ->
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
}
