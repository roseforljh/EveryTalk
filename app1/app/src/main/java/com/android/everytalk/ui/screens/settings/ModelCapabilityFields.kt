package com.android.everytalk.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.R
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape

// 与 QoneAgent 的能力编辑器一致；目录中的 PDF 元数据仍保留，不单独增加编辑项。
private val modalityLabels = linkedMapOf("text" to "文本", "image" to "图片", "video" to "视频", "audio" to "音频")

/** 颜色只用于能力选项，同一种输入、输出能力保持同色；深色主题提高亮度以保证文字清楚。 */
@Composable
private fun modalitySelectionColor(modality: String): Color {
    val dark = isSystemInDarkTheme()
    return when (modality) {
        "image" -> if (dark) Color(0xFF6EE7B7) else Color(0xFF047857)
        "video" -> if (dark) Color(0xFFC4B5FD) else Color(0xFF6D28D9)
        "audio" -> if (dark) Color(0xFFFCD34D) else Color(0xFF92400E)
        else -> if (dark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)
    }
}

/** 能力覆盖保存在模型自身，和目录缓存分开；至少保留一种输入与输出。 */
@Composable
internal fun ModelCapabilityFields(config: ApiConfig, onChange: (ApiConfig) -> Unit) {
    val parameters = config.modelParameters
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("输入与输出", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { onChange(config.copy(modelParameters = parameters.copy(inputModalities = null, outputModalities = null, supportsReasoning = null))) }) { Text("使用目录能力") }
        }
        Text(if (parameters.resolvedCapability?.modalitiesSource == ModelCapabilitySource.COMMUNITY_CATALOG) "models.dev 标准 · 可手动调整" else "自定义模型能力 · 点击刷新从 models.dev 匹配", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(true, false).forEach { input ->
            val selected = if (input) config.inputModalities() else config.outputModalities()
            Text(if (input) "输入" else "输出", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modalityLabels.forEach { (id, label) ->
                    val selectedColor = modalitySelectionColor(id)
                    FilterChip(selected = id in selected, onClick = {
                        val next = if (id in selected) selected - id else selected + id
                        if (next.isNotEmpty()) onChange(config.copy(modelParameters = if (input) parameters.copy(inputModalities = next) else parameters.copy(outputModalities = next)))
                    }, label = { Text(label) },
                        shape = AppDialogTextFieldShape,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = selectedColor.copy(alpha = 0.15f), selectedLabelColor = selectedColor),
                        border = BorderStroke(1.dp, if (id in selected) selectedColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
        if (config.outputModalities().any { it in setOf("audio", "video") }) Text("音频／视频输出能力会保存；当前聊天尚未接入这些生成接口。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 不同图片接口只展示现有请求实现支持的参数，切换格式不会清空其他格式的值。 */
@Composable
internal fun ImageModelParameterFields(config: ApiConfig, onChange: (ApiConfig) -> Unit) {
    val parameters = config.modelParameters
    val format = config.imageApiFormat()
    val fields = imageParameterKeys(format)
    var guidanceText by remember(config.id, format) { mutableStateOf(config.guidanceScale?.toString().orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Text("图片生成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (format == "unsupported") Text("当前 API 协议未接入图片生成，请在 API 配置中选择支持图片生成的接口。")
        if ("imageSize" in fields) {
            if (format == "openai") OutlinedTextField(value = config.imageSize.orEmpty(), onValueChange = { onChange(config.copy(imageSize = it.ifBlank { null })) }, label = { Text("图片尺寸") }, placeholder = { Text("1024x1024") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = AppDialogTextFieldShape)
            else ImageParameterChoices("图片尺寸", config.imageSize.orEmpty(), if (format == "gemini") listOf("1K", "2K", "4K") else listOf("HD", "2K")) {
                onChange(config.copy(imageSize = it.ifBlank { null }))
            }
        }
        val ratios = if (format == "modal") {
            if (config.imageSize.equals("2K", true)) listOf("1:1", "16:9", "9:16", "4:3") else listOf("1:1", "16:9", "9:16")
        } else listOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
        if ("aspectRatio" in fields) ImageParameterChoices("宽高比", parameters.imageAspectRatio.orEmpty(), ratios) {
            onChange(config.copy(modelParameters = parameters.copy(imageAspectRatio = it.ifBlank { null })))
        }
        if ("inferenceSteps" in fields) OutlinedTextField(value = config.numInferenceSteps?.toString().orEmpty(), onValueChange = { text -> if (text.isEmpty() || text.toIntOrNull() in 1..1000) onChange(config.copy(numInferenceSteps = text.toIntOrNull())) }, label = { Text("推理步数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if ("guidanceScale" in fields) OutlinedTextField(value = guidanceText, onValueChange = { text ->
            if (text.isEmpty() || text.toFloatOrNull()?.let { it.isFinite() && it in 0f..100f } == true) {
                guidanceText = text
                onChange(config.copy(guidanceScale = text.toFloatOrNull()))
            }
        }, label = { Text("引导系数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if ("quality" in fields) OutlinedTextField(value = parameters.imageQuality.orEmpty(), onValueChange = { onChange(config.copy(modelParameters = parameters.copy(imageQuality = it.ifBlank { null }))) }, label = { Text("图片质量") }, placeholder = { Text("auto / low / medium / high") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if ("batchSize" in fields) OutlinedTextField(value = parameters.imageBatchSize?.toString().orEmpty(), onValueChange = { text -> if (text.isEmpty() || text.toIntOrNull() in 1..10) onChange(config.copy(modelParameters = parameters.copy(imageBatchSize = text.toIntOrNull()))) }, label = { Text("生成数量") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (format == "qwen") Text("Qwen Image Edit 需要上传参考图片。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 原生接口使用固定选项；默认项保留服务端默认值，不再让用户填写任意字符串。 */
@Composable
private fun ImageParameterChoices(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("") + options).forEach { option ->
            val selected = value == option
            FilterChip(selected = selected, onClick = { onChange(option) }, label = { Text(option.ifEmpty { "默认" }) },
                shape = AppDialogTextFieldShape,
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = Color.Transparent, selectedLabelColor = MaterialTheme.colorScheme.onSurface),
                border = null,
                leadingIcon = if (selected) { { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.state_selected)) } } else null,
            )
        }
    }
}
