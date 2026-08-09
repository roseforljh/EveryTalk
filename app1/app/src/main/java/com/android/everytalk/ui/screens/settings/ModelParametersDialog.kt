package com.android.everytalk.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.CustomModelParameter
import com.android.everytalk.data.DataClass.CustomParameterType
import com.android.everytalk.data.DataClass.DEFAULT_MAX_OUTPUT_TOKENS
import com.android.everytalk.data.DataClass.DEFAULT_REASONING_EFFORT
import com.android.everytalk.data.DataClass.MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT
import com.android.everytalk.data.DataClass.MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.DataClass.defaultOpenAICompatibleParameters
import com.android.everytalk.data.DataClass.modelParameterProtocol
import com.android.everytalk.data.DataClass.openAICompatibleRequestParameters
import com.android.everytalk.data.DataClass.validateAutoContextCompressionThreshold
import com.android.everytalk.data.DataClass.validateModelTokenLimits
import com.android.everytalk.data.DataClass.withUserTokenLimits
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val codexThinkingLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
private val anthropicThinkingLevels = listOf("none", "low", "medium", "high", "max")
private val geminiThinkingLevels = listOf("none", "minimal", "low", "medium", "high")
private val openAICompatibleThinkingLevels = listOf("none", "low", "medium", "high", "xhigh", "max")

@Composable
private fun localizedThinkingLevel(option: String): String = when (option.trim().lowercase()) {
    "none" -> stringResource(R.string.model_parameters_reasoning_none)
    "minimal" -> stringResource(R.string.model_parameters_reasoning_minimal)
    "low" -> stringResource(R.string.model_parameters_reasoning_low)
    "medium" -> stringResource(R.string.model_parameters_reasoning_medium)
    "high" -> stringResource(R.string.model_parameters_reasoning_high)
    "xhigh" -> stringResource(R.string.model_parameters_reasoning_xhigh)
    "max" -> stringResource(R.string.model_parameters_reasoning_max)
    else -> option
}

internal fun parseModelTokenLimits(
    maxOutputTokens: String,
    maxContextTokens: String,
): ModelTokenLimits {
    val output = maxOutputTokens.trim().toIntOrNull()
        ?: throw IllegalArgumentException()
    val context = maxContextTokens.trim().toIntOrNull()
        ?: throw IllegalArgumentException()
    return validateModelTokenLimits(output, context)
}

@StringRes
internal fun modelCapabilitySourceLabelRes(source: ModelCapabilitySource): Int = when (source) {
    ModelCapabilitySource.USER_OVERRIDE -> R.string.model_capability_source_user_override
    ModelCapabilitySource.LIVE_ENDPOINT -> R.string.model_capability_source_live_endpoint
    ModelCapabilitySource.OFFICIAL_CATALOG -> R.string.model_capability_source_official_catalog
    ModelCapabilitySource.LOCAL_CACHE -> R.string.model_capability_source_local_cache
    ModelCapabilitySource.COMMUNITY_CATALOG -> R.string.model_capability_source_community_catalog
    ModelCapabilitySource.FAMILY_FALLBACK -> R.string.model_capability_source_family_fallback
    ModelCapabilitySource.CONSERVATIVE_DEFAULT -> R.string.model_capability_source_conservative_default
}

internal fun thinkingLevelOptions(protocol: ModelParameterProtocol): List<String> = when (protocol) {
    ModelParameterProtocol.CODEX -> codexThinkingLevels
    ModelParameterProtocol.ANTHROPIC -> anthropicThinkingLevels
    ModelParameterProtocol.GEMINI -> geminiThinkingLevels
    ModelParameterProtocol.OPENAI_COMPATIBLE -> openAICompatibleThinkingLevels
}

internal fun effectiveThinkingLevelOptions(
    protocol: ModelParameterProtocol,
    modelEfforts: Set<String> = emptySet(),
): List<String> {
    if (modelEfforts.isEmpty()) return thinkingLevelOptions(protocol)
    val defaults = thinkingLevelOptions(protocol)
    val normalized = modelEfforts
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
    return defaults.filter { preset -> normalized.any { it.equals(preset, ignoreCase = true) } } +
        normalized.filter { value -> defaults.none { it.equals(value, ignoreCase = true) } }
}

internal fun thinkingLevelMenuOptions(
    protocol: ModelParameterProtocol,
    currentValue: String,
    customValues: List<String> = emptyList(),
    modelEfforts: Set<String> = emptySet(),
): List<String> {
    val presets = effectiveThinkingLevelOptions(protocol, modelEfforts)
    val normalizedCurrent = normalizeThinkingLevel(protocol, currentValue, modelEfforts)
    if (protocol != ModelParameterProtocol.OPENAI_COMPATIBLE) return presets
    return (presets + normalizeCustomThinkingLevels(
        protocol = protocol,
        values = customValues + normalizedCurrent,
    )).distinctBy { it.lowercase() }
}

internal fun normalizeCustomThinkingLevels(
    protocol: ModelParameterProtocol,
    values: List<String>,
): List<String> {
    if (protocol != ModelParameterProtocol.OPENAI_COMPATIBLE) return emptyList()
    val presets = thinkingLevelOptions(protocol)
    return values
        .map(String::trim)
        .filter { value -> value.isNotEmpty() && presets.none { it.equals(value, ignoreCase = true) } }
        .distinct()
}

internal fun selectedThinkingLevelValue(
    protocol: ModelParameterProtocol,
    parameters: ModelParameters,
    modelEfforts: Set<String> = emptySet(),
): String {
    val selected = when (protocol) {
        ModelParameterProtocol.CODEX -> if (parameters.reasoningMode == ReasoningMode.DISABLED) {
            "none"
        } else {
            parameters.reasoningEffort
        }
        ModelParameterProtocol.ANTHROPIC,
        ModelParameterProtocol.GEMINI -> if (parameters.reasoningMode == ReasoningMode.DISABLED) {
            "none"
        } else {
            parameters.reasoningEffort
        }
        ModelParameterProtocol.OPENAI_COMPATIBLE -> {
            val reasoningParameter = (parameters.customParameters ?: defaultOpenAICompatibleParameters)
                .firstOrNull { it.name.trim().equals("reasoning_effort", ignoreCase = true) }
            if (reasoningParameter?.enabled == true) {
                reasoningParameter.value
            } else {
                "none"
            }
        }
    }
    val normalized = normalizeThinkingLevel(protocol, selected, modelEfforts).ifEmpty { DEFAULT_REASONING_EFFORT }
    return if (
        protocol == ModelParameterProtocol.OPENAI_COMPATIBLE ||
        normalized in effectiveThinkingLevelOptions(protocol, modelEfforts)
    ) {
        normalized
    } else {
        DEFAULT_REASONING_EFFORT
    }
}

internal fun automaticThinkingLevelValue(
    protocol: ModelParameterProtocol,
    currentValue: String,
    supportsReasoning: Boolean?,
    modelEfforts: Set<String> = emptySet(),
): String = when {
    supportsReasoning == false -> "none"
    modelEfforts.isNotEmpty() -> effectiveThinkingLevelOptions(protocol, modelEfforts).let { options ->
        options.firstOrNull { it.equals(currentValue, ignoreCase = true) }
            ?: options.firstOrNull { it == DEFAULT_REASONING_EFFORT }
            ?: options.firstOrNull { it != "none" }
            ?: "none"
    }
    supportsReasoning == true -> DEFAULT_REASONING_EFFORT
    protocol == ModelParameterProtocol.OPENAI_COMPATIBLE -> currentValue
    else -> DEFAULT_REASONING_EFFORT
}

internal fun applyThinkingLevelSelection(
    protocol: ModelParameterProtocol,
    parameters: ModelParameters,
    selectedValue: String,
    modelEfforts: Set<String> = emptySet(),
): ModelParameters {
    val normalizedValue = normalizeThinkingLevel(protocol, selectedValue, modelEfforts)
    require(normalizedValue.isNotEmpty())
    if (protocol != ModelParameterProtocol.OPENAI_COMPATIBLE) {
        require(normalizedValue in effectiveThinkingLevelOptions(protocol, modelEfforts))
    }
    if (
        parameters.reasoningMode == ReasoningMode.BUDGET &&
        normalizedValue == selectedThinkingLevelValue(protocol, parameters)
    ) {
        return parameters
    }
    return when (protocol) {
        ModelParameterProtocol.CODEX -> parameters.copy(
            reasoningMode = ReasoningMode.EFFORT,
            reasoningEffort = normalizedValue,
        )
        ModelParameterProtocol.ANTHROPIC,
        ModelParameterProtocol.GEMINI -> if (normalizedValue == "none") {
            parameters.copy(
                reasoningMode = ReasoningMode.DISABLED,
                reasoningEffort = normalizedValue,
            )
        } else {
            parameters.copy(
                reasoningMode = ReasoningMode.EFFORT,
                reasoningEffort = normalizedValue,
            )
        }
        ModelParameterProtocol.OPENAI_COMPATIBLE -> {
            parameters.copy(
                reasoningMode = ReasoningMode.EFFORT,
                reasoningEffort = normalizedValue,
                customReasoningEfforts = normalizeCustomThinkingLevels(
                    protocol = protocol,
                    values = parameters.customReasoningEfforts + normalizedValue,
                ),
                customParameters = updateOpenAIReasoningParameter(
                    parameters = parameters.customParameters ?: defaultOpenAICompatibleParameters,
                    effort = normalizedValue,
                ),
            )
        }
    }
}

private fun normalizeThinkingLevel(
    protocol: ModelParameterProtocol,
    value: String,
    modelEfforts: Set<String> = emptySet(),
): String {
    val trimmed = value.trim()
    return effectiveThinkingLevelOptions(protocol, modelEfforts)
        .firstOrNull { it.equals(trimmed, ignoreCase = true) }
        ?: trimmed
}

private fun updateOpenAIReasoningParameter(
    parameters: List<CustomModelParameter>,
    effort: String,
): List<CustomModelParameter> {
    val updated = CustomModelParameter(
        name = "reasoning_effort",
        value = effort,
        type = CustomParameterType.STRING,
        enabled = true,
    )
    val result = mutableListOf<CustomModelParameter>()
    var inserted = false
    parameters.forEach { parameter ->
        if (parameter.name.trim().equals("reasoning_effort", ignoreCase = true)) {
            if (!inserted) {
                result += updated
                inserted = true
            }
        } else {
            result += parameter
        }
    }
    if (!inserted) result += updated
    return result
}

@Composable
internal fun ModelParametersDialog(
    config: ApiConfig,
    onDismissRequest: () -> Unit,
    onConfirm: (ApiConfig) -> Unit,
    onAutoLoad: (suspend (ApiConfig) -> Result<ApiConfig>)? = null,
) {
    val context = LocalContext.current
    val unknownErrorLabel = stringResource(R.string.unknown_error)
    val invalidParametersLabel = stringResource(R.string.model_parameters_invalid)
    val autoFetchLabel = stringResource(R.string.model_parameters_auto_fetch)
    val autoFetchingLabel = stringResource(R.string.model_parameters_auto_fetching)
    val protocol = remember(config.channel) { modelParameterProtocol(config.channel) }
    val coroutineScope = rememberCoroutineScope()
    var workingConfig by remember(config) { mutableStateOf(config) }
    var selectedValue by remember(config.id, protocol, config.modelParameters) {
        mutableStateOf(
            selectedThinkingLevelValue(
                protocol,
                config.modelParameters,
                config.modelParameters.resolvedCapability?.reasoningEfforts.orEmpty(),
            )
        )
    }
    var customValues by remember(config.id, protocol, config.modelParameters) {
        mutableStateOf(
            normalizeCustomThinkingLevels(
                protocol = protocol,
                values = config.modelParameters.customReasoningEfforts,
            )
        )
    }
    var maxOutputTokens by remember(config.id, config.maxTokens) {
        mutableStateOf((config.maxTokens ?: DEFAULT_MAX_OUTPUT_TOKENS).toString())
    }
    var maxContextTokens by remember(config.id, config.modelParameters.maxContextTokens) {
        mutableStateOf(config.modelParameters.maxContextTokens.toString())
    }
    var autoCompressionEnabled by remember(config.id, config.modelParameters.autoContextCompressionEnabled) {
        mutableStateOf(config.modelParameters.autoContextCompressionEnabled)
    }
    var autoCompressionThreshold by remember(
        config.id,
        config.modelParameters.autoContextCompressionThresholdPercent,
    ) {
        mutableStateOf(
            config.modelParameters.autoContextCompressionThresholdPercent.coerceIn(
                MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT,
                MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT,
            )
        )
    }
    var errorText by remember(config.id) { mutableStateOf<String?>(null) }
    var isAutoLoading by remember(config.id) { mutableStateOf(false) }
    val resolvedCapability = workingConfig.modelParameters.resolvedCapability
    val modelEfforts = resolvedCapability?.reasoningEfforts.orEmpty()
    val menuOptions = thinkingLevelMenuOptions(
        protocol,
        selectedValue,
        customValues,
        modelEfforts,
    )
    val dialogBackground = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val borderColor = appDialogBorderColor()
    val maxOutputSource = resolvedCapability?.maxOutputSource ?: ModelCapabilitySource.USER_OVERRIDE
    val contextWindowSource = resolvedCapability?.contextWindowSource ?: ModelCapabilitySource.USER_OVERRIDE
    val reasoningSource = resolvedCapability?.reasoningSource
    val refreshRotation = if (isAutoLoading) {
        val transition = rememberInfiniteTransition(label = "模型参数加载旋转")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
            ),
            label = "模型参数加载角度",
        )
        rotation
    } else {
        0f
    }

    fun autoLoad() {
        val loader = onAutoLoad ?: return
        if (isAutoLoading) return
        coroutineScope.launch {
            isAutoLoading = true
            errorText = null
            try {
                loader(workingConfig).fold(
                    onSuccess = { loadedConfig ->
                        workingConfig = loadedConfig
                        selectedValue = automaticThinkingLevelValue(
                            protocol = protocol,
                            currentValue = selectedThinkingLevelValue(
                                protocol,
                                loadedConfig.modelParameters,
                                loadedConfig.modelParameters.resolvedCapability?.reasoningEfforts.orEmpty(),
                            ),
                            supportsReasoning = loadedConfig.modelParameters
                                .resolvedCapability
                                ?.supportsReasoning,
                            modelEfforts = loadedConfig.modelParameters
                                .resolvedCapability
                                ?.reasoningEfforts
                                .orEmpty(),
                        )
                        customValues = normalizeCustomThinkingLevels(
                            protocol = protocol,
                            values = loadedConfig.modelParameters.customReasoningEfforts,
                        )
                        maxOutputTokens = (
                            loadedConfig.maxTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
                        ).toString()
                        maxContextTokens = loadedConfig.modelParameters.maxContextTokens.toString()
                    },
                    onFailure = { error ->
                        errorText = context.getString(
                            R.string.model_parameters_auto_fetch_failed,
                            error.message ?: unknownErrorLabel,
                        )
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorText = context.getString(
                    R.string.model_parameters_auto_fetch_failed,
                    error.message ?: unknownErrorLabel,
                )
            } finally {
                isAutoLoading = false
            }
        }
    }

    fun save() {
        val update = try {
            val limits = parseModelTokenLimits(maxOutputTokens, maxContextTokens)
            val compressionThreshold = validateAutoContextCompressionThreshold(autoCompressionThreshold)
            val updated = applyThinkingLevelSelection(
                protocol = protocol,
                parameters = workingConfig.modelParameters.copy(
                    customReasoningEfforts = normalizeCustomThinkingLevels(protocol, customValues),
                    maxContextTokens = limits.maxContextTokens,
                    autoContextCompressionEnabled = autoCompressionEnabled,
                    autoContextCompressionThresholdPercent = compressionThreshold,
                ),
                selectedValue = selectedValue,
                modelEfforts = modelEfforts,
            )
            if (protocol == ModelParameterProtocol.OPENAI_COMPATIBLE) {
                updated.openAICompatibleRequestParameters()
            }
            updated to limits
        } catch (_: IllegalArgumentException) {
            errorText = invalidParametersLabel
            return
        } catch (_: Exception) {
            errorText = invalidParametersLabel
            return
        }
        onConfirm(
            workingConfig.copy(modelParameters = update.first)
                .withUserTokenLimits(update.second)
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, borderColor, AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBackground,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_parameters_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = config.name.ifBlank { config.model },
                        style = MaterialTheme.typography.bodySmall,
                        color = appDialogSubtextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = ::autoLoad,
                    enabled = onAutoLoad != null && !isAutoLoading,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription = if (isAutoLoading) autoFetchingLabel else autoFetchLabel
                        },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(refreshRotation),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ModelParameterRow(
                    label = stringResource(R.string.model_parameters_reasoning_effort),
                    supportingText = reasoningSource?.let {
                        stringResource(
                            R.string.model_parameters_reasoning_strength_source,
                            stringResource(modelCapabilitySourceLabelRes(it)),
                        )
                    } ?: stringResource(R.string.model_parameters_reasoning_strength),
                ) {
                    ThinkingLevelDropdown(
                        options = menuOptions,
                        value = selectedValue,
                        allowCustom = protocol == ModelParameterProtocol.OPENAI_COMPATIBLE,
                        onValueChange = {
                            selectedValue = it
                            errorText = null
                        },
                        onCustomValueCommitted = { customValue ->
                            customValues = normalizeCustomThinkingLevels(
                                protocol = protocol,
                                values = customValues + customValue,
                            )
                        },
                        onCustomValueDeleted = { customValue ->
                            customValues = customValues.filterNot { it == customValue }
                            if (selectedValue.trim() == customValue) {
                                selectedValue = DEFAULT_REASONING_EFFORT
                            }
                            errorText = null
                        },
                    )
                }
                HorizontalDivider(color = borderColor.copy(alpha = 0.55f))
                ModelParameterRow(
                    label = stringResource(R.string.model_parameters_max_output),
                    supportingText = stringResource(
                        R.string.model_parameters_max_output_description,
                        stringResource(modelCapabilitySourceLabelRes(maxOutputSource)),
                    ),
                ) {
                    TokenValueField(
                        value = maxOutputTokens,
                        contentDescription = stringResource(
                            R.string.model_parameters_max_output_content_description,
                        ),
                        onValueChange = {
                            maxOutputTokens = it
                            errorText = null
                        },
                    )
                }
                HorizontalDivider(color = borderColor.copy(alpha = 0.55f))
                ModelParameterRow(
                    label = stringResource(R.string.model_parameters_context_window),
                    supportingText = stringResource(
                        R.string.model_parameters_context_window_description,
                        stringResource(modelCapabilitySourceLabelRes(contextWindowSource)),
                    ),
                ) {
                    TokenValueField(
                        value = maxContextTokens,
                        contentDescription = stringResource(
                            R.string.model_parameters_context_window_content_description,
                        ),
                        onValueChange = {
                            maxContextTokens = it
                            errorText = null
                        },
                    )
                }
                HorizontalDivider(color = borderColor.copy(alpha = 0.55f))
                AutoContextCompressionSection(
                    enabled = autoCompressionEnabled,
                    thresholdPercent = autoCompressionThreshold,
                    onEnabledChange = {
                        autoCompressionEnabled = it
                        errorText = null
                    },
                    onThresholdChange = {
                        autoCompressionThreshold = it
                        errorText = null
                    },
                )
                errorText?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = AppDialogButtonShape,
                    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.18f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = dialogBackground,
                        contentColor = contentColor,
                    ),
                ) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = ::save,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBackground,
                    ),
                ) {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun ModelParameterRow(
    label: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = appDialogSubtextColor(),
            )
        }
        content()
    }
}

@Composable
private fun TokenValueField(
    value: String,
    contentDescription: String,
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val contentColor = appDialogContentColor()
    BasicTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= 8 && candidate.all(Char::isDigit)) onValueChange(candidate)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(
            color = contentColor,
            textAlign = TextAlign.End,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { innerTextField() }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.model_parameters_token_unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = appDialogSubtextColor(),
                )
            }
        },
        modifier = Modifier
            .width(148.dp)
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), shape)
            .padding(horizontal = 12.dp)
            .semantics { this.contentDescription = contentDescription },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelDropdown(
    options: List<String>,
    value: String,
    allowCustom: Boolean,
    onValueChange: (String) -> Unit,
    onCustomValueCommitted: (String) -> Unit,
    onCustomValueDeleted: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var isEditingCustom by remember { mutableStateOf(false) }
    val enterValueLabel = stringResource(R.string.model_parameters_enter_value)
    val collapseLabel = stringResource(R.string.model_parameters_collapse_reasoning)
    val expandLabel = stringResource(R.string.model_parameters_expand_reasoning)
    val dropdownLabel = stringResource(R.string.model_parameters_reasoning_dropdown)
    val displayedValue = if (isEditingCustom) value else localizedThinkingLevel(value)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "思考程度展开箭头",
    )
    val menuShape = RoundedCornerShape(16.dp)
    val borderColor = appDialogBorderColor()
    val presetOptions = if (allowCustom) {
        thinkingLevelOptions(ModelParameterProtocol.OPENAI_COMPATIBLE)
    } else {
        options
    }
    fun finishCustomEditing() {
        val customValue = value.trim()
        if (customValue.isEmpty()) return
        onValueChange(customValue)
        onCustomValueCommitted(customValue)
        isEditingCustom = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    LaunchedEffect(isEditingCustom) {
        if (isEditingCustom) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!isEditingCustom) expanded = it },
        modifier = Modifier.widthIn(min = 116.dp, max = 132.dp),
    ) {
        BasicTextField(
            value = displayedValue,
            onValueChange = if (isEditingCustom) onValueChange else { _ -> },
            readOnly = !isEditingCustom,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = appDialogContentColor()),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finishCustomEditing() }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = enterValueLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = appDialogSubtextColor(),
                            )
                        }
                        innerTextField()
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_gpt_chevron_right),
                        contentDescription = if (expanded) collapseLabel else expandLabel,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation),
                    )
                }
            },
            modifier = Modifier
                .menuAnchor(
                    if (isEditingCustom) {
                        ExposedDropdownMenuAnchorType.PrimaryEditable
                    } else {
                        ExposedDropdownMenuAnchorType.PrimaryNotEditable
                    }
                )
                .focusRequester(focusRequester)
                .width(132.dp)
                .height(44.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = dropdownLabel },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .border(1.dp, borderColor, menuShape)
                .dropdownMenuViewport(maxHeight = 280.dp),
            shape = menuShape,
            containerColor = appDialogContainerColor(),
        ) {
            if (allowCustom) {
                val customLabel = stringResource(R.string.model_parameters_custom)
                ThinkingLevelOptionRow(
                    option = customLabel,
                    displayOption = customLabel,
                    isSelected = false,
                    isCustom = false,
                    onSelect = {
                        expanded = false
                        onValueChange("")
                        isEditingCustom = true
                    },
                    onDelete = {},
                )
            }
            options.forEach { option ->
                val isSelected = option == value.trim()
                val isCustomOption = allowCustom && option !in presetOptions
                ThinkingLevelOptionRow(
                    option = option,
                    displayOption = localizedThinkingLevel(option),
                    isSelected = isSelected,
                    isCustom = isCustomOption,
                    onSelect = {
                        onValueChange(option)
                        if (isCustomOption) onCustomValueCommitted(option)
                        isEditingCustom = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        expanded = false
                    },
                    onDelete = {
                        onCustomValueDeleted(option)
                        if (isSelected) {
                            onValueChange(DEFAULT_REASONING_EFFORT)
                            isEditingCustom = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ThinkingLevelOptionRow(
    option: String,
    displayOption: String,
    isSelected: Boolean,
    isCustom: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val selectedDescription = stringResource(R.string.model_parameters_selected_option, displayOption)
    val deleteDescription = stringResource(R.string.model_parameters_delete_custom, displayOption)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clickable(onClick = onSelect)
                .padding(start = 14.dp, end = if (isCustom) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayOption,
                modifier = Modifier.weight(1f),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isSelected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = selectedDescription,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (isCustom) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = deleteDescription },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 去掉 Material 3 菜单自带的上下留白，同时保留其定位、动画与滚动行为。 */
private fun Modifier.dropdownMenuViewport(maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val defaultVerticalPadding = 8.dp.roundToPx()
    val viewportMaxHeight = minOf(constraints.maxHeight, maxHeight.roundToPx())
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = 0,
            maxHeight = viewportMaxHeight + defaultVerticalPadding * 2,
        )
    )
    val viewportHeight = (placeable.height - defaultVerticalPadding * 2)
        .coerceIn(minOf(constraints.minHeight, viewportMaxHeight), viewportMaxHeight)
    layout(placeable.width, viewportHeight) {
        placeable.placeRelative(0, -defaultVerticalPadding)
    }
}
